package org.bukkit.plugin.java;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

/**
 * Compile-only stub cho Bukkit/Paper JavaPlugin (bị loại khỏi JAR khi đóng gói).
 * Khi chạy trong Paper, JVM dùng JavaPlugin thật của máy chủ Paper.
 */
public abstract class JavaPlugin implements Plugin, CommandExecutor, TabCompleter {
    public void onLoad() {}
    public void onEnable() {}
    public void onDisable() {}

    @Override public File getDataFolder() { return null; }
    @Override public Logger getLogger() { return null; }
    @Override public Server getServer() { return null; }
    @Override public PluginDescriptionFile getDescription() { return null; }
    @Override public boolean isEnabled() { return true; }
    public PluginCommand getCommand(String name) { return null; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return null;
    }
}
