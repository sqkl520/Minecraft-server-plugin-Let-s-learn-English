package com.letslearnenglish.minecraftplugin.listener;

import com.letslearnenglish.minecraftplugin.LetsLearnEnglish;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.regex.Pattern;

/**
 * NPC Interaction Listener
 *
 * Handles player interactions with NPCs for dialogue practice.
 * Supports both Citizens plugin NPCs and custom entity-based NPCs.
 */
public class NPCInteractionListener implements Listener {

    private static final Pattern COLOR_CODE = Pattern.compile("&[0-9a-fk-or]");

    private final LetsLearnEnglish plugin;

    public NPCInteractionListener(LetsLearnEnglish plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        String npcName = event.getRightClicked().getName();
        if (npcName == null) {
            return;
        }

        String npcPrefix = plugin.getConfigManager().getMainConfig()
                .getString("dialogue-system.npc.name-prefix", "&b[English] &f");
        if (npcPrefix == null) {
            return;
        }

        String strippedPrefix = COLOR_CODE.matcher(npcPrefix).replaceAll("").trim();
        String strippedNpcName = COLOR_CODE.matcher(npcName).replaceAll("");
        if (!strippedNpcName.contains(strippedPrefix)) {
            return;
        }

        event.setCancelled(true);
    }
}