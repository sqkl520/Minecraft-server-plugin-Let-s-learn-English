# Changelog

## [1.0.5] - 2026-05-19

### Added
- **单词学习核心答题流程**：GUI 展示单词 → 关闭 GUI → 聊天栏倒计时答题 → 重新打开 GUI 下一题
- **回合制 Combo 连胜系统**：连续答对奖励递增（1-2次 1.0x, 3-4次 1.5x, 5-6次 2.0x, 7-9次 2.5x, 10+次 3.0x），答错/超时重置连胜
- **停止当前回合功能**：GUI 中点击 🚫 屏障方块即可立即结束会话并结算分数
- **配置文件新增** `word-system.answer-timeout` 答题倒计时配置（默认 30 秒，范围 10-120）
- GUI 答题界面新增 Combo 连胜烈焰棒展示 + 进度时钟 + 分数显示
- 主菜单全部项目支持双语切换（单词学习/对话练习/进度统计随语言变化）
- 新增 6 个语言文件键实现答题反馈全面中文化：`time-up`, `countdown-urgent`, `countdown`, `correct-answer`, `correct-answer-combo`, `wrong-answer`, `session-stopped`

### Fixed
- **严重 Bug：修复答题倒计时重叠导致题目无限循环输出**
  - 根因：异步线程调用 Bukkit 调度器 `cancelTask()` 静默失败，旧计时器未停止，新计时器叠加创建
  - 三重防护修复：`volatile boolean cancelled` 取消标志 + `AtomicBoolean submitting` 重入防护 + ChatAnswerListener 主线程调度
- **修复答对后计时器不停止**：`submitAnswer()` 和 `handleTimeout()` 完全分离，消除内部自动启动下一题导致的竞态
- **修复中文界面混合语言显示**：
  - 倒计时 `27s remaining...` → `还剩 27 秒...`
  - 正确提示 `✔ Correct! (+10) ✨ 2x combo! (1.0x)` → `✔ 正确！(+10) ✨ 2x 连击！(1.0x)`
  - 错误提示 `✖ Wrong! Answer: X` → `✖ 错误！答案是: X`
  - 超时提示 `Time's up!` → `时间到！正确答案: X`
  - 停止会话 `Session stopped!` → `会话已停止！`
- **修复语言切换 `{prefix}` 未解析**：ConfirmationGUI.applySwitch 直接读取原始文本未替换前缀占位符
- 修复切换语言后主菜单项目文字不变的问题
- 修复 GUI 物品可被玩家拿走的权限漏洞（所有插件 GUI 均取消 InventoryClickEvent）

### Changed
- **答题流程架构重构**：`submitAnswer()` / `handleTimeout()` / `buildResult()` 三方法分离，消除副作用耦合
- `WordLearningManager` 提取 `buildResult()` 消除 submitAnswer/handleTimeout 间 15 行重复代码
- `GUIMenuListener` 提取 `startAnswerForCurrentWord()` 消除 onInventoryClose/slot22 重复逻辑
- `answer-prompt` 超时时间改用配置值 `{seconds}` 注入，替代硬编码 30 秒
- `showSessionComplete` 改为使用语言文件键 + `{score}` 占位符
- `LearningSession` 新增 `addScore()` / `totalScore` 支持 Combo 加权分数
- 清理语言文件 10 个死键（`session-info`, `close*`, `word-prompt`, `timeout-message`, `answer-received`, `started`）
- 删除 `QuizState` 死构造函数、`WordLearningGUI.getComboLabel` 未使用参数
- `maven-clean-plugin` 配置保护 `*.jar`，以后 `mvn clean` 不再删除旧版本
- 代码审计：`buildResult()` 消除 could 冗余、`cancelAnswerTimer` 双重调用精简、finally 块统一管理线程锁释放

## [1.0.4] - 2026-05-17

### Added
- 新增中英双语 README（顶部带语言切换链接）
- 新增 README 顶部开发警告提示
- 新增 `CHANGELOG.md` 版本更新日志

### Fixed
- 修复语言切换确认界面两次都显示同一种语言的问题：第一次英文"Really?"，第二次中文"真的吗？"

### Changed
- 构建时自动清理 maven-status 缓存，避免重复编译冲突

## [1.0.3] - 2026-05-14

### Added
- 主菜单左下角新增中英文切换按钮
- 切换语言需要两次确认
- 新增 `en.yml` 和 `zh.yml` 双语消息配置文件

### Changed
- 构建输出目录改为上级 `target/`，保留历史版本 JAR

## [1.0.1] - 2026-05-14

### Fixed
- 修复 GUI 菜单中颜色代码（`&7`、`&6`、`&l` 等）显示为原始文本的问题
- 修复 `/le menu` 等命令只发送文字消息没有打开 GUI 的问题
- 修复多处编译警告和空指针隐患

## [1.0.0] - 2026-05-14

### Added
- 初始版本发布
- 单词学习系统（基于艾宾浩斯遗忘曲线复习）
- 情景对话练习（餐厅、机场、购物等场景）
- 学习进度追踪与成就系统
- GUI 图形化菜单
- SQLite 数据库支持
- 管理员命令系统