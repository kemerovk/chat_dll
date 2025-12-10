package me.project;

import java.io.File; // <-- Обязательно этот импорт

public class LoadedPlugin {
    public PluginInterface lib;
    public String name;
    public String description;
    public String filename;

    // 👇 ВОТ ЭТОГО ПОЛЯ НЕ ХВАТАЛО
    public File tempFile;

    // Обновленный конструктор принимает 3 аргумента
    public LoadedPlugin(PluginInterface lib, String filename, File tempFile) {
        this.lib = lib;
        this.filename = filename;
        this.tempFile = tempFile; // Сохраняем ссылку на временный файл

        // Безопасное получение данных
        try { this.name = lib.get_name(); } catch (Throwable e) { this.name = "unknown"; }
        try { this.description = lib.get_description(); } catch (Throwable e) { this.description = "No description"; }

        if (this.name == null) this.name = "null";
        if (this.description == null) this.description = "-";
    }
}