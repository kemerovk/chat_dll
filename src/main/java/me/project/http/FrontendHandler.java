package me.project.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

public class FrontendHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
        File file = new File("index.html");
        byte[] bytes;
        if(file.exists()) bytes = Files.readAllBytes(file.toPath());
        else bytes = "<h1>Error: index.html missing</h1>".getBytes();

        t.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = t.getResponseBody()) { os.write(bytes); }
    }
}