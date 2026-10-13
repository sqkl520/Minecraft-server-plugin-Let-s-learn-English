# Phase 3 施工跟踪

- 基线： commit fb9604b（规划期即修 7 项实伤 + 八域规划已提交）
- 总纲： tasks/phase3/plan-master.md（4 波施工顺序）；各域细节以 plan-*.md 为准
- 纪律： 每波内多流并行（文件白名单严格互斥）；编排者统一 fan-in（i18n/config-version/CHANGELOG）；每波三维 tester + `mvn clean verify` + commit

## Wave 1（9 流并行，文件所有权严格互斥）

| 流 | 域 | 白名单文件 | 任务来源 |
|----|----|-----------|---------|
| S-A 数据层 | 1+3+7 共用 | DatabaseManager、PlayerDataStore、schema.sql | plan-onboarding §3.2 / plan-achievements §1.2+§3.2(H8/H11/H12) / plan-audio-feedback §3.1 |
| S-B 音效底座 | 7+3 | SoundManager、VersionAdapter、core/feedback/CountdownTier(新) | plan-audio-feedback §3.2/§3.3 / plan-achievements H16 |
| S-C 配置门 | 8 | ConfigManager、config.yml | plan-menu-config 1.(b)/2.4（仅 MC-2 部分+域7 config 段） |
| S-D 成就定义展示 | 3 | Achievement.java、AchievementWallGUI、LLEPapiExpansion、achievements-config.yml、新测试 | plan-achievements US-ACH-1/4 |
| S-E 成就管理器广播 | 3+8 | AchievementManager、ProxyMessenger | plan-achievements US-ACH-3/2.4 广播 / MC-2 成就门 |
| S-F 会话核心 | 1+7+3+8 | WordLearningManager、WordManagerImpl、ChatAnswerListener | plan-onboarding US-ONB-3 / plan-audio-feedback 3A-1/3A-2 / plan-achievements H7 / MC-2 word 门 |
| S-G 引导核心菜单入口 | 1+8 | OnboardingManager(新)、LetsLearnEnglish、PlayerJoinListener、MiscGUIListener、LearnCommand、ProfileGUI | plan-onboarding US-ONB-2/3 / MC-2 命令与 GUI 门 |
| S-H AI与挑战门 | 8 | AIChatManager、ChatScenarioListener、DailyChallengeManager、RewardEngine、ReviewScheduler、EnglishSquareGUI、SocialListener | plan-menu-config MC-2（ai/daily/leaderboard/rewards 门 + F1-19 语义） |
| S-I 对决与音效盲点 | 7+8+3 | DuelManager、ScenarioSelectListener、WrongBookListener | plan-audio-feedback 3A-3/3A-5 / MC-2 duel 门 / plan-achievements H9 |

Wave 1 冻结接口：见各流提示词内清单（数据层/音效/配置门/成就/引导管理器）。

## Wave 2（待 Wave 1 提交后）
- 域 4 广场社交（SocialManager 全新 + 2 新表 + 广场动态页）
- MC-1 主菜单 9 按钮 + DuelChallengeGUI
- MC-3 modes 接线（WordModes + AnswerChecker 参数化 + QuizState 快照）
- MC-5 挑战经济参数接线（challenges-per-day/rewards.* + F1-12 配套）
- 域 6 家长报告

## Wave 3（待 Wave 2 提交后）
- 域 2 AI 反馈闭环（ChatSessionState 重构 + 新表 ai_chat_feedback）
- 域 5 批次 A 赛季+跨服榜
- MC-4 个人场景管理（PersonalScenarioStore 抽取 + 2 新 GUI）
- MC-6 语义终案收尾（裸 getString 二期 + ExportResult 协议化；F1-19 已在 Wave 1 提前落）

## Wave 4（收尾）
- 域 5 批次 B 班级
- MC-7 死键/死类终局大扫除（必须在全部接线落盘后）
- 3B 资源包（默认不做，待用户拍板）

## 收尾（用户点名）
- 验收 + 锐评 agent（数路并行）
- 全部相关文档更新（README / CHANGELOG / tasks/ / dev-findings / 规划文档状态回写）

