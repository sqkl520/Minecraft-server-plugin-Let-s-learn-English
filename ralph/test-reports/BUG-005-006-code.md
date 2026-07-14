# 代码质量报告 BUG-005-006
### 判定：FAIL

| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | LearningCalendarGUI.java:75 | `msg.getString("calendar.title", "&a&lStudy History")` — 硬编码英文标题作为 fallback，玩家可见 |
| 2 | i18n | LearningCalendarGUI.java:95 | `getCalendarMsg(..., "无学习记录", "No activity")` — 硬编码中英文 fallback 消息 |
| 3 | i18n | LearningCalendarGUI.java:96 | `getCalendarMsg(..., "轻度学习", "Light")` — 硬编码中英文 fallback 消息 |
| 4 | i18n | LearningCalendarGUI.java:97 | `getCalendarMsg(..., "适度学习", "Moderate")` — 硬编码中英文 fallback 消息 |
| 5 | i18n | LearningCalendarGUI.java:98 | `getCalendarMsg(..., "深入学习", "Heavy")` — 硬编码中英文 fallback 消息 |
| 6 | i18n | LearningCalendarGUI.java:99 | `getCalendarMsg(..., "&e★ 今天", "&e★ Today")` — 硬编码中英文 fallback 消息 |
| 7 | i18n | LearningCalendarGUI.java:100 | `getCalendarMsg(..., "词", "words")` — 硬编码中英文 fallback 消息 |
| 8 | i18n | LearningCalendarGUI.java:170-171 | `getCalendarMsg(..., "返回", "Back")` 及 lore 均含硬编码中英文 fallback |
| 9 | i18n | LearningCalendarGUI.java:188 | `getDayLabels()` 内 `String[] defaults = {"Mon","Tue",...}` — 硬编码英文星期标签作为 fallback |

### 通过项

- LearningCalendarGUI.java 空 slot 填充：第 178-182 行在 `openInventory` 前遍历全部 54 个 slot，null 处填充灰色玻璃板，顺序正确。
- LearningCalendarGUI.java 装饰用填充物 name=`" "` 为硬编码，符合豁免规则。
- PlayerDataStore.java `getRankedPlayers()`：两步法正确 — Step 1(748-765) ResultSet 打开期间仅收集原始行到 `List<Object[]>`，无嵌套查询；Step 2(768-779) ResultSet 关闭后调用 `getPlayerStats()`/`getPlayerName()`。
- PlayerDataStore.java `getRankedPlayersForPeriod()`：同上两步法，结构正确。
- PlayerDataStore.java try-with-resources：`PreparedStatement` 和 `ResultSet` 均在 try-with-resources 内；`Connection` 在外部获取，未被 try-with-resources 关闭，符合单连接复用设计。
