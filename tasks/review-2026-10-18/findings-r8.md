# findings-r8 玩家命令+菜单导航（审查 Agent 产出 · 第 2 轮续任，含前任核验成文）
- 审查范围：command/LearnCommand.java、gui/MainMenu.java、listener/MainMenuListener.java、listener/MiscGUIListener.java、gui/GUIUtils.java、gui/ConfirmationGUI.java、core/GuiNavigationManager.java（+ 缝线侧取证：SocialListener、PlayerJoinListener、ScenarioSelectListener、ChallengeCompleteGUI、ConfigManager）
- 审查时间：2026-10-18
- 结论摘要：高0条 / 中1条 / 低4条 / 待验证2条 / 豁免挑战0条
- 承接说明：前任（第 1 轮）已核结论标注 [前任核验采纳] 收于本文；其遗留的核验 1/2/3 已全部完成取证并成文（见 L2/M1/L1）
- 备注：本文件由编排者按其返回全文原样保存（harness 禁止子代理写文件）

## 高危
无（本域按 rubric 无高危；M1 的影响面广但均有可退出路径与数据无损，维持中危）

## 中危
### R8-M1 [logic·中·高] 返回栈残留帧会被无关界面的返回键消费：直开界面（庆祝页 / /le profile 主页）的返回键恒弹"不属于它"的栈顶帧（R11-L4 合并立案）
- 位置：机制本体 `core/GuiNavigationManager.java:29-31, 51-61`；无 push 配对的 pop 消费点 `listener/MiscGUIListener.java:303-315`（庆祝页返回）、`listener/SocialListener.java:429-433`（主页返回，命令直开路径）；直开点 `command/LearnCommand.java:568-569`（庆祝页）、`command/LearnCommand.java:451-453`（/le profile）；残帧来源：ESC 关闭子页不弹帧（全仓无 InventoryCloseEvent 与 nav 栈对账，PlayerJoinListener.java:116 仅 quit 时 clearPlayer）
- 证据：
  ```java
  // GuiNavigationManager.java:51-61 —— popBack 语义：无条件弹任意栈顶
  public Runnable popBack(UUID playerId, Runnable fallback) {
      Deque<Runnable> deque = profileBackActions.get(playerId);
      if (deque != null && !deque.isEmpty()) {
          Runnable action = deque.pop();
          if (deque.isEmpty()) profileBackActions.remove(playerId);
          return action;
      }
      return fallback;
  }
  // LearnCommand.java:568-569 —— 庆祝页直开，未 push 任何来源帧
  new com.letslearnenglish.minecraftplugin.gui.ChallengeCompleteGUI(plugin)
          .open(player, getWordChallengeReward());
  // MiscGUIListener.java:311-314 —— 庆祝页返回键却 pop（消费别处的帧）
  GuiNavigationManager nav = plugin.getGuiNavigationManager();
  Runnable backAction = nav.popBack(player.getUniqueId(),
          () -> new MainMenu(plugin).open(player));
  backAction.run();
  ```
  push 配对核对（全量 grep，函数级）：有 push 的入口 = 广场头像 SocialListener:329/400、榜单头像 :578、动态右键 :682、主页→日历 :424、主页→成就墙 :458、主页→设置 :473、管理页 ScenarioSelectListener:209、主菜单→每日挑战 MainMenuListener:148。pop 消费点 = MiscGUIListener:88/181/198/312、SocialListener:229/431/484、PersonalScenarioListener:62。**其中仅"庆祝页（MiscGUI:312）"与"命令直开主页（LearnCommand:451/444 → SocialListener:431）"两个消费点永无自己的配对 push**；其余 pop 点配对成立，但会被"任意 ESC 关闭造成的残留帧"污染（弹错层）。
