package com.letslearnenglish.minecraftplugin.util;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Version Adapter
 *
 * Handles API differences between different Minecraft versions.
 * Isolates version-specific code to ensure the plugin runs correctly
 * across multiple server versions.
 *
 * Supported version range: 1.18.x - 1.21.x
 */
public class VersionAdapter {

    private final LetsLearnEnglish plugin;
    private final String serverVersion;
    private final int majorVersion;
    private final int minorVersion;

    public VersionAdapter(LetsLearnEnglish plugin) {
        this.plugin = plugin;
        this.serverVersion = plugin.getServer().getMinecraftVersion();

        int parsedMajor = 20;
        int parsedMinor = 0;
        try {
            String[] parts = serverVersion.split("\\.");
            parsedMajor = parts.length > 1 ? Integer.parseInt(parts[1]) : 20;
            parsedMinor = parts.length > 2 ? Integer.parseInt(parts[2].replaceAll("[^0-9].*$", "")) : 0;
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Failed to parse server version: " + serverVersion
                    + ". Using defaults (major=20, minor=0).");
        }
        this.majorVersion = parsedMajor;
        this.minorVersion = parsedMinor;
    }

    /**
     * Get a cross-version compatible Material.
     * Handles name changes after the 1.13+ flattening.
     */
    public Material getMaterial(String modernName, String legacyName) {
        try {
            return Material.valueOf(modernName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Material not found: " + modernName
                    + ", falling back to: " + legacyName);
            return Material.valueOf(legacyName);
        }
    }

    /**
     * Get a cross-version compatible Sound.
     */
    public Sound getSound(String modernName, String legacyName) {
        try {
            return Sound.valueOf(modernName);
        } catch (IllegalArgumentException e) {
            return Sound.valueOf(legacyName);
        }
    }

    /**
     * Play a sound to a player (cross-version compatible).
     */
    public void playSound(Player player, Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /**
     * Get the server major version number.
     */
    public int getMajorVersion() {
        return majorVersion;
    }

    /**
     * Get the server minor version number.
     */
    public int getMinorVersion() {
        return minorVersion;
    }

    /**
     * Get the full version string.
     */
    public String getServerVersion() {
        return serverVersion;
    }

    /**
     * Check if the current server version is supported.
     */
    public boolean isVersionSupported() {
        return majorVersion >= 18 && majorVersion <= 21;
    }
}