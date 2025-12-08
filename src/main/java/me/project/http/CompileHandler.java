package me.project.http;

import com.sun.jna.Native;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import me.project.ChatServer;
import me.project.LoadedPlugin;
import me.project.PluginInterface;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CompileHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
        if ("POST".equals(t.getRequestMethod())) {
            Map<String, String> params = parse(readBody(t));
            // Запускаем компиляцию
            String result = compile(params.get("filename"), params.get("code"));
            sendResponse(t, result);
        }
    }

    private String compile(String filename, String code) {
        // Создаем объект файла заранее, чтобы он был доступен в finally
        File src = new File("plugins", "temp_" + filename + ".cpp");

        try {
            // 1. Записываем код в файл
            String fullCode = "#include \"api.h\"\n#include <string>\n#include <vector>\nusing namespace std;\n" + code;
            try (PrintWriter w = new PrintWriter(src, StandardCharsets.UTF_8)) {
                w.println(fullCode);
            }

            // 2. Подготовка команды компиляции
            File out = new File("plugins", filename + ChatServer.LIB_EXT);

            List<String> cmd = new ArrayList<>(Arrays.asList(
                    "g++", "-shared", "-o", out.getAbsolutePath(), src.getAbsolutePath(), "-I."
            ));

            // Настройки для разных ОС
            if (!ChatServer.IS_WIN) cmd.add("-fPIC");
            if (ChatServer.IS_MAC) { cmd.add("-undefined"); cmd.add("dynamic_lookup"); }

            // 3. Запуск процесса
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(new File("."));
            Process p = pb.start();

            // Читаем вывод компилятора (ошибки)
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = br.readLine()) != null) sb.append(line).append("\n");

            // 4. Проверка результата
            if (p.waitFor() == 0) {
                // Успех! Загружаем плагин
                PluginInterface lib = Native.load(out.getAbsolutePath(), PluginInterface.class);
                LoadedPlugin lp = new LoadedPlugin(lib, out.getName());
                ChatServer.plugins.put(lp.name, lp);
                ChatServer.broadcast("🔌 Плагин #" + lp.name + " успешно загружен!", "System", true);
                return "Success";
            } else {
                // Ошибка компиляции
                return "Compile Error:\n" + sb.toString();
            }

        } catch (Exception e) {
            return "System Error: " + e.getMessage();
        } finally {
            // --- ВАЖНО: ЭТОТ БЛОК ВЫПОЛНЯЕТСЯ ВСЕГДА ---
            // Удаляем .cpp файл, даже если была ошибка
            if (src.exists()) {
                src.delete();
            }
        }
    }

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

    private Map<String, String> parse(String body) throws UnsupportedEncodingException {
        Map<String, String> m = new HashMap<>();
        for (String p : body.split("&")) {
            int i = p.indexOf("=");
            if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), "UTF-8"), URLDecoder.decode(p.substring(i+1), "UTF-8"));
        }
        return m;
    }
}