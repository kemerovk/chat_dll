package me.project;
import com.sun.jna.Library;

public interface PluginInterface extends Library {
    String handle_message(String sender, String text);
    String get_name();
    String get_description();
}