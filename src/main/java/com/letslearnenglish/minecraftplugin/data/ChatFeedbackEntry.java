package com.letslearnenglish.minecraftplugin.data;

import java.sql.Timestamp;

/**
 * US-FB §3.1：会话级 AI 对话反馈行（表 {@code ai_chat_feedback}）。
 *
 * <p>一行对应一次完整的 AI 情景对话会话：本地统计（发言数 / AI 回复数 / 时长）在会话结束时
 * 先以 {@code source='local'} 落库兜底，AI 点评异步到达后 UPSERT 覆写为 {@code source='ai'}
 * 并补 {@code payload}（JSON: comment / corrections / words）。
 *
 * <p>形态对齐 {@link ChatHistoryEntry}：不可变 + 构造器注入 + getter。
 */
public class ChatFeedbackEntry {

    private final int id;
    private final String playerUuid;
    private final String sessionId;
    private final String scenarioKey;
    private final String difficultyKey;
    private final int userMessages;
    private final int aiMessages;
    private final int durationSeconds;
    private final String source;
    private final String payload;
    private final Timestamp timestamp;

    public ChatFeedbackEntry(int id, String playerUuid, String sessionId,
                             String scenarioKey, String difficultyKey,
                             int userMessages, int aiMessages, int durationSeconds,
                             String source, String payload, Timestamp timestamp) {
        this.id = id;
        this.playerUuid = playerUuid;
        this.sessionId = sessionId;
        this.scenarioKey = scenarioKey;
        this.difficultyKey = difficultyKey;
        this.userMessages = userMessages;
        this.aiMessages = aiMessages;
        this.durationSeconds = durationSeconds;
        this.source = source;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    public int getId() { return id; }
    public String getPlayerUuid() { return playerUuid; }
    public String getSessionId() { return sessionId; }
    public String getScenarioKey() { return scenarioKey; }
    public int getUserMessages() { return userMessages; }
    public int getAiMessages() { return aiMessages; }
    public int getDurationSeconds() { return durationSeconds; }
    /** 'local'（仅本地统计）或 'ai'（含 AI 点评 payload）。 */
    public String getSource() { return source; }
    /** AI 点评 JSON（comment / corrections / words）；仅本地行时为 null。 */
    public String getPayload() { return payload; }
    public Timestamp getTimestamp() { return timestamp; }
}
