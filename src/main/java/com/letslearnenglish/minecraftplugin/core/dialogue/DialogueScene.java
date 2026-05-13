package com.letslearnenglish.minecraftplugin.core.dialogue;

import com.letslearnenglish.minecraftplugin.core.dialogue.DialogueNode.DialogueOption;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/**
 * Dialogue Scene - represents a complete dialogue scenario.
 *
 * Loaded from YAML configuration, contains the full dialogue tree
 * with NPC lines and player response options.
 */
public class DialogueScene {

    private final String id;
    private final String name;
    private final String description;
    private final String difficulty;
    private final NPC npc;
    private final Map<String, DialogueNode> nodes;

    @SuppressWarnings("unchecked")
    public DialogueScene(FileConfiguration config) {
        this.id = config.getString("scene.id", "unknown");
        this.name = config.getString("scene.name", "Unknown Scene");
        this.description = config.getString("scene.description", "");
        this.difficulty = config.getString("scene.difficulty", "beginner");

        String npcName = config.getString("scene.npc.name", "NPC");
        String npcRole = config.getString("scene.npc.role", "");
        String npcGreeting = config.getString("scene.npc.greeting", "Hello!");
        this.npc = new NPC(npcName, npcRole, npcGreeting);

        this.nodes = new LinkedHashMap<>();
        loadNodes(config);
    }

    @SuppressWarnings("unchecked")
    private void loadNodes(FileConfiguration config) {
        org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("dialogue_tree");
        if (section == null) {
            return;
        }

        for (String nodeId : section.getKeys(false)) {
            String path = "dialogue_tree." + nodeId;
            String npcText = config.getString(path + ".npc_text", "");
            boolean isEnd = config.getBoolean(path + ".is_end", false);

            List<DialogueOption> options = new ArrayList<>();
            List<?> rawList = config.getList(path + ".options");

            if (rawList != null) {
                for (Object item : rawList) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> optData = (Map<String, Object>) item;
                        String optId = (String) optData.get("id");
                        String optText = (String) optData.get("text");
                        String nextNode = (String) optData.get("next_node");
                        options.add(new DialogueOption(optId, optText, nextNode));
                    }
                }
            }

            nodes.put(nodeId, new DialogueNode(nodeId, npcText, options, isEnd));
        }
    }

    public DialogueNode getStartNode() {
        return nodes.get("start");
    }

    public DialogueNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public NPC getNpc() {
        return npc;
    }

    public Map<String, DialogueNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }
}