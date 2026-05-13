package com.letslearnenglish.minecraftplugin.core.dialogue;

import java.util.Collections;
import java.util.List;

/**
 * Dialogue Node - a single node in the dialogue tree.
 *
 * Contains the NPC's line and the player's response options.
 */
public class DialogueNode {

    private final String id;
    private final String npcText;
    private final List<DialogueOption> options;
    private final boolean isEnd;

    public DialogueNode(String id, String npcText, List<DialogueOption> options, boolean isEnd) {
        this.id = id;
        this.npcText = npcText;
        this.options = options != null ? options : List.of();
        this.isEnd = isEnd;
    }

    public String getId() {
        return id;
    }

    public String getNpcText() {
        return npcText;
    }

    public List<DialogueOption> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public boolean isEnd() {
        return isEnd;
    }

    /**
     * Inner class representing a player response option.
     */
    public static class DialogueOption {
        private final String id;
        private final String text;
        private final String nextNode;

        public DialogueOption(String id, String text, String nextNode) {
            this.id = id;
            this.text = text;
            this.nextNode = nextNode;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public String getNextNode() {
            return nextNode;
        }
    }
}