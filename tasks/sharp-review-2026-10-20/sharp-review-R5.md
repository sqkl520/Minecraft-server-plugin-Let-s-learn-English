# 锐评 R5 · 技术架构

> 2026-10-20 战略锐评 · 架构师视角 · 只读 · agentId a4fe859268abc7810

## 1. 一句话判决

这是一棵"3.3 万行、以主类为唯一枢纽的 Bukkit 单例挂单树"：分层在文档里（DEPLOYMENT_WIKI.md:51-73），不在代码里——零接口、全员反向依赖主类、并发与双写一致性全靠注释约定撑着喵。

## 2. 致命伤 Top3

**① 分层是装饰，不是契约。** 全项目生产代码可数的 `interface` 只有两个嵌套回调（AIChatManager.java:1519、FeedTextFormatter.java:132）；`WordManagerImpl`/`ProgressManagerImpl` 有 Impl 之名、无接口可 Impl。88 个文件中 **67 个** import 主类、**67 个**构造器接收 `LetsLearnEnglish plugin`（grep 实测），LearnCommand.java 一个命令类链式调用 `plugin.getX()` **174 次、触达 20 种依赖**，command→data 直捅 `getDatabaseManager()`；GUI 层裸查库（ChatHistoryGUI.java:162,365、EnglishSquareGUI.java:285，gui 包 6 处）。依赖没有消失，只是被 service locator 藏进运行期，编译期查不出任何环。

**② 并发与状态一致性只靠纪律维持。** DatabaseManager 把内部监视器原样交出（`getConnectionLock()`，DatabaseManager.java:2955），类 javadoc 自己盘点 PlayerDataStore 8 处、DailyChallengeManager 8 处、AchievementManager 3 处等"25 处裸 JDBC 点"（:2950-2953），实测 DatabaseManager 之外 8 个文件、40 处手写 `synchronized(getConnectionLock())`。PlayerDataStore 持 **14 张 UUID→Map 缓存**（:43-72），真相来源在三套语义间漂移：部分计数"先改缓存+即时绝对值 UPSERT"（:244-251）、部分"miss 时同步回读 DB 真源"（:174-190）、主体"每分钟全量清扫落盘、无 dirty flag"（LetsLearnEnglish.java:389-397）。join 在主线程同步跑 3 条 SQL（PlayerJoinListener.java:26-31），改语言在主线程全量重写 player_lang.yml（LetsLearnEnglish.java:891-894）。全项目 70 处调度提交、25 处异步、线程断言仅 4 处——边界全靠人自觉喵。

**③ GUI 是用"标题中英子串 contains"路由的字符串状态机。** 11 个 listener 文件重复 `event.getView().getTitle().contains("English Square")||contains("英语广场")` 式判定（SocialListener.java:130,143-144、ChatHistoryListener.java:82-85、ScenarioSelectListener.java:30-31），GUIUtils.isPluginGUI（GUIUtils.java:76-80）再抄一份全量标题清单，注释自认"标题改名不同步=音效静默丢失"。核心玩法"聊天栏拼写"挂 4 个 AsyncPlayerChatEvent 处理器，任何接管/取消聊天框的插件都会静默杀死玩法喵。

## 3. 分条炮击（附重构方案）

**onEnable = 人脑拓扑排序。** LetsLearnEnglish.java:150-254 顺序 new 约 27 个对象，顺序即生死（:184-186 注释特意规定 OnboardingManager 必须先于 registerListeners 构造），三处分散的启动异步块依赖"此时 DB 已初始化"这种口头契约。全项目 `new` 442 处/154 类。手写 DI 的代价直接反映在测试上：src/test 仅 8 文件 754 行 vs 主代码 32,925 行，jacoco 门禁压到 0.01，pom 注释自嘲真实覆盖 ~4%"多数代码与 Bukkit 耦合无法测"。重构：不引 Spring，手写 `Phase.init(order)` 注册表 + 把 `PluginContext` 接口化，每个 manager 只注入真依赖（WordLearningManager 已注入 10 种服务，说明作者试过，但最终还是绕回 plugin.getX() 全域）。

