# 代码质量报告 US-GUI-code
### 判定：FAIL

| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | ChatHistoryGUI.java:128 | 硬编码显示格式 `"&b&l" + scenario + " &7- " + dateStr`，未使用 messages.yml 模板。项目标准模式应为 `msg.getString("chat-history.session-item-format", "&b&l{scenario} &7- {date}").replace(...)`。此字符串是用户可见的 GUI 物品名称，应由 i18n 系统管理。 |
| 2 | i18n | LearningCalendarGUI.java:129-134 | 硬编码星期标签。`getDayLabels()` 方法直接返回中文数组 `{"一","二","三","四","五","六","日"}` 和英文数组 `{"Mon","Tue","Wed","Thu","Fri","Sat","Sun"}`，未通过 messages.yml 定义。这些标签作为 GUI 物品显示名称（第52行 `"&e&l" + labels[d]`）对用户可见。 |

---

# 代码质量报告 US-GUI-code (Re-test)
### 判定：PASS

修复验证结果：两处硬编码均已在 `messages/{en,zh}.yml` 中定义对应 key，代码改为 `msg.getString()` 读取模板。无新增违规。
