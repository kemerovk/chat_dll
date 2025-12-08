package me.project;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class ClientHandler implements Runnable {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    public String username;
    public Set<String> blacklist = new HashSet<>();

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    public void sendMessage(String msg) {
        if (out != null) out.println(msg);
    }

    private void sendHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001B[36m=== HELP ===\u001B[0m\n@user msg, #block user\n");
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

            out.println("Enter name:");
            username = in.readLine();
            if (username == null) return;

            ChatServer.broadcast(username + " joined.", "Server", true);
            sendHelp();

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
            int sp = msg.indexOf(' ');
            if (sp != -1) ChatServer.sendPrivate(this, msg.substring(1, sp), msg.substring(sp + 1));
        } else if (msg.startsWith("#")) {
            String[] parts = msg.split(" ", 2);
            String cmd = parts[0].substring(1);
            String arg = parts.length > 1 ? parts[1].trim() : "";

            if (cmd.equals("help")) sendHelp();
            else if (cmd.equals("block")) { blacklist.add(arg); sendMessage("Blocked " + arg); }
            else {
                if (ChatServer.plugins.containsKey(cmd)) {
                    try {
                        String res = ChatServer.plugins.get(cmd).lib.handle_message(username, arg);
                        ChatServer.broadcast(res, "System", true);
                    } catch (Exception e) { sendMessage("Plugin Error: " + e.getMessage()); }
                } else { sendMessage("Unknown command."); }
            }
        } else {
            ChatServer.broadcast(username + ": " + msg, username, false);
        }
    }
}