package me.project.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.project.ChatServer;
import me.project.LoadedPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
/*
Этот файл — информатор для админ-панели. Он отвечает за то, чтобы браузер знал, какие плагины есть на сервере и в каком они состоянии.
Его работа в 4 шагах:
1. Сканирует диск: Заходит в папку plugins и ищет все файлы библиотек (.dll или .so).
2. Сверяет с памятью: Для каждого файла проверяет, загружен ли он прямо сейчас в ChatServer (работает ли он).
3. Определяет статус:
    Active: Если плагин загружен, берет его имя и описание из памяти.
    Inactive: Если файл просто лежит на диске, но не загружен, помечает его как "выключенный".
4. Отдает JSON: Формирует список в формате JSON и отправляет его браузеру, чтобы тот нарисовал таблицу.
 */



public class ListHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
        StringBuilder json = new StringBuilder("[");

        File pluginDir = new File("plugins");
        File[] files = pluginDir.listFiles((dir, name) ->
                name.endsWith(ChatServer.LIB_EXT) && !name.contains("loaded_copy_")
        );

        if (files != null) {
            int i = 0;
            for (File f : files) {
                if (i > 0) json.append(",");

                LoadedPlugin activePlugin = null;
                for (LoadedPlugin p : ChatServer.plugins.values()) {
                    if (p.filename.equals(f.getName())) {
                        activePlugin = p;
                        break;
                    }
                }

                String name, desc, status, cmdName;

                if (activePlugin != null) {
                    // 1. Плагин АКТИВЕН -> берем из памяти
                    name = activePlugin.name;
                    desc = activePlugin.description;
                    status = "active";
                    cmdName = activePlugin.name;
                } else {
                    // 2. Плагин ВЫКЛЮЧЕН -> Пытаемся прочитать .txt файл
                    status = "inactive";

                    File meta = new File(pluginDir, f.getName() + ".txt");
                    if (meta.exists()) {
                        try {
                            List<String> lines = Files.readAllLines(meta.toPath(), StandardCharsets.UTF_8);
                            name = lines.size() > 0 ? lines.get(0) : "Unknown";
                            desc = lines.size() > 1 ? lines.get(1) : "Нет описания";
                            cmdName = name; // Мы знаем имя из файла!
                        } catch (Exception e) {
                            name = "Error reading meta";
                            desc = e.getMessage();
                            cmdName = "";
                        }
                    } else {
                        // Если .txt нет (старые плагины)
                        name = "Unknown (Unloaded)";
                        desc = "Включите плагин, чтобы обновить инфо";
                        cmdName = "";
                    }
                }

                json.append(String.format(
                        "{\"filename\":\"%s\", \"name\":\"%s\", \"desc\":\"%s\", \"status\":\"%s\", \"cmd\":\"%s\"}",
                        escape(f.getName()), escape(name), escape(desc), status, escape(cmdName)
                ));
                i++;
            }
        }

        json.append("]");
        t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        byte[] b = json.toString().getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, b.length);
        try (OutputStream os = t.getResponseBody()) { os.write(b); }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}