package me.project;

import com.sun.jna.Native;
import com.sun.net.httpserver.HttpServer;
import me.project.http.*;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    public static final int PORT = 8888;
    public static final int HTTP_PORT = 8081;

    public static final String OS = System.getProperty("os.name").toLowerCase();
    public static final boolean IS_WIN = OS.contains("win");
    public static final boolean IS_MAC = OS.contains("mac");
    public static final String LIB_EXT = IS_WIN ? ".dll" : (IS_MAC ? ".dylib" : ".so");

    public static final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();
    public static final Set<ClientHandler> clients = new CopyOnWriteArraySet<>();
    public static final Map<String, String> ipHistory = new ConcurrentHashMap<>();
    public static final Map<String, List<String>> offlineMessages = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        System.setProperty("jna.encoding", "UTF-8");
        File pluginDir = new File("plugins");
        if (!pluginDir.exists()) pluginDir.mkdirs();

        File[] junk = pluginDir.listFiles((dir, name) ->
                name.endsWith(".trash") || name.contains("loaded_copy_") || (name.endsWith(".cpp") && name.startsWith("temp_"))
        );
        if (junk != null) for (File f : junk) f.delete();

        System.out.println("Scanning for plugins...");
        File[] files = pluginDir.listFiles((dir, name) -> name.endsWith(LIB_EXT) && !name.contains("loaded_copy_"));
        if (files != null) {
            for (File f : files) loadPluginSafe(f);
        }

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
        httpServer.createContext("/", new FrontendHandler());
        httpServer.createContext("/compile", new CompileHandler());
        httpServer.createContext("/list", new ListHandler());
        httpServer.createContext("/manage", new ManageHandler());
        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("HTTP Interface: http://localhost:" + HTTP_PORT);

        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Chat Server started on port " + PORT);

        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            ClientHandler client = new ClientHandler(serverSocket.accept());
            clients.add(client);
            pool.execute(client);
        }
    }

    // --- ИЗМЕНЕНИЕ ЗДЕСЬ: Возвращаем LoadedPlugin ---
    public static LoadedPlugin loadPluginSafe(File originalFile) {
        try {
            String tempName = "loaded_copy_" + System.currentTimeMillis() + "_" + originalFile.getName();
            File tempFile = new File(originalFile.getParentFile(), tempName);
            Files.copy(originalFile.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            PluginInterface lib = Native.load(tempFile.getAbsolutePath(), PluginInterface.class);
            LoadedPlugin plugin = new LoadedPlugin(lib, originalFile.getName(), tempFile);

            plugins.put(plugin.name, plugin);
            System.out.println(" [+] Loaded #" + plugin.name);

            tempFile.deleteOnExit();

            return plugin; // <-- ВОЗВРАЩАЕМ ОБЪЕКТ
        } catch (Throwable e) {
            System.err.println(" [-] Error loading " + originalFile.getName() + ": " + e.getMessage());
            return null; // <-- Возвращаем null при ошибке
        }
    }

    public static void unloadPlugin(String cmdName) {
        LoadedPlugin p = plugins.remove(cmdName);
        if (p != null) {
            if (p.tempFile != null && p.tempFile.exists()) {
                if (!p.tempFile.delete()) {
                    File trash = new File(p.tempFile.getParent(), p.tempFile.getName() + ".trash");
                    p.tempFile.renameTo(trash);
                    trash.deleteOnExit();
                }
            }
            broadcast("🔌 Плагин #" + cmdName + " выключен.", "System", true);
        }
    }

    public static void broadcast(String msg, String senderName, boolean isSystem) {
        String finalMsg = isSystem ? "\u001B[32m[SYSTEM] " + msg + "\u001B[0m" : msg;
        for (ClientHandler client : clients) {
            if (!isSystem && (client.blacklist.contains(senderName))) continue;
            if (!isSystem && client.favorites.contains(senderName)) { client.sendMessage("\u001B[33m⭐ " + msg + "\u001B[0m"); continue; }
            client.sendMessage(finalMsg);
        }
    }

    public static void sendPrivate(ClientHandler sender, String targetName, String msg) {
        String formattedMsg = "\u001B[35m(Private) " + sender.username + ": " + msg + "\u001B[0m";
        boolean online = false;
        for (ClientHandler client : clients) {
            if (client.username.equals(targetName)) {
                client.sendMessage(formattedMsg);
                online = true;
                break;
            }
        }
        if (!online) {
            offlineMessages.putIfAbsent(targetName, new ArrayList<>());
            List<String> box = offlineMessages.get(targetName);
            synchronized (box) {
                if (box.size() >= 10) sender.sendMessage("❌ Mailbox full.");
                else {
                    box.add("\u001B[35m(Offline) " + sender.username + ": " + msg + "\u001B[0m");
                    sender.sendMessage("💤 Saved offline.");
                }
            }
        }
    }
}