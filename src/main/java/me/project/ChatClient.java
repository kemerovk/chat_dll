package me.project;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ChatClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 8888;

    public static void main(String[] args) {
        try (Socket socket = new Socket(SERVER_IP, SERVER_PORT)) {
            System.out.println("[Client] Connected to " + SERVER_IP + ":" + SERVER_PORT);

            Thread t = new Thread(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String msg;
                    while ((msg = in.readLine()) != null) System.out.println(msg);
                } catch (IOException e) {
                    System.out.println("[System] Connection lost.");
                    System.exit(0);
                }
            });
            t.start();

            PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8.name());
            while (scanner.hasNextLine()) {
                String input = scanner.nextLine();
                out.println(input);
            }
        } catch (IOException e) {
            System.err.println("Connection failed: " + e.getMessage());
        }
    }
}