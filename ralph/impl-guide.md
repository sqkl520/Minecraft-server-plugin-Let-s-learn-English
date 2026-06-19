# 实现指南 — GUI Bug 修复

---

## US-001 — Learning Calendar 主菜单项缺少 i18n 翻译

### 目标
补充 `main-menu.calendar` 和 `main-menu.calendar-lore` 两个 i18n 键值到中英文 message 文件，使主菜单中的"Learning Calendar"入口项能正确显示对应语言的文本。

### 根因分析
`MainMenu.java` 第 84-88 行使用 `messages.getString("main-menu.calendar", "&aLearning Calendar")` — 当 `zh.yml` 和 `en.yml` 均缺少该 key 时，始终回退到硬编码英文默认值 `"&aLearning Calendar"`。两个 message 文件（zh.yml / en.yml）的 `main-menu` 段都没有定义 `calendar` 和 `calendar-lore`。

### 涉及文件
- `src/main/resources/messages/zh.yml` — 修改（追加 2 个键值）
- `src/main/resources/messages/en.yml` — 修改（追加 2 个键值）

### 实现要点
1. 在 `zh.yml` 的 `main-menu:` 段末尾（`wrong-book-lore` 之后）追加：
   ```yaml
     calendar: "&a学习日历"
     calendar-lore: "&7查看你的每日学习活动"
   ```
2. 在 `en.yml` 的 `main-menu:` 段末尾（`wrong-book-lore` 之后）追加：
   ```yaml
     calendar: "&aLearning Calendar"
     calendar-lore: "&7View your daily learning activity"
   ```

### 验收条件
- [ ] 切换到中文后，主菜单第 9 槽显示中文"学习日历"
- [ ] 切换到英文后，主菜单第 9 槽显示"Learning Calendar"
- [ ] mvn clean compile passes

### 依赖
无

---

## US-002 — 错题本页面修复：标题拼接 / 装饰行 / 返回按钮位置

### 目标
修复 WrongBookGUI 的三个问题：(A) 标题硬编码中文拼接"| Page N"导致语言混杂；(B) 首行红色玻璃装饰冗余；(C) 返回按钮从 slot 49 移到 slot 8 与其他 GUI 统一。

### 根因分析

**A — 标题拼接**: `WrongBookGUI.java` 第 51 行 `title = title + " | Page " + page;` 将英文 "Page" 硬编码拼接到标题末尾。`zh.yml` 的 `wrong-book.title` 已经是 `"&c&l错题本 / Wrong Book"`，拼接后变成 `"错题本 / Wrong Book | Page 1"`。需要将 "Page" 也纳入 i18n 或在标题模板中直接包含 `{page}` 占位符。

**B — 装饰行冗余**: `WrongBookGUI.java` 第 70-72 行用红色玻璃填满首行（slot 0-8），但其他 GUI（ProfileGUI、LearningCalendarGUI、DailyChallengeGUI）均不使用整行纯色装饰。用户认为这是多余信息。

**C — 返回按钮位置**: 当前返回按钮在 slot 49（底部中央）。其他 GUI 的返回按钮标准位置是 slot 8（右上角）—— ProfileGUI、EnglishSquareGUI、DailyChallengeGUI、LearningCalendarGUI、PrivacySettings 全部使用 slot 8。

### 涉及文件
- `src/main/java/com/letslearnenglish/minecraftplugin/gui/WrongBookGUI.java` — 修改
- `src/main/java/com/letslearnenglish/minecraftplugin/listener/WrongBookListener.java` — 修改
- `src/main/resources/messages/zh.yml` — 修改（新增 `wrong-book.page-title` 键）
- `src/main/resources/messages/en.yml` — 修改（新增 `wrong-book.page-title` 键）

### 实现要点

**A — 修复标题**:
1. 在 `zh.yml` 的 `wrong-book` 段新增 `page-title: "&c&l错题本 | 第{page}页"`
2. 在 `en.yml` 的 `wrong-book` 段新增 `page-title: "&c&lWrong Book | Page {page}"`
3. 修改 `WrongBookGUI.open()` 标题构造逻辑：
   ```java
   String title = ChatColor.translateAlternateColorCodes('&',
           messages.getString("wrong-book.page-title", "&c&lWrong Book | Page {page}")
                   .replace("{page}", String.valueOf(page)));
   ```
4. 对应的 `WrongBookListener.extractPageFromTitle()` 需要适配新的标题格式（提取 `{page}` 占位符已被替换后的数字）

**B — 去掉装饰行**:
1. 删除或注释掉 `WrongBookGUI.open()` 第 70-72 行的首行填充代码块

**C — 移动返回按钮**:
1. 将 `BACK_SLOT` 常量从 `49` 改为 `8`
2. 同步更新 `WrongBookListener.java` 的 `BACK_SLOT` 常量（当前为 `49`）
3. 注意：slot 8 当前被装饰行占用，删除装饰行后该槽位空出，直接可用

