# impl-guide-p1.md — v1.0.7 修复批次（中文服止血 + 仓库卫生）

> 目标版本：**1.0.7**（不 bump，pom.xml 保持 `1.0.7-SNAPSHOT`）
> 来源：`docs/reports/项目现状体检报告.html` 的 D-01 ~ D-19
> 本批次只做 P1（止血）+ 部分 P3（回归闭环），架构改造（InventoryHolder）留待下一版

---

## 硬性约束

| # | 约束 |
|---|------|
| C1 | **禁止硬编码玩家可见消息**。所有新增/修改的玩家可见文本走 `messages/zh.yml` + `messages/en.yml` |
| C2 | i18n 键由**编排者统一添加**，开发者只负责调用。调用时**不写 fallback 参数**（fallback 也算违规） |
| C3 | 编译必须通过：`mvn clean package -DskipTests` → BUILD SUCCESS |
| C4 | **不要**修改 `pom.xml` 版本号、`CHANGELOG.md`、`README.md`、`messages/*.yml`、`prd.json`、`progress.txt`、不要 `git commit` |
| C5 | 保持现有代码风格（中文注释、`&` 颜色码、`ChatColor.translateAlternateColorCodes`） |
| C6 | 只改自己负责的文件，避免与其他并行 agent 冲突 |

---

## 统一 i18n 键名表（编排者会添加，开发者直接调用）

### 新增键

```yaml
# progress 段新增（D-01）
progress:
  money-reward: "{prefix}&a答题奖励 &e{amount} &a金币"
  money-session-bonus: "{prefix}&a会话完成奖励 &e{amount} &a金币"
  money-review-reward: "{prefix}&a复习奖励 &e{amount} &a金币"
  money-review-penalty: "{prefix}&c复习惩罚 &e{amount} &c金币"

# profile 段新增（D-01 附带）
profile:
  settings-locked: "&8设置 (已锁定)"
  settings-locked-lore: "&7只能修改自己的设置"

# help 段新增（D-01 附带）
help:
  leaderboard: "&e/le leaderboard &7- 查看排行榜"
  challenge: "&e/le challenge &7- 查看今日挑战进度"

# admin-help 段新增（D-01 附带）
admin-help:
  ignore-update: "&e/lea ignore-update &7- 忽略本次更新提醒"
  broadcast: "&e/lea broadcast <消息> &7- 通过代理向全服广播"

# 新增 admin 段消息（AdminCommand 硬编码清理）
admin:
  usage-exportwordbank: "{prefix}&c用法: /lea exportwordbank <难度>"
  available-difficulties: "{prefix}&7可选难度: &e{list}"
  usage-importwordbank: "{prefix}&c用法: /lea importwordbank <文件名.yml>"
  importwordbank-hint: "{prefix}&7请先把文件放到 plugins/LetsLearnEnglish/exports/ 目录"
  player-only: "{prefix}&c此命令只能由玩家使用。"
  proxy-unavailable: "{prefix}&c代理消息不可用。服务器必须运行在 Velocity 或 BungeeCord 之后。"
  usage-broadcast: "{prefix}&c用法: /lea broadcast <消息>"
  broadcast-hint: "{prefix}&7通过代理向所有子服发送消息。"
  broadcast-sent: "{prefix}&a全局广播已发送到所有子服。"

# 确认界面新增（D-18，取消按钮）
language:
  cancel-item: "&a&l取消"
  cancel-lore: "&7点击关闭并返回主菜单"
```

### 已有键（直接用，别新增）

- `gui.back-button` / `gui.back-lore`（`gui` 段，已存在于 zh.yml:216-217）
- `word-learning-gui.*` 的**实际存在**键名：`.correct-answer` / `.wrong-answer` / `.correct-answer-combo` / `.time-up` / `.countdown`
- `calendar.title`（zh: `&a&l过往学习` / en: `&a&lStudy History`）

---

## 工作流划分（4 个并行 agent，文件互不重叠）

### Agent-A — 对决退出 + 确认界面

**文件：**`listener/DuelGUIListener.java`、`gui/ConfirmationGUI.java`、`listener/MainMenuListener.java`

**D-03 中文服对决退出按钮失效**
- 现状：`DuelGUIListener.java:41` 硬编码 `title.startsWith("&6&lWord Duel")`，中文标题是 `&6&l单词对决`（`zh.yml:182`），默认语言 zh → 判据不命中
- 文件里已有 `duelTitleZh` / `duelTitleEn` 两个变量（`:35-38`）但从未使用 —— 现在用起来
- 要求：
  1. `setCancelled(true)` 必须**提到身份判定之前**（现在在 `:45`，位于 `:42` 的 return 之后 → barrier 可被拖走）
  2. 身份判定改为：与玩家的语言配置标题比对，**同时**兼容中英两种（改语言后标题会变）
  3. 若标题为空或未配置，回退到 `title.contains("Word Duel") || title.contains("单词对决")`（与 `GUIUtils` 一致）
  4. 保留 slot 22 = 退出按钮逻辑不变

