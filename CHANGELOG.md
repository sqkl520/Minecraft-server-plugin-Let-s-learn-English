# Changelog

## [1.0.5] - 2026-05-19

### Added
- **版本更新检测**：启动时自动检查 GitHub Releases 获取最新版本，OP 玩家加入时通知更新
- **单词学习答题系统**：GUI 展示单词 → 关闭 GUI → 聊天栏倒计时答题 → 自动进入下一题
- **回合制 Combo 连胜系统**：连续答对产生连击倍率（1-2连击1.0x → 10+连击3.0x），答错/超时重置
- **停止当前回合**：GUI 第 16 格屏障方块按钮，点击立即结束并结算分数
- **数据备份**：自动定时备份数据库（默认每 1440 分钟），保留最多 7 份
- **每日学习提醒**：可配置多个提醒时间点（默认 9/14/20 点），新增玩家进服时发送学习鼓励消息（`on-join` 配置项）
- **消息前缀可自定义**：在 `config.yml` 中 `prefix.zh` / `prefix.en` 分别配置中英文消息前缀，热重载生效
- **GUI 音效系统**：菜单打开/点击/答题正确/错误/完成均有音效反馈
- **数据库 UPSERT 优化**：INSERT OR REPLACE 替代 DELETE+INSERT，写入量减少 50%
- **配置文件大幅扩展**：新增 backup/daily-reminder/gui.sounds/difficulty-settings 等配置项
- 答题超时时间可配置（`word-system.answer-timeout`，范围 10-120 秒）

### Changed
- **全面 i18n 国际化**：所有聊天消息、GUI 标题、倒计时提示、答题反馈、连击消息均支持中英双语
- **GUI 与聊天栏互斥重新设计**：关闭 GUI → 启动计时器 → 聊天答题流程，适配 Minecraft 客户端限制
- **主菜单全部项目支持双语切换**（单词学习/对话练习/进度统计随语言变化）
- 主菜单打开/按钮点击新增音效
- `maven-clean-plugin` 配置保护 `*.jar`，以后 `mvn clean` 不再删除旧版本

### Fixed
- **修复计时器无限循环**：答对后系统继续倒计时不停止，三重防护（volatile cancelled + AtomicBoolean 重入锁 + 主线程调度）
- **修复倒计时重叠**：计时器到期与聊天输入同时触发时产生双计时器雪崩
- **修复中文界面仍有英文混显**：`getPlayerLanguage()` 硬编码默认 `"en"` 不读 `config.yml` 的 `general.language` 配置，导致新玩家语言永远是英语；`config-version` 版本号升级至 4 强制刷新语言文件使新翻译生效
- **修复语言切换消息前缀未替换**：`ConfirmationGUI.applySwitch()` 绕过 `MessageUtil` 直接读语言文件，导致 `{prefix}` 显示为原始文本
- **修复 GUI 物品防盗**：所有插件 GUI 阻止玩家拿走展示物品
- 修复 `loadPlayerData()` SQL 参数顺序 bug 导致数据从未加载
- 修复切换语言后消息提示方向错误（切中文提示"已切换为英语"等）
- 修复切换语言后主菜单项目文字不变的问题
- 修复 MessageUtil 感知玩家语言的多处遗漏

### Refactored
- **答题流程完整推演与冗余清除**：12 处冗余代码清理（死构造函数/重复双 cancel/死语言键 10 个等）
- **buildResult() 公有方法**：消除 submitAnswer 与 handleTimeout 间 15 行重复统计代码
- **GUIMenuListener 提取 startAnswerForCurrentWord()**：消除 onInventoryClose / slot22 两处重复逻辑
- 语言文件精简化：删除 10 个死键，补充 8 个新汉化键
- `answer-prompt` 超时时间从硬编码改为配置注入 `{seconds}` 占位符
- 倒计时/答题反馈/超时提示已全部移除硬编码字符串，改用语言文件键
- `getPlayerLanguage()` 重构为读取 `config.yml` 的 `general.language` 配置项
- `MessageUtil` 新增 `resolvePrefix()` 方法，优先读取 `config.yml` 的 `prefix.{lang}` 配置
- `ConfirmationGUI.applySwitch()` 改为通过 `MessageUtil.getPlayerMessage()` 发送切换消息
- `PlayerJoinListener` 新增 `sendJoinReminder()` 方法，支持按玩家语言发送进服提醒

### Tests
- 新增 20 个单元测试（LearningSessionTest/PlayerProgressTest/WordTest），全部通过

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