- 复现（命令路径，无需挑战，最干净）：
  1. /le square → 点任意头像进主页（SocialListener:400 push 「广场帧」）。
  2. 按 ESC 关闭主页 —— 无任何 close 钩子弹帧，「广场帧」残留。
  3. 执行 /le profile（LearnCommand:451-453 直开，无 push）。
  4. 点主页返回键（SocialListener:431 popBack）→ 弹出残留「广场帧」→ 玩家被送到英语广场。
  预期（无残留时）：fallback 主菜单。实际：回到几分钟前访问过的广场；屏幕路径与栈完全脱节。
- 复现（庆祝页路径，与 R11-L4 同场景，本条从栈机制本体报告、R11-L4 从 /le challenge 入口报告——**建议汇总时合并为一条**）：广场→头像进主页（push）→ESC 关（残留）→完成每日单词挑战后 `/le challenge` 弹庆祝页（LearnCommand:568 直开）→点庆祝页 slot 8/22 →弹出残留「广场帧」→被送回广场而非主菜单（MiscGUIListener:303-315 实读原文 `if (slot == 8 || slot == 22)` 均走 popBack）。
- 通用化：任意"主页→日历/成就墙/设置后 ESC 关闭"都会在栈底留一帧；之后**任意** pop 消费点（含笑话页返回 SocialListener:229）都可能先弹这枚陈旧帧，表现为返回键"原地重开旧页"或"跳去无关页"。
- 影响：全程无数据损失；玩家侧表现为返回键行为不可预测（丢失对"返回=回来源"的心智模型），多级错位时可达 2-3 次返回才到预期页。
- 修法：(1) 最小修复——LearnCommand:568 弹庆祝页前补 `pushBack(player.getUniqueId(), () -> new MainMenu(plugin).open(player))`（与 MainMenuListener:148 每日挑战同模式），/le profile 直开同理补主菜单帧或改 fallback 语义；(2) 根治残帧——push 引入页面标识键（pushBack(key, action)/popBack(key, fallback) 只弹匹配键），并配合"打开子页时替换同键旧帧";(3) 存量缓解——打开带返回键的子页前清理同键残留帧。

## 低危
### R8-L1 [data·低·确定] ConfirmationGUI.confirmStages 在 quit 时无清理（核验 3 成文）
- 位置：`gui/ConfirmationGUI.java:32, 81-83, 133-141`；清理清单缺口 `listener/PlayerJoinListener.java:104-129`
- 证据：
  ```java
  // ConfirmationGUI.java:32 —— 静态 map，仅两个移除点
  private static final Map<UUID, ConfirmState> confirmStages = new ConcurrentHashMap<>();
  // ConfirmationGUI.java:81-83 resetStage —— 唯一显式清理口
  public static void resetStage(Player player) { confirmStages.remove(player.getUniqueId()); }
  // PlayerJoinListener.java:116 —— quit 编排点清了 nav 栈（R-41），但清单里没有 ConfirmationGUI
  plugin.getGuiNavigationManager().clearPlayer(playerId);
  ```
  resetStage 的全部调用点 = MainMenuListener:220（取消键）与 applySwitch（133-141，确认切换时 remove）。quit 无任何调用。
- 复现：任意玩家点主菜单语言按钮进二确页 →（不点取消/确认，或点确认后在 2 tick 窗口内下线）→ 玩家 quit。confirmStages 中该 UUID 条目永久残留（每玩家至多 1 条，量级小）。重进后被覆盖保护：所有进入二确的路径（open/openSecond，ConfirmationGUI:124）都会 put 新 state；handleConfirmGUI 仅在该标题打开时可达。故无用户可见错误路径，定性为泄漏 + 极端时序陈旧态（F6-01 的 runTaskLater 有 isOnline() 守卫，不误开第二页）。
- 影响：内存泄漏（至多 1 条/离线玩家，永不释放）；与 R-41（nav 栈已清）清理标准不一致。
- 修法：PlayerJoinListener.onPlayerQuit 增加 `ConfirmationGUI.resetStage(event.getPlayer());`（该事件内已有各服务 unload 收口点，语义一致）。