## 状态
- Wave 1（9 流）进行中：S-A ✅ / S-B ✅ / S-C ✅ / S-D ✅ / S-E ✅ / S-F ✅ / S-G 运行 / S-H ✅ / S-I ✅
  - S-A：四新列 + 存取方法全部落盘（SQLite 真库冒烟过）；冻结签名：get/setOnboardingStep、setWrongBookCleared、increment/getWrongBookCleared、isSoundsEnabled
  - S-B：SoundManager 五扩展 + Adventure ActionBar + CountdownTier；兄弟流已消费其冻结接口（playSoundByName/tierOf/sendActionBar）
  - S-C：11 门方法 + config.yml 四新段（272 旧键真解析零改动、24 新键全在白名单）；全仓 javac EXIT=0（77 文件）
  - S-D：22 成就 + 墙五段式渲染 + PAPI 动态位 + AchievementDefinitionTest 7/7；FIRST_WORD 描述裁决=保持原句（判定已修，描述重吻合）
  - S-E：tryUnlock 六段重构（模块门/短路/前置守卫/写库/主线程化通知）+ 四新判定 + 三冻结签名落盘 + 广播双语 per-recipient（顺带修复 {prefix} 字面外泄）；**title 走 Adventure Title（String 版已废弃——tester 按此验收）**；**checkHelpAchievement 为 TODO stub，Wave 2 域 4 落 getSocialManager 后须接线（记入 Wave 2 待办）**
  - S-F：tutorial 全链（错答守卫落 else 首行、同题重试闭环、超时不推进）+ 倒计时 ActionBar 化 + 连击 pitch + 里程碑 + 练习成就挂钩 + word 门；**遗留两待核实项（等 S-G 报告核对）**：(1) LearnCommand.startWordQuiz/MiscGUIListener 调 startQuiz 后未查 null（门关闭时可能开空 GUI）；(2) interceptQuizStart 在 word 门关闭时 startTutorialQuiz 返回 null 但 S-G 侧未判返回值
  - S-I：BossBar 倒计时（runTaskTimer + 三条自清理路径 + 双参 remove 防误摘新任务）+ 清理矩阵八路推演（含 stale 交错窗口）+ 决斗门 + 首胜成就挂钩 + 音效盲点三处；timeout 音切新键（默认跟随 incorrect 听感零变化）
  - S-H：20 个门落点全清单（AI×4 / 挑战×8 / 奖励收口 / ReviewScheduler / 榜单×4 渲染+点击双汇点）；`RewardEngine.isMoneyEnabled` 改为 public 且并集语义（isRewardsEnabled && money.enabled）；**上报三条 money.enabled 直读缺口（fan-in 收口）**：(a) LearnCommand:534-543 isEconomyAvailable 直读；(b) MiscGUIListener 领取分支只读 money.enabled 不查伞；(c) CheckInManager:140-142 直读
  - S-G 运行（核心已落盘，等报告）

## fan-in 待办清单（编排者，全部流回流后执行）
- [ ] **word 门缺口 A**（S-F 上报已核实）：`MiscGUIListener.handleWordBankSelect` 主菜单→词库选择→点击路径无 word 门（main menu 按钮 Wave 2 才隐藏）——门关闭时 startQuiz 返回 null 后仍开空 GUI + 发 session-start。修法：方法顶加 `isWordSystemEnabled` 门（存档 module.disabled）。**注意该文件属 S-G 白名单，须等 S-G 回流后改**
- [ ] **word 门缺口 B**（S-F 上报已核实）：`OnboardingManager.interceptQuizStart` 对 `startTutorialQuiz` 返回 null 未判（只 catch 异常）——word 门关闭时教程 UI 会先开。修法：调用后判 null → return false（调用点不复核时兜底）
- [ ] `LearnCommand.startWordQuiz` 调 startQuiz 后补 null 防御检查（子命令入口已有门，纯纵深）
- [ ] **Wave 2 待办**：`AchievementManager.checkHelpAchievement` 接线（域 4 落 `getSocialManager` 后替换 TODO stub）
- [ ] **money 收口三处**（S-H 上报）：LearnCommand.isEconomyAvailable 改调 `getRewardEngine().isMoneyEnabled()`；MiscGUIListener 领取分支同改；CheckInManager.isMoneyDisabledByConfig 同改（等 S-G 回流后统一改，涉及 S-G 白名单文件）
- [ ] Wave 1 → fan-in（i18n ~60 键 + config-version 12 + CHANGELOG）→ tester×3 → verify → commit
- [ ] Wave 2 → 同上
- [ ] Wave 3 → 同上
- [ ] Wave 4 → 同上
- [ ] 验收+锐评 → 文档更新
