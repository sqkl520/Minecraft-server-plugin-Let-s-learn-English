# findings-r11 UX 旅程专项（审查 Agent 产出）
- 审查范围：跨域静态走查（3 条旅程 + 静默失败清单 + 文案巡检）
- 审查时间：2026-10-18
- 结论摘要：高 0 / 中 4 / 低 4 / 待验证 3 / 豁免挑战 0（注：R11 为体验域，无崩溃类高危；"中"含全部确认的静默失败/误导）
- 备注：本文件由编排者按该 agent 返回全文原样保存（harness 禁止子代理写文件）

## 旅程地图（摘要）
- **旅程 A 新人**：join 欢迎×3(40L,PlayerJoinListener:47-58) ✅ → 引导书+intro(OnboardingManager:120-130) ✅ → /le menu(MainMenu:31)/别名 le/learn/english(plugin.yml:16) ✅ → 三入口拦截迷你会话(OnboardingManager:169-198) ✅ → 首成就(FIRST_WORD, AchievementManager:367) ✅ → 挑战领取(MiscGUIListener:227) ✅/每日挑战界面(MainMenuListener:141) ✅ → 签到(LearnCommand:706) ✅ → 个人主页(ProfileGUI:38,完成引导) ✅ → 成就墙(SocialListener:434) ✅ → 广场(EnglishSquareGUI) ✅ → AI 首轮(ChatScenarioListener) ✅ → 决斗邀请(DuelChallengeGUI PDC 映射) ✅ → 榜单(LearnCommand:422+F3-01 快照登记) ✅ → /lea report(AdminCommand:100 report 权限独立) ✅
- **旅程 B 老玩家**：/le review 三态完整(LearnCommand:364-395, 含 R-12 失效复审词回显) ✅ → 错题本双页入口(WrongBookGUI/Listener) ✅ → 断签恢复(streak-broken 先行提示, LearnCommand:724) ✅ → 广场回访(FEED 快照冻结, SocialListener:126) ✅ → 赛季知晓(join 补发 claimPendingRewards + 广播; season.enabled 默认 false 时 /le season 回 season.disabled) ✅
- **旅程 C 中断**：对话 quit(ChatScenarioListener:168-176 endConversationOnQuit+persistOnQuit) ✅ → 学习中关 GUI(InventoryClose→起倒计时, WordLearningListener:48-71) ✅ → 决斗重连(30s 宽限+20L 延迟 handleReconnect, DuelListener:39) ✅ → 领取时断线(claim 同步完成+openAsync isOnline 复检) ✅

## 中危

### R11-M1 [i18n·中·确定] WordLearningGUI 标题把原始难度标识透传给中文玩家（"- beginner" / "- review"）
- 位置：`gui/WordLearningGUI.java:33-35`；`messages/zh.yml:447`（`title: "单词学习 - {difficulty}"`）
- 证据：`MessageUtil.getGuiMsg(messages, "word-learning-gui.title").replace("{difficulty}", difficulty)` —— `difficulty` 为原始 key。全部调用点传原值：`listener/MiscGUIListener.java:167`（`banks.get(index)`）、`listener/ChatAnswerListener.java:278-279`（`wlManager.getDifficulty()`）、`LearnCommand.java:260`（规范化后 key）；复习会话传 `"review"`（LearnCommand:379）。聊天侧已由 F2-13/PF-ONB-03 修复（走 `WordModes.localizeDifficulty` → "初级"/"选择题"），**GUI 标题漏了**。
- 复现：中文玩家 /le word beginner → 界面标题显示"单词学习 - beginner"；/le review → "单词学习 - review"。
- 影响：与已修聊天侧同根漏面；中文玩家首屏看到英文标识符，教学一致性与信任感受损（旅程 A 第 2 步、旅程 B 第 1 步均经过）。
- 修法：`open()` 内 `difficulty` 显示值走 `WordModes.localizeDifficulty(...)`（review 特判 `word.mode-review`），或改为在调用点传入已本地化的 label（注意 GUI 无需 raw key 做逻辑）。

### R11-M2 [bug·中·高] MiscGUIListener 词库点击缺 try/catch：异常逃逸监听器 → 玩家零反馈且困在词库界面
- 位置：`listener/MiscGUIListener.java:161-167`
- 证据：
  ```java
  var session = plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
  if (session == null) { return; }
  new WordLearningGUI(plugin).openWithMessage(player, difficulty, mode);
  ```
  无 try/catch。`WordManagerImpl.startSession:137-139` 在 `sessionWords.isEmpty()` 时抛 `IllegalStateException("No words available...")`。对照命令路径 `LearnCommand.startWordQuiz:269-277` 有 `catch (IllegalStateException e)` → `word.no-words-available` 提示。
