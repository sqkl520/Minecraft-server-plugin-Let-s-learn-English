# 代码质量报告 BUG-CALENDAR-LISTENER
### 判定：FAIL

| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | zh.yml / en.yml | `calendar.title` key 在两个语言文件中均不存在，GUI 标题始终使用硬编码 fallback `"&a&lStudy History"`，i18n 路径完全失效 |
| 2 | 硬编码 | MiscGUIListener.java:38,54 | `title.contains("过往学习")` 为死代码——因为 `LearningCalendarGUI` 的 title fallback 仅为英文，中文玩家的 GUI 标题也渲染为 `§a§lStudy History`，该分支永远不会命中 |
| 3 | i18n | MiscGUIListener.java:136 | `handleDailyChallenge` 直接调用 `msgs.getString(..., "&a[Daily] &6Reward claimed! &e+{reward} coins")` 并硬编码英文 fallback 字符串，违反 i18n 规范（应通过 `MessageUtil` / `LanguageManager.getFor()` 读取） |
