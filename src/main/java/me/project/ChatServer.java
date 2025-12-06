package me.project;

import com.sun.jna.Native;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private static final int PORT = 8888;
    private static final int HTTP_PORT = 8081;
    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WIN = OS.contains("win");
    private static final boolean IS_MAC = OS.contains("mac");

    // --- Структура для хранения загруженного плагина ---
    static class LoadedPlugin {
        PluginInterface lib;
        String name;
        String description;

        public LoadedPlugin(PluginInterface lib) {
            this.lib = lib;
            this.name = lib.get_name(); // Имя берем из кода C++
            this.description = lib.get_description(); // Описание берем из кода C++
        }
    }

    private static final Set<ClientHandler> clients = new CopyOnWriteArraySet<>();
    private static final Map<String, OfflineData> ipDb = new ConcurrentHashMap<>();
    // Теперь храним LoadedPlugin вместо простого интерфейса
    private static final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();

    static class OfflineData {
        String lastUsername;
        List<String> pendingMessages = new ArrayList<>();
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("jna.encoding", "UTF-8");
        File pluginDir = new File("plugins");
        if (!pluginDir.exists()) pluginDir.mkdirs();

        // Автозагрузка
        System.out.println("Scanning for plugins...");
        File[] files = pluginDir.listFiles((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib");
        });

        if (files != null) {
            for (File f : files) {
                try {
                    // Загружаем библиотеку
                    PluginInterface lib = Native.load(f.getAbsolutePath(), PluginInterface.class);
                    // Создаем обертку (внутри вызывается get_name() из C++)
                    LoadedPlugin plugin = new LoadedPlugin(lib);
                    plugins.put(plugin.name, plugin);
                    System.out.println(" [+] Loaded #" + plugin.name + ": " + plugin.description);
                } catch (Throwable e) {
                    System.err.println(" [-] Failed load " + f.getName() + ": " + e.getMessage());
                }
            }
        }

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", new FrontendHandler());
        httpServer.createContext("/compile", new CompileHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("HTTP Compiler on port " + HTTP_PORT);

        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Chat Server on port " + PORT);

        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            clients.add(new ClientHandler(serverSocket.accept()));
            pool.execute(clients.iterator().next()); // (Упрощено для примера, лучше переписать аккуратнее)
        }
    }

    public static void broadcast(String msg, String senderName, boolean isSystem) {
        for (ClientHandler client : clients) {
            if (!isSystem && client.blacklist.contains(senderName)) continue;
            String finalMsg = msg;
            if (client.favorites.contains(senderName)) finalMsg = "\u001B[33m[FAV] " + msg + "\u001B[0m";
            client.sendMessage(finalMsg);
        }
    }

    public static void sendPrivate(ClientHandler sender, String targetName, String msg, boolean isSystem) {
        String formattedMsg = isSystem ? msg : "\u001B[35m(Private) " + sender.username + ": " + msg + "\u001B[0m";
        boolean found = false;
        for (ClientHandler client : clients) {
            if (client.username.equals(targetName)) {
                if (!isSystem && client.blacklist.contains(sender.username)) {
                    sender.sendMessage("You are blacklisted."); return;
                }
                client.sendMessage(formattedMsg); found = true; break;
            }
        }
        if (!found && !isSystem) {
            sender.sendMessage("User not found / offline.");
            // (Offline logic omitted for brevity)
        }
    }

    static class ClientHandler implements Runnable {
        private Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        public String username;
        public String ip;
        public Set<String> blacklist = new HashSet<>();
        public Set<String> favorites = new HashSet<>();

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.ip = socket.getInetAddress().getHostAddress();
        }
        public void sendMessage(String msg) { out.println(msg); }

        private void sendHelp() {
            StringBuilder sb = new StringBuilder();
            sb.append("\u001B[36m=== HELP ===\u001B[0m\n");
            sb.append("@user msg   - Private Msg\n");
            sb.append("#block user - Blacklist\n");
            sb.append("#fav user   - Favorite\n");
            if (!plugins.isEmpty()) {
                sb.append("\u001B[36m--- Plugins ---\u001B[0m\n");
                for (LoadedPlugin p : plugins.values()) {
                    // Выводим имя и описание, которое дал создатель плагина
                    sb.append("#").append(p.name).append(" text -> ").append(p.description).append("\n");
                }
            }
            sendMessage(sb.toString());
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                out.println("Enter name:");
                username = in.readLine();
                if (username == null) return;

                broadcast(username + " joined.", "Server", true);
                sendHelp();

                String message;
                while ((message = in.readLine()) != null) {
                    if (message.isEmpty()) continue;
                    processMessage(message);
                }
            } catch (IOException e) {}
            finally {
                try { socket.close(); } catch (Exception e) {}
                clients.remove(this);
                broadcast(username + " left.", "Server", true);
            }
        }

        private void processMessage(String msg) {
            if (msg.startsWith("@")) {
                int sp = msg.indexOf(' ');
                if (sp != -1) sendPrivate(this, msg.substring(1, sp), msg.substring(sp + 1), false);
            } else if (msg.startsWith("#")) {
                String[] parts = msg.split(" ", 2);
                String cmd = parts[0].substring(1);
                String arg = parts.length > 1 ? parts[1].trim() : "";

                if (cmd.equals("help")) sendHelp();
                else if (cmd.equals("block")) { blacklist.add(arg); sendMessage("Blocked " + arg); }
                else if (cmd.equals("fav")) { favorites.add(arg); sendMessage("Fav " + arg); }
                else {
                    if (plugins.containsKey(cmd)) {
                        try {
                            // --- ГЛАВНОЕ ИЗМЕНЕНИЕ ---
                            // Передаем текст (arg) напрямую в плагин, не ищем userLastMsg
                            String res = plugins.get(cmd).lib.handle_message(username, arg);
                            broadcast(res, "System", true);
                        } catch (Exception e) {
                            sendMessage("Plugin Error: " + e.getMessage());
                        }
                    } else {
                        sendMessage("Unknown command.");
                    }
                }
            } else {
                broadcast(username + ": " + msg, username, false);
            }
        }
    }

    static class FrontendHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            byte[] bytes = Files.readAllBytes(new File("index.html").toPath());
            t.sendResponseHeaders(200, bytes.length);
            t.getResponseBody().write(bytes);
            t.close();
        }
    }

    static class CompileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8))) {
                    String line; while ((line = br.readLine()) != null) body.append(line);
                }

                Map<String, String> params = parse(body.toString());
                // Имя файла (filename) теперь нужно только для сохранения на диск
                // Имя команды определится кодом C++
                String filename = params.get("filename");
                String code = params.get("code");

                String result = compile(filename, code);
                t.sendResponseHeaders(200, result.getBytes(StandardCharsets.UTF_8).length);
                t.getResponseBody().write(result.getBytes(StandardCharsets.UTF_8));
                t.close();
            }
        }

        private Map<String, String> parse(String body) throws UnsupportedEncodingException {
            Map<String, String> m = new HashMap<>();
            for (String p : body.split("&")) {
                int i = p.indexOf("=");
                if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), "UTF-8"), URLDecoder.decode(p.substring(i+1), "UTF-8"));
            }
            return m;
        }

        private String compile(String filename, String code) {
            try {
                if (filename == null || filename.isEmpty()) return "Filename required!";

                String fullCode = "#include \"api.h\"\n#include <string>\n#include <vector>\nusing namespace std;\n" + code;
                File src = new File("plugins", "temp_" + filename + ".cpp");
                try (PrintWriter w = new PrintWriter(src, StandardCharsets.UTF_8)) { w.println(fullCode); }

                String ext = IS_WIN ? ".dll" : (IS_MAC ? ".dylib" : ".so");
                File out = new File("plugins", filename + ext);

                List<String> cmd = new ArrayList<>(Arrays.asList("g++", "-shared", "-o", out.getAbsolutePath(), src.getAbsolutePath(), "-I."));
                if (!IS_WIN) cmd.add("-fPIC");
                if (IS_MAC) { cmd.add("-undefined"); cmd.add("dynamic_lookup"); }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.directory(new File("."));
                Process p = pb.start();

                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line; while((line = br.readLine())!=null) sb.append(line).append("\n");

                if (p.waitFor() == 0) {
                    // Загружаем сразу, чтобы узнать имя команды
                    PluginInterface lib = Native.load(out.getAbsolutePath(), PluginInterface.class);
                    LoadedPlugin lp = new LoadedPlugin(lib);
                    plugins.put(lp.name, lp);
                    return "Success! Registered command: #" + lp.name + "\nDesc: " + lp.description;
                } else {
                    return "Error:\n" + sb;
                }
            } catch (Exception e) { return "Sys Error: " + e.getMessage(); }
        }
    }
}