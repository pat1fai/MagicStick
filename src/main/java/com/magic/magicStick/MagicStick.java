package com.magic.magicStick;

import org.bukkit.plugin.java.JavaPlugin;

public final class MagicStick extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("Привет! Гондон!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Плагин выключен.");
    }
}
