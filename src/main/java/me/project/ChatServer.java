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
    private static final String LIB_EXT = IS_WIN ? ".dll" : (IS_MAC ? ".dylib" : ".so");

    static class LoadedPlugin {
        PluginInterface lib;
        String name;
        String description;
        String filename;

        public LoadedPlugin(PluginInterface lib, String filename) {
            this.lib = lib;
            this.filename = filename;
            try { this.name = lib.get_name(); } catch (Error e) { this.name = "unknown"; }
            try { this.description = lib.get_description(); } catch (Error e) { this.description = "No description"; }
        }
    }

    private static final Set<ClientHandler> clients = new CopyOnWriteArraySet<>();
    private static final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();
    private static final Map<String, OfflineData> ipDb = new ConcurrentHashMap<>();

    static class OfflineData {
        String lastUsername;
        List<String> pendingMessages = new ArrayList<>();
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("jna.encoding", "UTF-8");
        File pluginDir = new File("plugins");
        if (!pluginDir.exists()) pluginDir.mkdirs();

        // --- ИСПРАВЛЕНИЕ 1: Очистка мусора при старте ---
        // Удаляем файлы .trash, оставшиеся с прошлого запуска
        File[] trashFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".trash"));
        if (trashFiles != null) {
            for (File f : trashFiles) {
                if (f.delete()) {
                    System.out.println(" [Cleanup] Deleted old file: " + f.getName());
                } else {
                    System.err.println(" [Cleanup] Could not delete: " + f.getName());
                }
            }
        }
        // -----------------------------------------------

        System.out.println("Scanning for plugins...");
        File[] files = pluginDir.listFiles((dir, name) -> name.endsWith(LIB_EXT));
        if (files != null) {
            for (File f : files) {
                try {
                    PluginInterface lib = Native.load(f.getAbsolutePath(), PluginInterface.class);
                    LoadedPlugin plugin = new LoadedPlugin(lib, f.getName());
                    plugins.put(plugin.name, plugin);
                    System.out.println(" [+] Loaded #" + plugin.name);
                } catch (Throwable e) {
                    System.err.println(" [-] Error loading " + f.getName());
                }
            }
        }

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", new FrontendHandler());
        httpServer.createContext("/compile", new CompileHandler());
        httpServer.createContext("/list", new ListHandler());
        httpServer.createContext("/delete", new DeleteHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("HTTP Web Interface on port " + HTTP_PORT);

        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Chat Server on port " + PORT);

        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            clients.add(new ClientHandler(serverSocket.accept()));
            pool.execute(clients.iterator().next());
        }
    }

    public static void broadcast(String msg, String senderName, boolean isSystem) {
        String finalMsg;
        if (isSystem) {
            finalMsg = "\u001B[32m[SYSTEM] " + msg + "\u001B[0m";
        } else {
            finalMsg = msg;
        }

        for (ClientHandler client : clients) {
            if (!isSystem) {
                if (client.blacklist.contains(senderName)) continue;
                if (client.favorites.contains(senderName)) {
                    finalMsg = "\u001B[33m[FAV] " + msg + "\u001B[0m";
                }
            }
            client.sendMessage(finalMsg);
        }
    }

    public static void sendPrivate(ClientHandler sender, String targetName, String msg, boolean isSystem) {
        String formattedMsg = isSystem ? msg : "\u001B[35m(Private) " + sender.username + ": " + msg + "\u001B[0m";
        boolean found = false;
        for (ClientHandler client : clients) {
            if (client.username.equals(targetName)) {
                client.sendMessage(formattedMsg); found = true; break;
            }
        }
        if (!found && !isSystem) sender.sendMessage("User offline/not found.");
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
            sb.append("@user msg, #block user, #fav user\n");
            if (!plugins.isEmpty()) {
                sb.append("\u001B[36m--- Plugins ---\u001B[0m\n");
                for (LoadedPlugin p : plugins.values()) {
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
                    if (!message.isEmpty()) processMessage(message);
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
                            String res = plugins.get(cmd).lib.handle_message(username, arg);
                            broadcast(res, "System", true);
                        } catch (Exception e) { sendMessage("Plugin Error: " + e.getMessage()); }
                    } else { sendMessage("Unknown command."); }
                }
            } else { broadcast(username + ": " + msg, username, false); }
        }
    }

    static class FrontendHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            byte[] bytes = Files.readAllBytes(new File("index.html").toPath());
            t.sendResponseHeaders(200, bytes.length);
            t.getResponseBody().write(bytes);
            t.close();
        }
    }

    static class ListHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            StringBuilder json = new StringBuilder("[");
            int i = 0;
            for (LoadedPlugin p : plugins.values()) {
                if (i > 0) json.append(",");
                String safeDesc = p.description.replace("\"", "\\\"");
                json.append(String.format("{\"name\":\"%s\", \"desc\":\"%s\", \"file\":\"%s\"}", p.name, safeDesc, p.filename));
                i++;
            }
            json.append("]");
            sendResponse(t, json.toString());
        }
    }

    // --- ИСПРАВЛЕНИЕ 2: Логика удаления ---
    static class DeleteHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                String query = readBody(t);
                String cmdName = query.split("=")[1];

                if (plugins.containsKey(cmdName)) {
                    LoadedPlugin p = plugins.remove(cmdName);
                    File f = new File("plugins", p.filename);

                    String status;
                    // Пытаемся удалить
                    if (f.delete()) {
                        status = "Плагин #" + cmdName + " и файл удалены.";
                    } else {
                        // Если файл заблокирован (Windows), переименовываем его в .trash
                        File trash = new File("plugins", p.filename + ".trash");
                        if (f.renameTo(trash)) {
                            status = "Плагин #" + cmdName + " выгружен. Файл удалится при перезапуске сервера.";
                        } else {
                            status = "Внимание: Плагин отключен, но файл удалить не удалось (Locked).";
                        }
                    }

                    broadcast("🗑️ Плагин #" + cmdName + " удален из системы.", "System", true);
                    sendResponse(t, status);
                } else {
                    sendResponse(t, "Ошибка: Плагин не найден.");
                }
            }
        }
    }

    static class CompileHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                Map<String, String> params = parse(readBody(t));
                String filename = params.get("filename");
                String code = params.get("code");
                String result = compile(filename, code);
                sendResponse(t, result);
            }
        }

        private String compile(String filename, String code) {
            try {
                String fullCode = "#include \"api.h\"\n#include <string>\n#include <vector>\nusing namespace std;\n" + code;
                File src = new File("plugins", "temp_" + filename + ".cpp");
                try (PrintWriter w = new PrintWriter(src, StandardCharsets.UTF_8)) { w.println(fullCode); }

                File out = new File("plugins", filename + LIB_EXT);
                List<String> cmd = new ArrayList<>(Arrays.asList("g++", "-shared", "-o", out.getAbsolutePath(), src.getAbsolutePath(), "-I."));
                if (!IS_WIN) cmd.add("-fPIC");
                if (IS_MAC) { cmd.add("-undefined"); cmd.add("dynamic_lookup"); }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true); pb.directory(new File("."));
                Process p = pb.start();

                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line; while((line = br.readLine())!=null) sb.append(line).append("\n");

                if (p.waitFor() == 0) {
                    PluginInterface lib = Native.load(out.getAbsolutePath(), PluginInterface.class);
                    LoadedPlugin lp = new LoadedPlugin(lib, out.getName());
                    boolean updated = plugins.containsKey(lp.name);
                    plugins.put(lp.name, lp);
                    String action = updated ? "обновлен" : "подключен";
                    broadcast("🔌 Новый плагин #" + lp.name + " успешно " + action + "!", "System", true);
                    return "Success";
                } else { return "Error:\n" + sb; }
            } catch (Exception e) { return "Sys Error: " + e.getMessage(); }
        }
    }

    private static String readBody(HttpExchange t) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) body.append(line);
        }
        return body.toString();
    }
    private static void sendResponse(HttpExchange t, String resp) throws IOException {
        byte[] b = resp.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.close();
    }
    private static Map<String, String> parse(String body) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        for (String p : body.split("&")) {
            int i = p.indexOf("=");
            if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), "UTF-8"), URLDecoder.decode(p.substring(i+1), "UTF-8"));
        }
        return m;
    }
}