package me.project;

import com.sun.jna.Native;
import com.sun.net.httpserver.HttpServer;
import me.project.http.*;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
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

    // --- НОВЫЕ ФУНКЦИИ ---
    // 1. Память IP: IP -> Последний Никнейм
    public static final Map<String, String> ipHistory = new ConcurrentHashMap<>();

    // 2. Оффлайн сообщения: Никнейм -> Список сообщений
    public static final Map<String, List<String>> offlineMessages = new ConcurrentHashMap<>();
    // ---------------------

    public static void main(String[] args) throws IOException {
        System.setProperty("jna.encoding", "UTF-8");
        File pluginDir = new File("plugins");
        if (!pluginDir.exists()) pluginDir.mkdirs();

        // Очистка .trash
        File[] trashFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".trash"));
        if (trashFiles != null) for (File f : trashFiles) f.delete();

        // Очистка .cpp
        File[] tempCppFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".cpp") && name.startsWith("temp_"));
        if (tempCppFiles != null) for (File f : tempCppFiles) f.delete();

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

    // Обновленный Broadcast с поддержкой Черного списка и Любимых авторов
    public static void broadcast(String msg, String senderName, boolean isSystem) {
        String finalMsg;
        if (isSystem) {
            finalMsg = "\u001B[32m[SYSTEM] " + msg + "\u001B[0m"; // Зеленый
        } else {
            finalMsg = msg;
        }

        for (ClientHandler client : clients) {
            if (!isSystem) {
                // Реализация черного списка
                if (client.blacklist.contains(senderName)) continue;

                // Подсветка любимого автора (Золотой цвет)
                if (client.favorites.contains(senderName)) {
                    client.sendMessage("\u001B[33m⭐ " + msg + "\u001B[0m");
                    continue;
                }
            }
            client.sendMessage(finalMsg);
        }
    }

    // Обновленная приватная отправка (теперь поддерживает оффлайн)
    public static void sendPrivate(ClientHandler sender, String targetName, String msg) {
        String formattedMsg = "\u001B[35m(Private) " + sender.username + ": " + msg + "\u001B[0m";
        boolean online = false;

        // 1. Пытаемся отправить онлайн
        for (ClientHandler client : clients) {
            if (client.username.equals(targetName)) {
                client.sendMessage(formattedMsg);
                online = true;
                break;
            }
        }

        // 2. Если пользователя нет - сохраняем в оффлайн (Отложенная отправка)
        if (!online) {
            offlineMessages.putIfAbsent(targetName, new ArrayList<>());
            List<String> userMailbox = offlineMessages.get(targetName);

            synchronized (userMailbox) {
                if (userMailbox.size() >= 10) {
                    sender.sendMessage("❌ Ошибка: Ящик пользователя " + targetName + " переполнен (макс 10).");
                } else {
                    userMailbox.add("\u001B[35m(Offline) " + sender.username + ": " + msg + "\u001B[0m");
                    sender.sendMessage("💤 Пользователь оффлайн. Сообщение сохранено (" + userMailbox.size() + "/10).");
                }
            }
        } else {
            sender.sendMessage("(Sent to " + targetName + ")");
        }
    }
}