package me.project.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.project.ChatServer;
import me.project.LoadedPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class DeleteHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
        if ("POST".equals(t.getRequestMethod())) {
            String query = readBody(t);
            String cmdName = query.split("=")[1];

            if (ChatServer.plugins.containsKey(cmdName)) {
                LoadedPlugin p = ChatServer.plugins.remove(cmdName);
                File f = new File("plugins", p.filename);

                String status;
                if (f.delete()) {
                    status = "Удалено.";
                } else {
                    File trash = new File("plugins", p.filename + ".trash");
                    f.renameTo(trash);
                    status = "Выгружено (удаление после перезапуска).";
                }

                ChatServer.broadcast("🗑️ Плагин #" + cmdName + " удален.", "System", true);
                sendResponse(t, status);
            } else {
                sendResponse(t, "Не найдено.");
            }
        }
    }

    // ... копии методов readBody и sendResponse (можно вынести в утилиты, но оставим тут)
    private String readBody(HttpExchange t) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line; while ((line = br.readLine()) != null) body.append(line);
            return body.toString();
        }
    }
    private void sendResponse(HttpExchange t, String resp) throws IOException {
        byte[] b = resp.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(200, b.length);
        t.getResponseBody().write(b);
        t.close();
    }
}