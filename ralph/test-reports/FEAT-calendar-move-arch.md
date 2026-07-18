# 架构报告 FEAT-calendar-move

### 判定：PASS

## 审查范围

- `gui/ProfileGUI.java`
- `listener/SocialListener.java`

## 维度审查结果

### 1. 分层合规

**ProfileGUI（gui 层）**
- 无任何对 `LearningCalendarGUI` 的 import 或实例化。
- slot 0 仅放置了 `WRITABLE_BOOK` 按钮，不包含任何导航逻辑。
- 依赖项：`LetsLearnEnglish`、`ConfigManager`、`PlayerDataStore`、`AchievementManager` —— 全部来自 core/data/config 层，符合 gui→core 方向。

**SocialListener（listener 层）**
- `LearningCalendarGUI` 的实例化发生在 `handleProfileGUI()` 内（listener 层调用 gui 层），方向合规。
- import 清单：`LearningCalendarGUI`、`ProfileGUI`、`AchievementWallGUI`、`EnglishSquareGUI`、`LeaderboardGUI`、`MainMenu` —— 均为 gui 层，无越级依赖。

### 2. GuiNavigationManager 使用正确性

`handleProfileGUI` slot 0 分支：

```
nav.pushBack(...)          // 先注册回退目标（ProfileGUI）
new LearningCalendarGUI(plugin).open(player)   // 再打开新 GUI
```

`pushBack` 在 `open` 之前调用，顺序正确。

### 3. ProfileGUI 对 LearningCalendarGUI 的依赖

ProfileGUI 自身不 import、不实例化 `LearningCalendarGUI`。
跳转逻辑完全由 `SocialListener.handleProfileGUI()` 承载，gui→gui 同层调用路径不存在，分层干净。

### 4. DI 完整性

- `SocialListener` 构造函数接收 `LetsLearnEnglish plugin`，通过 `plugin.getGuiNavigationManager()`、`plugin.getPlayerDataStore()` 获取依赖，无直接 `new` 非 GUI 依赖对象。
- `LearningCalendarGUI`、`ProfileGUI` 等 GUI 对象在事件处理时即时构造（临时对象），符合项目既有惯例，不违反 DI 规范。

## 结论

四个维度零违规，判定 PASS。
