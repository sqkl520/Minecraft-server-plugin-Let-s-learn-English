# 代码质量报告 calendar-move

### 判定：FAIL

| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | ProfileGUI.java:73 | messages.getString 第二参数为硬编码中文 `"&e过往学习"`，key 缺失时直接暴露给玩家 |
| 2 | i18n | ProfileGUI.java:75 | messages.getString 第二参数为硬编码中文 `"&7查看你的学习记录"`，key 缺失时直接暴露给玩家 |

### 通过项

| 文件 | 检查项 | 结果 |
|------|--------|------|
| MainMenu.java | slot 9 日历按钮已删除 | PASS |
| MainMenu.java | 无 LearningCalendarGUI 残留 | PASS |
| MainMenuListener.java | case 9 分支已删除 | PASS |
| MainMenuListener.java | LearningCalendarGUI import 已删除 | PASS |
| ProfileGUI.java | slot 0 材质为 CLOCK | PASS |
| SocialListener.java | slot 0 含 pushBack | PASS |
| SocialListener.java | slot 0 打开 LearningCalendarGUI | PASS |
| SocialListener.java | LearningCalendarGUI import 存在且被使用 | PASS |
