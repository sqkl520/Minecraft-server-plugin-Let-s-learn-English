# 代码质量报告 FEAT-calendar-move

### 判定：FAIL

| # | 维度 | 位置 | 原因 |
|---|------|------|------|
| 1 | i18n | ProfileGUI.java:73 | `messages.getString("main-menu.calendar", "&e过往学习")` — 硬编码中文作为 fallback 参数，yml key 缺失时玩家会看到原始硬编码文字 |
| 2 | i18n | ProfileGUI.java:75 | `messages.getString("main-menu.calendar-lore", "&7查看你的学习记录")` — 同上，lore fallback 硬编码中文 |

### 通过维度
- i18n key 存在：`main-menu.calendar` / `main-menu.calendar-lore` 在 zh.yml 和 en.yml 均有定义
- 导航栈：slot 0 点击前正确调用 `nav.pushBack()` (SocialListener.java:170-171)
- fillBorder：slot 0 未在 borderSlots 数组中，不会被边框玻璃覆盖 (ProfileGUI.java:274)
- 线程安全：`@EventHandler` 在主线程执行，无 async 修饰
