package org.bukkit.plugin;

import org.bukkit.Server;
import java.io.File;
import java.util.logging.Logger;

public interface Plugin {
    File getDataFolder();
    Logger getLogger();
    Server getServer();
    PluginDescriptionFile getDescription();
    boolean isEnabled();
}