### 验收条件
- [ ] 中文模式下标题显示"错题本 | 第1页"（中文），不再包含英文 "Page"
- [ ] 英文模式下标题显示"Wrong Book | Page 1"
- [ ] 首行不再有红色玻璃装饰
- [ ] 返回按钮在右上角（slot 8），与其他 GUI 风格一致
- [ ] 翻页时 `extractPageFromTitle` 仍能正确解析页码
- [ ] mvn clean compile passes

### 依赖
无

---

## US-003 — 页面切换按钮风格统一

### 目标
统一所有带翻页功能的 GUI 的 prev/next 按钮样式（材料、标签格式、位置）。

### 根因分析
当前各 GUI 翻页按钮的对比：

| GUI | Prev 槽位 | Next 槽位 | 材料 | 标签格式 |
|-----|----------|----------|------|---------|
| EnglishSquareGUI | 47（可配） | 51（可配） | ARROW（可配） | `← 上一页` / `下一页 →` (zh) |
| WrongBookGUI | 47（固定） | 51（固定） | ARROW（固定） | 从 messages 读取 |
| ChatHistoryGUI | 47（固定） | 51（固定） | ARROW（固定） | 从 messages 读取 |
| LeaderboardGUI | 无翻页 | 无翻页 | — | — |

槽位位置已基本统一（47/51），需要统一的是：
1. WrongBookGUI 不支持 ConfigManager 的可配置槽位/材料 — 应改为可配置
2. ChatHistoryGUI 也应支持 ConfigManager 可配置
3. 所有 GUI 的翻页按钮 lore 格式 `/"Page {page}"` 在中文模式下应显示为 `/"{page}页"` 或 `/"第{page}页"`

### 涉及文件
- `src/main/java/com/letslearnenglish/minecraftplugin/gui/WrongBookGUI.java` — 修改
- `src/main/java/com/letslearnenglish/minecraftplugin/gui/ChatHistoryGUI.java` — 修改（可选，保持一致）
- `src/main/java/com/letslearnenglish/minecraftplugin/listener/WrongBookListener.java` — 修改（槽位改为从 GUI 常量引用）
- `src/main/java/com/letslearnenglish/minecraftplugin/listener/ChatHistoryListener.java` — 修改（同上）
- `src/main/resources/messages/zh.yml` — 修改（统一 lore 占位符格式）
- `src/main/resources/messages/en.yml` — 修改（同上）

### 实现要点
1. WrongBookGUI 翻页按钮的 slot 从硬编码改为调用 `ConfigManager.getGuiSlot("wrong-book", "prev-page", 47)` 和 `ConfigManager.getGuiSlot("wrong-book", "next-page", 51)`
2. WrongBookGUI 翻页按钮的 material 改为 `ConfigManager.getGuiMaterial("wrong-book", "prev-page", Material.ARROW)` 和 `ConfigManager.getGuiMaterial("wrong-book", "next-page", Material.ARROW)`
3. WrongBookListener 的 `PREV_SLOT` / `NEXT_SLOT` 常量改为引用 WrongBookGUI 的公共常量或通过方法获取（不能用 static final 因为取决于 config）
4. 统一 page indicator 格式：EnglishSquareGUI 不使用 indicator；WrongBookGUI 使用 `Page {current}/{total}`（slot 48）；ChatHistoryGUI 使用 NAME_TAG（slot 45）。统一到 slot 48 并使用一致的 item 样式。
5. ChatHistoryGUI 同样应用 ConfigManager 可配置化（其 slots 已经引用 `ChatHistoryGUI.PREV_PAGE_SLOT` / `NEXT_PAGE_SLOT` 公共常量，只需改为从 ConfigManager 读取）

### 验收条件
- [ ] WrongBookGUI 翻页按钮可通过 config.yml 的 `gui-layout.wrong-book.prev-page.*` / `next-page.*` 配置
- [ ] ChatHistoryGUI 翻页按钮可通过 config.yml 的 `gui-layout.chat-history.prev-page.*` / `next-page.*` 配置
- [ ] 所有翻页按钮的样式（标签、材料、lore 格式）保持一致
- [ ] mvn clean compile passes

### 依赖
US-002（错题本改动与此 Story 的翻页按钮改动有重叠，建议 US-002 先改，US-003 在此基础上加配置化）

---

## US-004 — GUI 导航栈：返回路径断裂

### 目标
修复 `GuiNavigationManager` 的单槽覆盖问题，使多层级导航（English Square → Profile → Achievement Wall → Profile → Back）能正确返回到 English Square 而非 MainMenu。

### 根因分析

