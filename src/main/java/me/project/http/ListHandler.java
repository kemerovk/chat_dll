package me.project.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.project.ChatServer;
import me.project.LoadedPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ListHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
        StringBuilder json = new StringBuilder("[");
        int i = 0;

        for (LoadedPlugin p : ChatServer.plugins.values()) {
            if (i > 0) json.append(",");
            String safeName = escapeJson(p.name);
            String safeDesc = escapeJson(p.description);
            String safeFile = escapeJson(p.filename);

            json.append(String.format(
                    "{\"name\":\"%s\", \"desc\":\"%s\", \"file\":\"%s\"}",
                    safeName, safeDesc, safeFile
            ));
            i++;
        }
        json.append("]");

        byte[] b = json.toString().getBytes(StandardCharsets.UTF_8);
        t.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        t.sendResponseHeaders(200, b.length);
        try (OutputStream os = t.getResponseBody()) { os.write(b); }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}