### R8-L2 [ux·低·中] MiscGUIListener:161 startQuiz 未包 try-catch：空词池窗口下点击词库异常冒泡出事件处理器（与 R11-M2 同位置，**合并**）
- 位置：`listener/MiscGUIListener.java:161`
- 证据：
  ```java
  // MiscGUIListener.java:158-166 —— interceptQuizStart 内部控制流后直接调用，无 catch
  if (plugin.getOnboardingManager().interceptQuizStart(player, difficulty)) return;
  var session = plugin.getWordLearningManager().startQuiz(player, difficulty, mode);
  if (session == null) return;   // 只判 null，不防抛
  // 对照 LearnCommand.java:252-277 —— 命令路径 Catch IllegalStateException → word.no-words-available
  } catch (IllegalStateException e) {
      player.sendMessage(plugin.getMessageUtil().getPlayerMessage(player, "word.no-words-available"));
  }
  ```
- 复现：玩家打开词库选择界面（渲染时该词库 >0 词）→ 管理员在玩家点击前 `/lea reload` 且词档被清空（词库注册条件 WordManagerImpl.loadAllWordBanks:87 `getWordCount()>0`）→ 玩家点击词库 → `selectWordsForSession` 空池抛 IllegalStateException → 冒泡出 InventoryClickEvent 处理器（玩家零反馈 + 控制台堆栈）。窗口极窄（需 reload+空白词档），正常路径不可达（渲染/点击同源 getAvailableDifficulties）。
- 影响：窄窗口内点击静默失败（无提示）、控制台无优雅降级。
- 修法：抄 LearnCommand.startWordQuiz 的 catch 对（IllegalStateException→word.no-words-available；IllegalArgumentException→general.error 包 message），或把 161-166 整段纳入 try。

### R8-L3 [config·低·确定] gui-layout 槽位配置无边界校验：越界值令 MainMenu.open 抛异常、同槽值令渲染/点击错位
- 位置：`config/ConfigManager.java:381-383`（读取无校验）、`gui/MainMenu.java:43-48`（只校验 size 不校验 slot）、`gui/MainMenu.java:77-142` 与 `listener/MainMenuListener.java:119-207`（渲染 setItem 顺序 vs 点击 if-else 顺序）
- 证据：
  ```java
  // ConfigManager.java:381-383 —— 直读无 clamp
  public int getGuiSlot(String guiName, String itemKey, int defaultSlot) {
      return mainConfig.getInt("gui-layout." + guiName + "." + itemKey + ".slot", defaultSlot);
  }
  // MainMenu.java:43-48 —— 注释自称深知越界即崩（AIOOBE），但校验只覆盖 size
  int size = plugin.getConfigManager().getMainConfig().getInt("gui.menu-size", 27);
  if (size < 27 || size > 54 || size % 9 != 0) { ...; size = 27; }
  // MainMenu.java:77-78 —— 槽位直接进 setItem，无 [0,size) 校验
  menu.setItem(cm.getGuiSlot("main-menu", "word-learning", 11), createMenuItem(...));
  ```
- 复现：管理员拟 `gui-layout.main-menu.duel.slot: 90`（手误）→ 任意玩家 /le menu → MainMenu.open 在 `menu.setItem(90, ...)` 抛越界异常，菜单打不开、控制台报错（对全服玩家，直至改回配置）。另：两个按钮配同一 slot（如 word 与 dialogue 都 13）→ 渲染侧后写者覆盖（dialog 图标可见），点击侧 if-else 先命中 word 分支（MainMenuListener:119 先判）→ 显示与行为漂移，无任何配置告警。
- 影响：管理员误配后菜单对所有玩家崩溃 / 静默错位；无既有防护。
- 修法：getGuiSlot 加 clamp `Math.max(0, Math.min(slot, size-1))`，或 MainMenu.open 在 size 校验块内对该菜单全部 9 个槽位统一校验（越界 WARNING + fallback 默认值），并顺带检测同槽重复告警。

