package me.project;

public class LoadedPlugin {
    public PluginInterface lib;
    public String name;
    public String description;
    public String filename;

    public LoadedPlugin(PluginInterface lib, String filename) {
        this.lib = lib;
        this.filename = filename;
        try { this.name = lib.get_name(); } catch (Throwable e) { this.name = "unknown"; }
        try { this.description = lib.get_description(); } catch (Throwable e) { this.description = "No description"; }

        if (this.name == null) this.name = "null";
        if (this.description == null) this.description = "-";
    }
}