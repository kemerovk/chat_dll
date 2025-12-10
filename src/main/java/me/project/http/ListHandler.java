package me.project.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.project.ChatServer;
import me.project.LoadedPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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

                // Ищем, загружен ли этот файл как активный плагин
                LoadedPlugin activePlugin = null;
                for (LoadedPlugin p : ChatServer.plugins.values()) {
                    if (p.filename.equals(f.getName())) {
                        activePlugin = p;
                        break;
                    }
                }

                String name, desc, status, cmdName;

                if (activePlugin != null) {
                    // АКТИВЕН
                    name = activePlugin.name;
                    desc = activePlugin.description;
                    status = "active";
                    cmdName = activePlugin.name; // ID для команд
                } else {
                    // ВЫГРУЖЕН (файл есть, но в памяти нет)
                    name = "Unknown (Unloaded)";
                    desc = "Плагин выключен. Нажмите 'Загрузить', чтобы узнать инфо.";
                    status = "inactive";
                    cmdName = "";
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