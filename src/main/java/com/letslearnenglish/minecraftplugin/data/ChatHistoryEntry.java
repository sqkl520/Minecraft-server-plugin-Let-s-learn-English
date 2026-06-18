package com.letslearnenglish.minecraftplugin.data;

import java.sql.Timestamp;

/**
 * Represents a single message in AI chat history.
 * Used both for individual messages and for session summary queries.
 */
public class ChatHistoryEntry {

    private final int id;
    private final String playerUuid;
    private final String sessionId;
    private final String role;
    private final String content;
    private final Timestamp timestamp;

    public ChatHistoryEntry(int id, String playerUuid, String sessionId,
                            String role, String content, Timestamp timestamp) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public String getPlayerUuid() { return playerUuid; }
    public String getSessionId() { return sessionId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public Timestamp getTimestamp() { return timestamp; }

    /**
     * Extract the scenario name from a system prompt.
     * System prompt format: "You are role-playing a scenario: {scenarioName}.\n\n..."
     */
    public static String extractScenarioFromSystemPrompt(String systemContent) {
        if (systemContent == null) return "Unknown";
        String prefix = "You are role-playing a scenario:";
        int startIdx = systemContent.indexOf(prefix);
        if (startIdx < 0) return "Unknown";
        startIdx += prefix.length();
        int endIdx = systemContent.indexOf(".", startIdx);
        if (endIdx < 0) endIdx = systemContent.indexOf("\n", startIdx);
        if (endIdx < 0) endIdx = systemContent.length();
        return systemContent.substring(startIdx, endIdx).trim();
    }
}
