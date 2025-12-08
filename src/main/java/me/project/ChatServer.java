package me.project;

import com.sun.jna.Native;
import com.sun.net.httpserver.HttpServer;
import me.project.http.*; // Импорт обработчиков (CompileHandler, ListHandler и т.д.)

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

public class ChatServer {
    public static final int PORT = 8888;
    public static final int HTTP_PORT = 8081;

    // --- Определение Операционной Системы ---
    public static final String OS = System.getProperty("os.name").toLowerCase();
    public static final boolean IS_WIN = OS.contains("win");
    public static final boolean IS_MAC = OS.contains("mac");
    public static final String LIB_EXT = IS_WIN ? ".dll" : (IS_MAC ? ".dylib" : ".so");

    // --- Глобальные хранилища (Public Static) ---
    // Хранит загруженные плагины (доступна для ListHandler, DeleteHandler, CompileHandler)
    public static final Map<String, LoadedPlugin> plugins = new ConcurrentHashMap<>();

    // Хранит подключенных клиентов чата
    public static final Set<ClientHandler> clients = new CopyOnWriteArraySet<>();

    public static void main(String[] args) throws IOException {
        System.setProperty("jna.encoding", "UTF-8");

        // 1. Подготовка папки для плагинов
        File pluginDir = new File("plugins");
        if (!pluginDir.exists()) pluginDir.mkdirs();

        // Очистка мусора (.trash файлов) перед запуском
        File[] trashFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".trash"));
        if (trashFiles != null) {
            for (File f : trashFiles) f.delete();
        }

        // 👇 ДОБАВЬТЕ ЭТОТ БЛОК:
        // Очистка старых .cpp файлов, оставшихся от ошибок
        File[] tempCppFiles = pluginDir.listFiles((dir, name) -> name.endsWith(".cpp") && name.startsWith("temp_"));
        if (tempCppFiles != null) {
            for (File f : tempCppFiles) {
                f.delete();
                System.out.println(" [Cleanup] Deleted garbage file: " + f.getName());
            }
        }

        // 2. Загрузка уже существующих плагинов
        System.out.println("Scanning for plugins...");
        File[] files = pluginDir.listFiles((dir, name) -> name.endsWith(LIB_EXT));
        if (files != null) {
            for (File f : files) {
                try {
                    PluginInterface lib = Native.load(f.getAbsolutePath(), PluginInterface.class);
                    // Используем класс LoadedPlugin (он должен быть в отдельном файле me.project.LoadedPlugin)
                    LoadedPlugin plugin = new LoadedPlugin(lib, f.getName());
                    plugins.put(plugin.name, plugin);
                    System.out.println(" [+] Loaded #" + plugin.name);
                } catch (Throwable e) {
                    System.err.println(" [-] Error loading " + f.getName() + ": " + e.getMessage());
                }
            }
        }

        // 3. Запуск HTTP Web-интерфейса
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);

        // --- РЕГИСТРАЦИЯ ПУТЕЙ (Вот здесь была проблема 404) ---
        httpServer.createContext("/", new FrontendHandler());
        httpServer.createContext("/compile", new CompileHandler()); // <--- ОБЯЗАТЕЛЬНО!
        httpServer.createContext("/list", new ListHandler());
        httpServer.createContext("/delete", new DeleteHandler());
        // --------------------------------------------------------

        httpServer.setExecutor(null);
        httpServer.start();
        System.out.println("HTTP Web Interface started: http://localhost:" + HTTP_PORT);

        // 4. Запуск TCP Chat-сервера
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Chat Server started on port " + PORT);

        ExecutorService pool = Executors.newCachedThreadPool();
        while (true) {
            ClientHandler client = new ClientHandler(serverSocket.accept());
            clients.add(client);
            pool.execute(client);
        }
    }

    // Метод для рассылки сообщений всем
    public static void broadcast(String msg, String senderName, boolean isSystem) {
        String finalMsg = isSystem ? "\u001B[32m[SYSTEM] " + msg + "\u001B[0m" : msg;
        for (ClientHandler client : clients) {
            if (!isSystem && client.blacklist.contains(senderName)) continue;
            client.sendMessage(finalMsg);
        }
    }

    // Метод для личных сообщений
    public static void sendPrivate(ClientHandler sender, String targetName, String msg) {
        String formattedMsg = "\u001B[35m(Private) " + sender.username + ": " + msg + "\u001B[0m";
        boolean found = false;
        for (ClientHandler client : clients) {
            if (client.username.equals(targetName)) {
                client.sendMessage(formattedMsg);
                found = true;
                break;
            }
        }
        if (!found) sender.sendMessage("User offline/not found.");
    }
}