### R8-L4 [quality·低·中] MiscGUIListener 标题判定是全域唯一仍用裸 contains 的一点，与 Phase 3"锚定前缀/等值"约定不对称（无现行互撞，维护性风险）
- 位置：`listener/MiscGUIListener.java:48-52`（点击）与 `:333-337`（拖拽），两处白名单逐字一致
- 证据：
  ```java
  // MiscGUIListener.java:48-52 —— 裸子串 contains（最宽匹配）
  if (!title.contains("Privacy Settings") && !title.contains("隐私设置")
          && !title.contains("Select Word Bank") && !title.contains("选择词库")
          && !title.contains("过往学习") && !title.contains("Study History")
          && !title.contains("Daily Challenge") && !title.contains("每日挑战")
          && !title.contains("Challenge Complete") && !title.contains("挑战完成")) { return; }
  // 对照：ConfirmationGUI.java:63-74 已改配置等值；MainMenuListener.java:90-104 配置等值+长度下限前缀；ClassBoardGUI 锚定前缀
  ```
  误伤面逐对核验结果（当前标题全集对照 zh/en.yml）："每日挑战"（daily-challenge.title）与"今日单词挑战"（word-title）不互为子串；"挑战完成！"（complete-title）与"每日挑战"互不命中；"过往学习"与"对话历史/AI 对话历史"互不命中；en 侧 `title.contains("Daily Challenge")` 会命中复数的 "Daily Challenges"（同一 GUI，无害）。**结论：现阶段零实际互撞**；真实风险在 P2-01 既有约束上再加一层——GUIUtils 类注释已自认"标题改名后白名单不同步 = 静默失效"，而 MiscGUIListener 若未来出现含上述词的其它标题（如"每日挑战排行榜"）会**误捕获**（cancel + 按错误 slot 表分发），危害方向与其它判定的"漏匹配"相反。
- 修法：向 ConfirmationGUI.isConfirmGUI 的配置等值模式收敛（本 5 类标题均可从 messages 配置读取比对），或至少把判定改为前缀锚定 + 注释红线的模式（与 DuelChallengeGUI 标题避碰红线同款）。无实伤，可排期随其它 fan-in 处理。

## 待验证区
1. [待验证] MainMenu 越界槽位（R8-L3）在 Paper 1.21.4 下的确切异常类型：MainMenu.java:39 注释称 ArrayIndexOutOfBoundsException，Bukkit 实际可能抛 IllegalArgumentException("Slot out of range")——影响仅异常文案/修法细节，建议单测或实机以 `gui-layout.main-menu.word-learning.slot: 90` 验证一次。
2. [待验证] R8-M1 两条复现链已静态全通（push/pop 行号逐一核过、无守卫拦截），但 2 tick reopen（MainMenuListener:243）与客户端 ESC 行为在真实服务器上的叠加时序建议由 testharness 实机走一遍（序列 b：/le square→头像→ESC→/le profile→点返回；序列 a：同 R11-L4），确认弹帧最终落点与预期一致。

## 豁免挑战
无。E5 的 B3（标题匹配内部新 bug 可报）已在 R8-L4 合法面内使用；E21（命令直开 GUI 音效白名单）——本次核过 /le challenge 直开庆祝页有 `SoundManager.playSound(player, "complete")`（ChallengeCompleteGUI.java:120）且 GUIUtils.isPluginGUI 含"挑战完成"（:91-92），不涉挑战。

## 范围外发现
- [quality·归 R13] `listener/SocialListener.java:503` 方法签名与首行语句同行挤压：`private void handleLeaderboardGUI(Player player, int slot) {        GuiNavigationManager nav = plugin.getGuiNavigationManager();`——疑似多轮合并残留的格式劣化，仅为卫生问题。
- [logic·归 R4/R11 交叉] 广场返回键（SocialListener:319-322）直开主菜单不入栈——与全域"子页返回走 pop"的语义不一致（此处是设计选择，但与 R8-M1 同族：反向确认"哪些返回键该弹、哪些该直开"应有一张统一契约表）。

