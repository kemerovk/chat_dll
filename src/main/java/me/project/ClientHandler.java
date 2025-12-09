package me.project;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ClientHandler implements Runnable {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    public String username;
    public String ip; // Храним IP адрес

    // Личные списки
    public Set<String> blacklist = new HashSet<>();
    public Set<String> favorites = new HashSet<>();

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.ip = socket.getInetAddress().getHostAddress(); // Получаем IP
    }

    public void sendMessage(String msg) {
        if (out != null) out.println(msg);
    }

    private void sendHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001B[36m=== HELP ===\u001B[0m\n");
        sb.append("@user msg - Личное сообщение (или оффлайн)\n");
        sb.append("#mass msg - Массовое личное сообщение всем\n");
        sb.append("#block user - В черный список\n");
        sb.append("#fav user   - Любимый автор (подсветка)\n");

        if (!ChatServer.plugins.isEmpty()) {
            sb.append("\u001B[36m--- Plugins ---\u001B[0m\n");
            for (LoadedPlugin p : ChatServer.plugins.values()) {
                sb.append("#").append(p.name).append(" -> ").append(p.description).append("\n");
            }
        }
        sendMessage(sb.toString());
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            // --- ЛОГИКА ВХОДА С ПАМЯТЬЮ IP ---
            String savedName = ChatServer.ipHistory.get(ip);
            String prompt = "Enter name";
            if (savedName != null) {
                prompt += " (Press ENTER to use '" + savedName + "')";
            }
            out.println(prompt + ":");

            String inputName = in.readLine();
            if (inputName == null) return;

            // Если нажали Enter и есть сохраненный ник - берем его
            if (inputName.trim().isEmpty() && savedName != null) {
                username = savedName;
            } else {
                username = inputName.trim();
                if (username.isEmpty()) username = "User_" + (int)(Math.random()*1000);
            }

            // Запоминаем IP -> Ник
            ChatServer.ipHistory.put(ip, username);
            // ---------------------------------

            ChatServer.broadcast(username + " joined.", "Server", true);
            sendHelp();

            // --- ПРОВЕРКА ОФФЛАЙН ПОЧТЫ ---
            if (ChatServer.offlineMessages.containsKey(username)) {
                List<String> mail = ChatServer.offlineMessages.remove(username); // Забираем и удаляем
                if (mail != null && !mail.isEmpty()) {
                    sendMessage("\u001B[36m📬 У вас " + mail.size() + " новых оффлайн-сообщений:\u001B[0m");
                    for (String m : mail) {
                        sendMessage(m);
                    }
                }
            }
            // -----------------------------

            String message;
            while ((message = in.readLine()) != null) {
                if (!message.isEmpty()) processMessage(message);
            }
        } catch (IOException e) {
        } finally {
            try { socket.close(); } catch (Exception e) {}
            ChatServer.clients.remove(this);
            if (username != null) ChatServer.broadcast(username + " left.", "Server", true);
        }
    }

    private void processMessage(String msg) {
        if (msg.startsWith("@")) {
            // Личное сообщение (или оффлайн)
            int sp = msg.indexOf(' ');
            if (sp != -1) {
                String target = msg.substring(1, sp);
                String text = msg.substring(sp + 1);
                ChatServer.sendPrivate(this, target, text);
            } else {
                sendMessage("Usage: @user message");
            }

        } else if (msg.startsWith("#")) {
            String[] parts = msg.split(" ", 2);
            String cmd = parts[0].substring(1);
            String arg = parts.length > 1 ? parts[1].trim() : "";

            switch (cmd) {
                case "help":
                    sendHelp();
                    break;
                case "block": // Черный список
                    blacklist.add(arg);
                    sendMessage("🚫 Вы заблокировали сообщения от " + arg);
                    break;
                case "fav":   // Любимый автор
                    favorites.add(arg);
                    sendMessage("⭐ Пользователь " + arg + " добавлен в избранное");
                    break;
                case "mass":  // Массовое ЛС
                    if (arg.isEmpty()) {
                        sendMessage("Usage: #mass text");
                    } else {
                        // Формируем сообщение, которое выглядит как личное
                        String fakePrivate = "\u001B[35m(Private) " + username + ": " + arg + "\u001B[0m";
                        for (ClientHandler client : ChatServer.clients) {
                            if (!client.blacklist.contains(username)) { // Уважаем чужой блок
                                client.sendMessage(fakePrivate);
                            }
                        }
                        sendMessage("📢 Массовое сообщение отправлено.");
                    }
                    break;
                default:
                    // Проверка плагинов
                    if (ChatServer.plugins.containsKey(cmd)) {
                        try {
                            String res = ChatServer.plugins.get(cmd).lib.handle_message(username, arg);
                            ChatServer.broadcast(res, "System", true);
                        } catch (Exception e) {
                            sendMessage("Plugin Error: " + e.getMessage());
                        }
                    } else {
                        sendMessage("Unknown command.");
                    }
            }
        } else {
            // Обычное сообщение в чат
            ChatServer.broadcast(username + ": " + msg, username, false);
        }
    }
}