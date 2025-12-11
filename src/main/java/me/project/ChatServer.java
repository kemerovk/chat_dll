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

/*
1. Хранилище данных (Глобальная память)
В начале класса объявлены статические карты (Map) и списки. Это "общая память" сервера, к которой имеют доступ все потоки:
plugins: Здесь хранятся все активные C++ функции.
clients: Список всех людей, кто сейчас сидит в чате.
ipHistory: Запоминает, какой ник был у IP адреса (для "С возвращением, Alice").
offlineMessages: Почтовый ящик для тех, кого нет в сети.
2. Запуск (main)
Когда вы запускаете программу, происходит следующее:
Уборка мусора: Сервер удаляет старые временные файлы (.trash, loaded_copy_...), оставшиеся с прошлого раза. Это критично для Windows, чтобы не накапливать заблокированные файлы.
Загрузка старых плагинов: Он сканирует папку plugins. Если вы скомпилировали плагин вчера, сервер найдет его файл .dll/.so и сразу загрузит.
Запуск Веб-сервера (порт 8081): Подключает те самые обработчики (CompileHandler, ListHandler и т.д.), которые мы разбирали ранее.
Запуск Чат-сервера (порт 8888): Открывает порт для подключения клиентов.
Вечный цикл: Сервер бесконечно ждет новых подключений. Как только кто-то заходит, он создает для него отдельный поток (ClientHandler) и сразу возвращается к ожиданию следующих гостей.
3. Хитрая загрузка плагинов (loadPluginSafe)
Это самая важная часть для горячей замены кода.
Проблема: В Windows, если программа загрузила .dll, этот файл нельзя удалить или перезаписать. Это значит, вы не смогли бы перекомпилировать код без перезапуска сервера.
Решение: Этот метод копирует файл плагина во временный файл (loaded_copy_...) и загружает именно копию!
Итог: Оригинальный файл остается свободным. Компилятор может спокойно перезаписывать его, а сервер при следующей загрузке просто создаст новую копию.
4. Выключение плагина (unloadPlugin)
Удаляет плагин из памяти.
Пытается удалить временный файл-копию. Если Windows не дает это сделать (файл занят), он переименовывает его в .trash. Этот мусор будет удален при следующем запуске сервера (см. пункт 2).
5. Почта и Рассылка (broadcast и sendPrivate)
Broadcast: Отправляет сообщение всем. Тут же проверяет:
Не в черном ли списке отправитель?
Не является ли он "любимым автором" (тогда красит сообщение в золото/желтый)?
SendPrivate:
Ищет пользователя онлайн.
Если не нашел — кладет сообщение в offlineMessages (с лимитом 10 штук), чтобы пользователь прочитал его, когда зайдет.
 */




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