- 复现：管理员放入/保留一个零词词库 yml（或删空 words 列表）→ 玩家在词库选择 GUI 点击该词库 → ISE 冒泡出事件处理器（Bukkit 仅记日志）→ 无任何玩家可见反馈，停留在原界面反复可点。步骤 `interceptQuizStart`（上游 158 行）只 catch 后回落 false，不会兜住后续 startQuiz。
- 影响：静默死路 + 玩家困惑（点了没反应）；命令入口同场景有提示，双入口行为漂移。
- 修法：给 161-167 段补 `catch (IllegalStateException)` → 发 `word.no-words-available`（与 LearnCommand 同款）。

### R11-M3 [ux·中·高] 异步预载失败即静默不打开：每日挑战 / 过往学习点击成死点击
- 位置：`gui/DailyChallengeGUI.java:79-83`、`gui/LearningCalendarGUI.java:92-98`
- 证据：
  ```java
  } catch (Exception e) {
      plugin.getLogger().log(Level.WARNING, "Failed to preload daily challenge GUI for " + playerId, e);
      return;   // 主线程段不执行 → 界面永不打开，玩家无任何提示
  }
  ```
  （Calendar 同形：`"Failed to preload learning calendar data for "`）
- 复现：DB 单连接瞬时异常/查询失败时，玩家从主菜单点【每日挑战】或从主页点【过往学习】→ 服务器日志有 WARNING，玩家侧毫无反馈（无消息、无音效、界面不开）。
- 影响：与全仓"失败必有 i18n 提示"的既有口径（S4/R-12 系列修复）不一致；玩家无法区分"卡了"与"没点到"。
- 修法：catch 分支主线程回一句 `general.error` 或专用提示（如 `daily.load-failed`）；至少给一声 `incorrect` 音效。

### R11-M4 [bug·中·确定] 签到 DB 写失败与"你今天已经签到过了"共用文案 → 当日签到静默丢失且误导玩家
- 位置：`core/manager/CheckInManager.java:98-100`；`command/LearnCommand.java:746-749`
- 证据：
  ```java
  if (!plugin.getDatabaseManager().updateCheckIn(player.getUniqueId(), today, newStreak)) {
      return new CheckInResult(false, data == null ? 0 : data.getStreakCount(), 0, false);
  }
  ```
  → LearnCommand：`if (result.success()) {...} else { player.sendMessage(... "checkin.already" ...) }`（"你今天已经签到过了喵~ 明天再来吧"）。updateCheckIn 返回 false 的唯一来源是 SQLException（DatabaseManager.updateCheckIn catch 后 return false）。
- 复现：DB 写瞬时失败（锁超时/连接问题）→ 未签到的玩家收到"已经签到过"→ 玩家认为已完成、不再重试 → 当日 streak 断、奖励丢。真实失败被伪装成正常幂等分支。
- 影响：数据丢失被静默（玩家永远不会报告此 bug，因为文案告诉他一切正常）。
- 修法：CheckInResult 增加 `storageFailed` 语义（或独立返回），失败分支发"签到失败请稍后再试"，不与 already 混用。

## 低危

### R11-L1 [i18n·低·确定] 广场"笑话页"标题字面残留 `{page}`（独立与子代理双重确认）
- 位置：`gui/EnglishSquareGUI.java:453-454`（`openHidden`）；`zh.yml:486` / `en.yml:486`
- 证据：`String title = ...getGuiMsg(messages, "square.title")` —— 同文件 `open()` 在 :169 做了 `.replace("{page}", ...)`，`openHidden` 没有。文案 `title: "&d&l英语广场 - 第{page}页"`。
- 复现：查看隐身玩家主页/点击已隐身作者（`SocialListener.java:409/462/590/694` 四处调用 openHidden）→ 标题栏显示"英语广场 - 第{page}页"。
- 影响：玩家可见的占位符残留；纯显示缺陷（getGuiMsg 不 NPE）。
- 修法：openHidden 标题替换 `{page}` 为 "1/1" 或改用独立标题键。

### R11-L2 [ux·低·确定] 决斗邀请文案"点击这里"但消息不可点（全仓零 ClickEvent 组件）
- 位置：`core/duel/DuelManager.java:192-201`；`zh.yml:304` / `en.yml:304`
- 证据：`String clickableAccept = ...getGuiMsg(acceptMsgs, "duel.accept-hint"); target.sendMessage(clickableAccept);` —— 变量名与注释（"Make it clickable"）承诺可点击，实际是纯 String 消息。全仓 grep 无 Adventure `ClickEvent`/`suggestCommand` 装配。文案：`"&6点击这里或输入 &e/le duel accept &6接受挑战！"`。
- 复现：被邀请玩家按提示"点击这里" → 无任何反应（好处是"或输入 /le duel accept"给了替代路径）。
- 修法：要么用 Adventure 组件真正挂 `ClickEvent.runCommand("/le duel accept")`，要么文案删"点击这里"只留命令提示。