`GuiNavigationManager` 当前实现：
```java
public class GuiNavigationManager {
    private final Map<UUID, Runnable> profileBackActions = new ConcurrentHashMap<>();

    public void pushBack(UUID playerId, Runnable action) {
        profileBackActions.put(playerId, action);  // 覆盖！
    }

    public Runnable popBack(UUID playerId, Runnable fallback) {
        Runnable action = profileBackActions.remove(playerId);  // 取出并删除
        return (action != null) ? action : fallback;
    }
}
```

问题追踪（English Square → Profile → Achievement Wall → Profile → Back）：

1. **English Square → 点击玩家头**: `pushBack(open EnglishSquare(page, mode))` → map = `{uuid: EnglishSquare back}`
2. **Profile → 点击成就墙(slot 18)**: `pushBack(open Profile(targetId))` → map = `{uuid: Profile back}` —— **EnglishSquare 被覆盖！**
3. **Achievement Wall → 点击返回(slot 49)**: `popBack(fallbackProfile)` → 取出 Profile back，执行 → 回到 Profile。map 变空。
4. **Profile → 点击返回(slot 8)**: `popBack(fallbackMainMenu)` → map 为空 → 执行 fallback（MainMenu）。**用户期望回到 English Square。**

### 涉及文件
- `src/main/java/com/letslearnenglish/minecraftplugin/core/GuiNavigationManager.java` — 修改（核心改动）
- `src/main/java/com/letslearnenglish/minecraftplugin/listener/SocialListener.java` — 无需改动（API 兼容）
- `src/main/java/com/letslearnenglish/minecraftplugin/listener/MiscGUIListener.java` — 无需改动（API 兼容）
- `src/main/java/com/letslearnenglish/minecraftplugin/listener/MainMenuListener.java` — 无需改动（API 兼容）

### 实现要点

1. 将 `GuiNavigationManager` 的内部数据结构从 `Map<UUID, Runnable>` 改为 `Map<UUID, Deque<Runnable>>`（使用 `ArrayDeque`）：
   ```java
   private final Map<UUID, Deque<Runnable>> navigationStacks = new ConcurrentHashMap<>();
   ```

2. 修改 `pushBack()` —— 使用 `computeIfAbsent` + `push`（栈顶推入）：
   ```java
   public void pushBack(UUID playerId, Runnable action) {
       navigationStacks.computeIfAbsent(playerId, k -> new ArrayDeque<>()).push(action);
   }
   ```

3. 修改 `popBack()` —— 使用 `poll`（栈顶弹出）：
   ```java
   public Runnable popBack(UUID playerId, Runnable fallback) {
       Deque<Runnable> stack = navigationStacks.get(playerId);
       if (stack != null && !stack.isEmpty()) {
           Runnable action = stack.poll();  // 弹出栈顶
           if (stack.isEmpty()) {
               navigationStacks.remove(playerId);  // 清理空栈
           }
           return action;
       }
       return fallback;
   }
   ```

4. 原有的 `pushBack`/`popBack` 方法签名保持不变，所有调用方（SocialListener、MiscGUIListener、MainMenuListener）无需修改。

5. 需要添加 `import java.util.ArrayDeque;` 和 `import java.util.Deque;`

### 验收条件
- [ ] 路径 English Square → Profile → Achievement Wall → Profile → Back → **回到 English Square**（相同 page/mode）
- [ ] 路径 English Square → Profile → Back → **回到 English Square**
- [ ] 路径 MainMenu → LearningCalendar → Back → **回到 MainMenu**
- [ ] 路径 MainMenu → DailyChallenge → Back → **回到 MainMenu**
- [ ] 直接 `/le profile` 打开的 Profile 页面，点 Back → **回到 MainMenu**（无栈时的 fallback）
- [ ] 多次进出不会导致内存泄漏（空栈被清理）
- [ ] mvn clean compile passes

### 依赖
无

---

## 附录：各 GUI 返回按钮槽位汇总（修复后）

| GUI | 返回按钮槽位 | 备注 |
|-----|------------|------|
| MainMenu | — | 顶层 |
| ProfileGUI | slot 8 | 已符合标准 |
| ProfileSettings | slot 8 | 已符合标准 |
| EnglishSquareGUI | slot 8 | 已符合标准 |
| AchievementWallGUI | slot 49 | 待改为 slot 8（可选后续优化） |
| LearningCalendarGUI | slot 8 | 已符合标准 |
| DailyChallengeGUI | slot 8 | 已符合标准 |
| ChallengeCompleteGUI | slot 8 | 已符合标准 |
| WordBankSelectGUI | slot 8 | 已符合标准 |
| WrongBookGUI | slot 8 | US-002 中修复 |
| ChatHistoryGUI | slot 49 | 待改为 slot 8（可选后续优化） |
| ScenarioSelectGUI | slot 8 | 已符合标准 |
| LeaderboardGUI | slot 49 | 待改为 slot 8（可选后续优化） |