**事件驱动是表象，轮询是底色。** 无 PlayerTickEvent 轮询（grep=0）算幸存，但每秒 timer 三处重复实现倒计时（WordLearningManager.java:503、DuelManager.java:566,889），pitch 公式 `1.0f+(N-rem)*0.15f` 逐字抄了两份（:500 vs :562）；每 60s 轮墙钟判断"现在是不是提醒小时"（LetsLearnEnglish.java:442-464）；每 180s 扫库查复习（:399-403）；每分钟全员全量 UPSERT（:389-397）。方案：单一到期堆（expiry heap）调度服务，ActionBar/音效/BossBar 做订阅者；保存改 dirty-set 增量。

**加一个"语法关"要动几个文件？** 新 GUI + 新 listener（或塞进 430 行的 MiscGUIListener）+ isPluginGUI 加串 + LearnCommand 20 个 case 的 switch 加分支 + PlayerDataStore 缓存/读/存/卸四路 + DatabaseManager 建表并手工同步 schema.sql（两份 21 张表 DDL 双写）+ zh/en 两份 messages + 成就枚举（Achievement.java 硬编码 22 常量）+ achievements-config + ReportGenerator 口径 + 主类接线——约 12 个文件，且漏任何一步都只在运行期暴雷，无编译器兜底。这是抽象缺失型复制粘贴的直接税单喵。

**API/SPI 两面评价。** `api/LetsLearnEnglishAPI.java` 是 83 行伪门面：构造器静态自赋值（:31-36）、直接暴露 WordManagerImpl/PlayerDataStore 等可变实现、零扩展事件——第三方可"调用"不可"扩展"。真正的好 SPI 在内容侧：words/*.yml 自动发现（WordManagerImpl.java:67）+ WordBankValidator + HotReloadManager 热载，场景走 ai_scenarios.yml/player_scenarios/——数据外挂这条线及格，代码扩展这条线为零。

**平台假设风险。** 编译绑 paper-api 1.21.4-R0.1-SNAPSHOT 且两处直接 import net.kyori Adventure（AchievementManager.java:7-9、OnboardingManager.java:9-10），wiki 却宣称 Spigot 1.21+ 可跑——Spigot 下 NoClassDefFoundError；VersionAdapter 号称支持 1.18-1.21（VersionAdapter.java:14）实为表演。Vault provider 只在 onEnable 抓一次（LetsLearnEnglish.java:771-780），前置经济插件晚加载/reload 即永久失效。ProxyMessenger 靠"随便找个在线玩家"发 plugin message（ProxyMessenger.java:144-165），零在线玩家时代理广播静默丢。决斗 setScoreboard 抢占玩家计分板（DuelManager.java:353）与同类插件无协调。无任何 bStats/指标。统计 HTTP 默认密码 changeme（好在仅告警且默认绑 127.0.0.1，StatisticsHttpServer.java:56-67）。正面：无世界/传送假设、全程 UUID 对离线模式安全喵。

## 4. 说句公道话

工程素养并非缺席：午夜 rollover 自递归调度抗 DST（LetsLearnEnglish.java:543-657）、备份改 VACUUM INTO + 独立连接 quick_check（:681-733）都高于插件圈平均水准；"绝对值 UPSERT 使计数与 quit 监听器顺序无关"（PlayerDataStore.java:236-250 注释）说明作者真实思考过一致性，只是选择了用注释而非类型强制；F2-11/R2-H1 式的两段式主线程/异步分工纪律遍布全库；内容 yml 外挂是正确决策。病根不是能力，是每个缺陷都被点止血、从不付重构债喵。

## 5. 只改一件事

把"标题子串路由 GUI"改成注册制视图：定义 viewKey→handler 映射挂进现成的 GuiNavigationManager，点击判定改 inventory holder 标识，废除 11 个 listener 的 contains 与 GUIUtils 全量清单。一次改动同时消灭静默丢音效、i18n 标题撞名、第三方同名箱子被误接管三类事故，并倒逼各层长出第一批接口——这是这份代码里杠杆率最高的第一块多米诺骨牌喵。