### R11-L3 [ux·低·中] 命令路径 `/le dialogue <场景>` 不预检 AI 可用性（GUI 路径会拦）→ 玩家进入"坏对话模式"
- 位置：`command/LearnCommand.java:332-340` vs `listener/ScenarioSelectListener.java:121-129`
- 证据：GUI 路径 `if (!plugin.getAIChatManager().isAvailable()) { ... "dialogue.ai-not-configured"; return; }`；命令路径直接 `addPlayer + startConversation + "dialogue.started"`。进入后每条消息才由 `ChatScenarioListener:137-140` 回 `"[AI] AI 服务未配置"`。
- 复现：未配 API Key 时 `/le dialogue restaurant` → 显示"AI 情景对话已开启！" → 输入任何内容 → 收到服务未配置错误。双入口语义漂移（GUI 拦、命令放）。
- 修法：handleDialogueCommand 在 addPlayer 前同款预检 isAvailable。

### R11-L4 [logic·低·中] `/le challenge` 自动弹庆祝页未 pushBack，返回键 popBack 可能消费无关旧帧
- 位置：`command/LearnCommand.java:567-569`；消费方 `listener/MiscGUIListener.java:303-315`
- 证据：`new ChallengeCompleteGUI(plugin).open(player, getWordChallengeReward());` 直开无导航帧；返回键走 `nav.popBack(...)` 弹栈顶。对照 F3-02 笑话页修复注释："直开主菜单会把这帧留成栈残留，被后续无关界面的返回误消费"——本入口正是该模式残留面（与 R8/GuiNavigationManager 交叉，若 R8 已立案不重复）。
- 复现：广场→点头像进主页（压帧）→ ESC 关主页（帧残留）→ 完成挑战后 `/le challenge` 弹庆祝页 → 点返回 → 被送回英语广场而非主菜单。
- 修法：弹页前 `pushBack(() -> new MainMenu(plugin).open(player))` 或返回键改弹到队列清空语义。

## 待验证区
1. [待验证] DailyChallenge 领取成功后 `openAsync` 自动重开界面（MiscGUIListener:251/265）——玩家领取瞬间按 ESC 想离开，界面 1-2 秒后被异步重开"拽回"。静态无法定"火候"，建议实机双开（领取+关闭）观察是否突兀。
2. [待验证] 零词库时 WordBankSelectGUI 无空态文案（`WordBankSelectGUI.java:46-66` 仅渲染有词项）：全灰玻璃界面 + 每个槽静默。触发条件（存在零词词库）能否经热重载形成需实机验证。
3. [待验证] `duel.accept-hint` 若按 L2 改为真组件，需确认 1.21.4 Paper 下 `sendMessage(Component)` 与既有多语言渲染路径兼容（现全链路 String）。

## 豁免挑战
无（E 节清单逐条对照后，无认定豁免错误项；L1 属 E5"匹配模式内部新 bug 可报"允许面）。

## 范围外发现
- 广场/榜单/动态三列表共用 28 槽 + `renderedFeedPages`/`squareGuis` 双层快照的漂移窗口（SocialListener:262-315）：现有三层容错看起来自洽，但 `/le square weekly` 直达无快照窗口内的"首次点击"会走重建分支（:302-314）——归 R4 复核。
- `PlayerDataStore.getActivePlayerIds` 混用 profileSettingsCache + 在线列表（PlayerDataStore.java:1023）：隐身过滤在两处调用一致，无发现，仅记录口径。

## 审查覆盖声明
已读（全文）：PlayerJoinListener、OnboardingManager、LearnCommand、MainMenu(+Listener)、MiscGUIListener、DailyChallengeManager/GUI、CheckInManager、ProfileGUI、AchievementWallGUI、SocialListener、EnglishSquareGUI、LeaderboardGUI、ChatScenarioListener、ChatAnswerListener、WordLearningListener、WordLearningManager、WordManagerImpl、AnswerChecker、WordBank、WordLearningGUI、WrongBookGUI/Listener、DuelManager（关键段 100-940/1240-1369）、DuelGUI/Listener、DuelChallengeGUI/Listener、ScenarioSelectListener、GuiNavigationManager、ConfirmationGUI、GUIUtils、MessageUtil、AdminCommand（100-320）、AchievementManager（240-420）、SeasonManager（380-740）、LetsLearnEnglish（220-600）、DuelGUIListener、config gui-layout/report 段、plugin.yml、zh.yml 全文/en.yml 关键段；子代理：zh/en 全键对称核对（684 键全绿——另一份扫描为 725 键，计数口径差在展平层级，均 0 缺键）、硬编码扫描。
未做完：ChatHistoryGUI/PersonalScenarioGUI/ClassBoardGUI 的旅程面细节、ReportGenerator 异步失败路径逐行、双服旅程（ProxyMessenger 面）未走——留给 R2/R9/R7 的既有覆盖。