## 正面清单（本域已核验为正确 / [前任核验采纳]）
- [前任核验采纳] LearnCommand 20 子命令四方一致：switch（:94-159）↔ SUBCOMMANDS（:52-54）↔ help 列表 ↔ README 命令表，无隐藏命令/孤儿帮助；本次逐 case 复核 20=20 一致（另抽验：无参→主菜单 :87-90；控制台保护 :82-85 回 general.player-only；未知子命令 :156-159 回 general.unknown-command；/le square 非法模式静默落默认 :409-418；/le profile 不存在玩家 :446-450 回 error.player-not-found；/le word 大小写归一化 :187 + resolveDifficulty :230-237 + 模式两级校验 isKnownMode/isEnabled :196-205——全部健全）。
- [前任核验采纳] MainMenu 9 按钮渲染/点击同源无漂移：本次抽验 9 对全部同键同 fallback——渲染 MainMenu:77/80/82/88/95/111/118/130/140 ↔ 点击 MainMenuListener:119/127/135/138/141/151/161/164/195。
- [前任核验采纳] 模块门渲染+点击全在位（D11 复核，含僵尸兜底）：LearnCommand requireEnabled 12 处（:171/283/366/424/458/649/655/660/685/700/708 + 拒时回 module.disabled）、MainMenu 渲染门 6 处（:76/79/94/110/125/135）、MainMenuListener 点击侧门（:118-124/128-132/142-146/154-158/178-185/199-204）、MiscGUIListener 词库门 :121 + 行为挑战领取门 :190（关模块后的迟到点击整体拒绝）——均有门有回显，无后门。缝线 3（模块门点读 vs 在途事件）从本域看：门全部点读 ConfigManager、点击瞬间判定，无缓存窗口，语义成立。
- D26 音效盲点三处回归（实读原文在位）：ScenarioSelectListener:122-126（AI 未配置 → incorrect+close+msg）、:216-220（无权限 → incorrect）、WrongBookListener:163-165（handlePractice → menu-click）。
- D22 wrong-book 键：zh/en 双侧 `main-menu.wrong-book`/`wrong-book-lore`（388-389）与 wrong-book 节（630）均存在。
- MiscGUIListener drag guard（:328-348）与 click 白名单（:48-52）逐字一致（5 类），上半区模式正确。
- MainMenu.size 校验（:43-48）本身正确（27/36/45/54 白名单 + WARNING 回落）；ConfirmationGUI 全套（isConfirmGUI 配置等值、F6-01 2-tick 守卫含 isOnline+stage+targetLang 三重校验、R-09 目标语言落盘）与 MainMenuListener.handleConfirmGUI 实读无缺陷。
- PlayerJoinListener quit 编排（:104-129）覆盖 nav 栈/签锁/reward/onboarding/social/AI summary 等共 10 项清理（缺口仅 ConfirmationGUI，见 L1）。

## 审查覆盖声明
- 全文读：GuiNavigationManager、MiscGUIListener、ConfirmationGUI、MainMenu、MainMenuListener、ChallengeCompleteGUI、GUIUtils（7 文件）。
- 节选读：LearnCommand（:44-283、:397-619，覆盖全部 cabinet 分发、word/dialogue/session 启动、square/profile/challenge 全段）、SocialListener（:195-431、:480-534）、PlayerJoinListener（:95-131）、ConfigManager（:370-395）、ScenarioSelectListener（:105-229）、WrongBookListener（:150-175）、plugin.yml、zh.yml/en.yml 关键键面。
- 未做完（诚实申报）：LearnCommand 剩余段（:625-1151：duel/wrong/checkin/season/class/scenario/history/tab 补全细节）未逐行；Tab 补全 :1067-1151 未逐项；README 对照采纳前任结论未复读原文；PersonalScenarioListener 仅节选 pop 点（:61-62）；ClassBoardGUI/其它 listener 标题判定未扩面抽验（不属本域文件）；实机验证未做（本文全部结论为静态取证）。
