package me.project;

import java.io.*;
import java.net.Socket;
import java.util.Scanner;

public class ChatClient {
    private static final String SERVER_IP = "127.0.0.1";
    private static final int SERVER_PORT = 8888;

    public static void main(String[] args) {
        try (Socket socket = new Socket(SERVER_IP, SERVER_PORT)) {
            System.out.println("Connected to server.");

            Thread t = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    String msg;
                    while ((msg = in.readLine()) != null) System.out.println(msg);
                } catch (IOException e) {
                    System.exit(0);
                }
            });
            t.start();

            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String input = scanner.nextLine();
                out.println(input);
            }
        } catch (IOException e) {
            System.out.println("Connection failed: " + e.getMessage());
        }
    }
}