**D-18a 确认界面没有取消路径**
- 现状：`ConfirmationGUI` 只有 slot 13 确认键，`MainMenuListener.java:93` 对非 13 槽直接 `return`（且不 `setCancelled`）→ 玩家只能按 ESC
- 要求：
  1. `ConfirmationGUI` 的 `open()` 与 `openSecond()` 都增加一个**取消按钮**，放在 **slot 22**（底部居中，与其它 GUI 的一致区域）
     - 材质：`Material.BARRIER`
     - 名称：`language.cancel-item`，lore：`language.cancel-lore`
  2. `MainMenuListener.handleConfirmGUI` 增加 slot 22 分支：`ConfirmationGUI.resetStage(player)` + 播放 `menu-click` 音效 + `new MainMenu(plugin).open(player)`
  3. 对非 13/22 槽保持 return，但**整体已 `setCancelled(true)`**，无需改动（`:36` 已做）
  4. 取消按钮的 slot 抽成 `public static final int CANCEL_SLOT = 22` 常量，供 listener 引用（与 `ChatHistoryGUI.BACK_SLOT` 风格一致）

---

### Agent-B — 英语广场翻页 + 排行榜返回键 + 音效白名单

**文件：**`listener/SocialListener.java`、`gui/LeaderboardGUI.java`、`gui/GUIUtils.java`

**D-04 中文服广场翻页永远回第 1 页**
- 现状：`SocialListener.java:275-291` `extractPageFromTitle()` 按空格切分找 `"Page"` 词元；zh 标题 `英语广场 - 第1/3页` 切不出该词元 → 返回默认 1
- 要求：
  1. 在 `gui/GUIUtils.java` 新增 **`public static int extractPageFromTitle(String title)`**：用正则提取标题中**第一个整数**，失败返回 `1`
     - 需同时匹配 `Page 2`、`第2页`、`第 2 / 3 页`、`2/3` 等形式
     - 建议正则：`(\d+)` 首个命中即可（标题里页码总是第一个出现的数字）
  2. `SocialListener` 删除本地 `extractPageFromTitle`，改调 `GUIUtils.extractPageFromTitle(title)`
  3. **顺带修 `extractTargetIdFromTitle`（`:268-279`）的名字子串误配**：当前用 `title.contains(name)`，`Bob` 会命中 `Bobby` 的主页标题
     - 改为：优先取**最长的匹配名字**（遍历时记录最长命中），避免短名前缀误配

**D-18b 排行榜返回键未统一**
- 现状：`LeaderboardGUI.java:43` `BACK_SLOT = 49`（底部居中），其余 14 个 GUI 都已统一到 slot 8；`SocialListener.java:214` 也硬编码 `slot == 49`
- 要求：
  1. `LeaderboardGUI.BACK_SLOT` 改为 **8**，并把常量改为 `public static final`（供 listener 引用）
  2. `ENTRY_SLOTS` 检查是否与 slot 8 冲突 —— 若冲突，调整 `ENTRY_SLOTS` 数组避开 slot 8
  3. `SocialListener.java:214` 的 `slot == 49` 改为 `slot == LeaderboardGUI.BACK_SLOT`
  4. 若 `config.yml` 的 `gui-layout.leaderboard.back-button` 有配置，默认值同步改 8（**不要改 config.yml，只改代码默认值**）

**D-13a GUIUtils 音效白名单漂移（日历）**
- 现状：`GUIUtils.java:44-45` 仍是 `学习日历` / `Learning Calendar`，而实际标题已改为 `过往学习`（zh.yml:325）/ `Study History`（en.yml:325）→ 日历不播打开音效
- 要求：把这两个字符串改为 `过往学习` / `Study History`；同时**补上** `AI 对话历史`（zh ChatHistory 标题）与 `Chat History` 已在列，检查一遍是否还有其它漂移

---

### Agent-C — 对话历史 / 错题本槽位边界

**文件：**`gui/ChatHistoryGUI.java`、`listener/ChatHistoryListener.java`、`gui/WrongBookGUI.java`、`listener/WrongBookListener.java`

**D-05 对话历史第 9 条会话点不开**
- 现状：`ChatHistoryGUI.java:34 SESSION_START_SLOT = 0`，`SESSIONS_PER_PAGE = 10` → 会话占 slot 0-9；`BACK_SLOT = 8`（`:33`）在 `:74` 先设，被第 9 条会话（slot 8）覆盖
- 要求：
  1. `SESSION_START_SLOT` 改为 **9**（从第二行开始，让 slot 0-8 留给返回键与留白）
  2. 确认 `SESSIONS_PER_PAGE = 10` 在 slot 9-18 范围内不与 `PREV_PAGE_SLOT = 47` / `NEXT_PAGE_SLOT = 51` 冲突
  3. `ChatHistoryListener` 的会话下标计算若依赖 `SESSION_START_SLOT`，同步更新（检查 `handleListViewClick`）

