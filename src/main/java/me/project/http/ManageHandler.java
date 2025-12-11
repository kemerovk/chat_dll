package me.project.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.project.ChatServer;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;


/*
Этот файл — пульт управления плагинами. Он обрабатывает нажатия кнопок "Включить", "Выключить" и "Удалить" в таблице админ-панели.
Его работа в 3 сценариях:
1. Включить (load):
    Берет файл с диска (который был выключен).
    Загружает его в память сервера (loadPluginSafe).
    Пишет в чат: "🔌 Плагин загружен".
2. Выключить (unload):
    Убирает плагин из памяти сервера (unloadPlugin), чтобы команда перестала работать.
    Файл при этом остается на диске (статус меняется на inactive).
3. Удалить (delete):
    Если плагин работал — сначала выключает его.
    Затем физически удаляет файл (.dll или .so) с жесткого диска.
    Пишет в чат: "🗑️ Плагин удален насовсем".
 */


public class ManageHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
        if ("POST".equals(t.getRequestMethod())) {
            Map<String, String> params = parse(readBody(t));
            String action = params.get("action");   // load, unload, delete
            String filename = params.get("file");   // Имя файла
            String cmdName = params.get("cmd");     // Имя команды

            String response = "Error";
            File file = new File("plugins", filename);

            if ("load".equals(action)) {
                if (file.exists()) {
                    // ChatServer.loadPluginSafe сам отправит сообщение в чат
                    me.project.LoadedPlugin p = ChatServer.loadPluginSafe(file);
                    if (p != null) {
                        ChatServer.broadcast("🔌 Плагин #" + p.name + " загружен.", "System", true);
                        response = "Загружено успешно.";
                    } else {
                        response = "Ошибка загрузки.";
                    }
                } else {
                    response = "Файл не найден.";
                }
            }
            else if ("unload".equals(action)) {
                if (cmdName != null && !cmdName.isEmpty()) {
                    // ChatServer.unloadPlugin сам отправит сообщение "выключен"
                    ChatServer.unloadPlugin(cmdName);
                    response = "Плагин выключен.";
                } else {
                    response = "Ошибка: неизвестное имя команды.";
                }
            }
            else if ("delete".equals(action)) {
                // 1. Определяем красивое имя для чата
                // Если есть cmdName - используем его (например "#fence")
                // Если нет - используем имя файла (например "plugin_123.dll")
                String displayName = (cmdName != null && !cmdName.isEmpty()) ? "#" + cmdName : filename;

                // 2. Если плагин активен, сначала выгружаем его
                if (cmdName != null && !cmdName.isEmpty() && ChatServer.plugins.containsKey(cmdName)) {
                    // Используем "тихую" выгрузку или просто удаляем из мапы,
                    // чтобы не спамить лишним сообщением "выключен", если мы всё равно удаляем.
                    // Но проще вызвать стандартный метод:
                    ChatServer.unloadPlugin(cmdName);
                }

                // 3. Удаляем файл
                if (file.exists() && file.delete()) {

                    File meta = new File("plugins", filename + ".txt");
                    if (meta.exists()) meta.delete();
                    ChatServer.broadcast("🗑️ Плагин " + displayName + " удален насовсем.", "System", true);
                    response = "Файл удален окончательно.";
                } else {
                    response = "Ошибка удаления файла.";
                }
            }

            sendResponse(t, response);
        }
    }

    private String readBody(HttpExchange t) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line; while ((line = br.readLine()) != null) body.append(line);
            return body.toString();
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

    private void sendResponse(HttpExchange t, String resp) throws IOException {
        t.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        byte[] b = resp.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.close();
    }
}