**D-06 错题本每页最后 9 个单词点不动**
- 现状：`WrongBookGUI.java:31-32` `GUI_SIZE = 54`、`ITEMS_PER_PAGE = 45`；槽位映射 `invSlot = idx + 9`（`:99-101`）→ 9~53；`WrongBookListener.java:79` 只接受 `slot >= 9 && slot <= 44`
- 要求：
  1. **改正 listener 的上界**：`slot <= 44` 改为 `slot <= 53`（rows 1-5 是 slot 9-53）
     - 但 slot 47/51 是翻页按钮、slot 49/50 可能是装饰 —— 需要在单词循环里**跳过**这些槽位
  2. 更稳的做法：`WrongBookGUI` 定义 `public static final int[] WORD_SLOTS`（可用槽位数组），单词按顺序填入这些槽位；`WrongBookListener` 用 `Arrays.binarySearch(WORD_SLOTS, slot) >= 0` 判定
     - 可用的单词槽位：第二行到第五行（slot 9-44）+ 第六行的 slot 45、46、48、50、52、53（避开 47=上一页、49=页码指示、51=下一页）
     - 按此计算每页容量，`ITEMS_PER_PAGE` 同步改
  3. 更新那句错误注释 `// Word entries are in rows 1-5 (slots 9-44)`
  4. **空槽占位**：`WrongBookGUI` 中部单词网格的空槽目前裸露，补灰色玻璃板填充（参考 `LearningCalendarGUI.java:176-181` 的兜底循环写法）

---

### Agent-D — 奖励消息接线 + 热重载补全 + volatile + 自动存档

**文件：**`core/progress/RewardEngine.java`、`core/manager/HotReloadManager.java`、`core/SoundManager.java`、`core/ai/AIChatManager.java`、`core/ai/TtsManager.java`、`LetsLearnEnglish.java`

**D-01 Java 侧 奖励消息键接线**
- 现状：`RewardEngine.java:62/98/144/176` 请求 `progress.money-*` 四个键，yml 里不存在 → `formatMessage` 返回 `"Missing message: ..."` 发玩家
- 要求：
  1. 键名**保持不变**（`progress.money-reward` 等），由编排者补 yml
  2. **删除 `giveAchievementReward`（`:219-229`）方法** —— 全项目零调用者，且它引用的 `core/progress/Achievement` 是死类（同包无需 import，易被误认为在用）
  3. `giveSessionReward`（`:194`）与私有 `giveExpReward`（`:206`）也全项目零调用 —— **保留但加注释**标明「预留：经验奖励链未接线」，或若确认不需要则删除。**倾向删除**（死代码清理），但删除前 grep 确认零调用

**D-10a volatile 补全**
- `AIChatManager.java:55 private String apiKey` → 加 `volatile`
- `TtsManager.java:34 private String apiKey` → 加 `volatile`
- （`StatisticsHttpServer.running` 已是 volatile，不动）

**D-08b 热重载补全**
- 现状：`HotReloadManager.reloadAll()`（`:24-33`）只重载 config、词库、PlayerDataStore 缓存、成就缓存
- 要求：
  1. 在 `reloadAll()` 里**补调** `plugin.getSoundManager().reloadSoundCache()`（让 `SoundManager.reloadSoundCache` 这个死方法接上线）
  2. 确认 `reloadWordBanks`（`:38`）是否已被 `reloadAll` 调用 —— 若无则接上
  3. **不要**在 `reloadAll` 里重启 `StatisticsHttpServer`（涉及端口绑定，风险高，留待下一版）

**性能 自动存档不再阻塞主线程（写库异步化）**
- 现状：`LetsLearnEnglish.java:269-279` 已把「快照 UUID」放主线程、DB 写入放异步 —— 但 `PlayerDataStore.savePlayerData` 内部可能有主线程依赖，需确认
- 要求：**只做只读核查**。若发现 `savePlayerData` / `saveProfileSettings` 内部触碰 Bukkit API（如 `Bukkit.getPlayer`），改为从参数取 UUID、不查在线玩家。若无需改动则不动。

---

## 验收标准（整个批次）

- [ ] `mvn clean package -DskipTests` → BUILD SUCCESS
- [ ] 中文服（`language: zh`）下：对决 GUI 退出按钮可点、广场「下一页」能翻、对话历史第 9 条能打开、错题本每页全部单词可点
- [ ] 无玩家可见的 `Missing message: ...` 文本
- [ ] `AdminCommand` 无裸硬编码玩家消息
- [ ] `/le help` 打印 17 行（含 leaderboard / challenge / duel）
- [ ] 确认界面有取消路径（slot 22 barrier）
- [ ] 排行榜返回键在 slot 8
- [ ] 学习日历能播打开音效
- [ ] 无 TODO/FIXME 遗留
