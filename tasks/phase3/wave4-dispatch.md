# Wave 4 派发提示词备忘（编排者预写，2026-10-16）

> 使用说明：3B 收口提交后，按本文派发 4A（两流并行）→ fan-in → 三维 tester → 提交；再派 4B（单流）→ fan-in → tester → 提交。
> 提示词原则（用户指令）：详细但不锁死；agent 自决空间保留；范围外 bug 只上报不修复；agent 用 javac 检查编译（并行流禁跑 mvn）。

---

## 4A-1（mc-dev）：班级数据层 + 管理命令

**白名单**：`data/DatabaseManager.java`、`command/AdminCommand.java`、`command/AdminCommandHandler.java`、`resources/schema.sql`、`resources/config.yml`（仅 class-system 段）

**背景**：Phase 3 Wave 4 批次 4A，US-CLS-1。管理型班级（"老师=OP 开好班级"模式；不做玩家自建/邀请/审批）。一人一班（加入新班自动退出旧班并提示）。

**任务**：
1. `classes` / `class_members` 两表：DatabaseManager 内嵌 DDL（双后端 AUTOINCREMENT/AUTO_INCREMENT 分支惯例，参考 ai_chat_feedback 表写法）+ schema.sql 同步（`{prefix}` 前缀版）。DDL 草案见 `tasks/phase3/plan-season.md` §3.1（class_id/class_name UNIQUE/display_name/created_by/created_at；class_members: class_id/uuid/joined_at，PK(class_id,uuid)）
2. 10 个数据方法（**冻结签名，逐位对齐**——4A-2 并行流按此消费）：
   - `boolean createClass(String className, UUID createdBy, long createdAt)`（false=重名）
   - `boolean deleteClass(String className)`（false=不存在；先删成员行再删班级行）
   - `ClassRow getClassByName(String className)`、`List<ClassRow> listClasses()`
   - `List<ClassMemberRow> getClassMembers(int classId)`（LEFT JOIN player_data 取名，player_name 空回落 uuid 字符串——参考 getRankedPlayersForPeriod 的异步安全姿态）
   - `Integer getPlayerClassId(UUID)`（一人一班；null=无班）
   - `boolean addClassMember(int classId, UUID uuid, long joinedAt)`（false=已在班内；**换班语义由调用方编排**——Data 层只管单班成员行）
   - `boolean removeClassMember(int classId, UUID uuid)`（false=不在班）
   - `int getClassMemberCount(int classId)`
   - `record ClassRow(int classId, String className, String displayName, String createdBy, long createdAt)`
   - `record ClassMemberRow(int classId, UUID uuid, String playerName, long joinedAt)`
   线程纪律：沿用 connectionLock 同步包裹 + 双后端差异（INSERT OR IGNORE / INSERT IGNORE 若用）
3. `/lea class` 命令族（AdminCommand SUBCOMMANDS 列表 :33-36 加 "class" + switch case + handler 方法；权限走既有 `hasPermissionFor` 默认分支即 letslearnenglish.admin）：
   - `create <班名>`：长度校验（config `class-system.max-name-length` 默认 32）→ class.name-invalid；重名 → class.name-exists
   - `delete <班名>`：不存在 → class.not-found；成功 → class.deleted
   - `add <班名> <玩家>`：班不存在 → class.not-found；玩家名解析（复用 resolvePlayerUUID 三级查找 + 在线优先）；已在班 → 幂等提示；换班（已在别的班）→ 先 removeClassMember 旧班 + class.reassigned 提示，再插入；人数满（`class-system.max-members` 默认 50）→ class.member-full
   - `remove <班名> <玩家>`：不在班 → class.member-not-in
   - `list <班名>`：**全量含隐身穿透**（管理路径，plan §1.5 明示不受 show_in_square 过滤）——头 class.list-header{class}{count} + 逐行 class.list-entry{player}{score}（score=当前赛季窗学习分；经 SeasonManager 窗口 + 成员过滤查询——**注意**：`getRankedPlayersForPeriodFiltered` 是 4A-2 的交付物，本流未落盘前**不要消费**；如要强化 list 的分数列，可用已有 `SeasonManager` 缓存 API 或先只列成员名，自决并在报告中说明）
   - `enabled=false`（class-system.enabled）时全部子命令给 class.disabled
   - 无参/未知子命令 → class.usage
4. config.yml 追加 class-system 段（enabled:false / max-name-length:32 / max-members:50；中文注释风格照抄 season 段）
5. 线程纪律：DB 全异步（AdminCommandHandler 既有模式）→ sendMessage 回主线程 runTask；resolvePlayerUUID 的 Bukkit 部分在主线程先做完再进异步
6. 编译检查：javac 全仓（classpath 从 ~/.m2 取 paper-api + snakeyaml + gson；不跑 mvn）

**i18n 键清单（编排者 fan-in 落文本；agent 直接用，不新增清单外的键）**：
`class.created{class}` / `class.deleted{class}` / `class.member-added{player}{class}` / `class.member-removed{player}` / `class.member-full{max}` / `class.name-exists` / `class.not-found{name}` / `class.name-invalid{max}` / `class.disabled` / `class.list-header{class}{count}` / `class.list-entry{rank}{player}{score}` / `class.member-not-in{player}` / `class.player-not-found` / `class.usage` / `class.reassigned{player}{class}`

**zh/en 文本草案（编排者预写，fan-in 用）**：
| key | zh | en |
|-----|----|----|
| class.title | `&6&l我的班级` | `&6&lMy Class` |
| class.board-title | `&6&l班级榜 - {class}` | `&6&lClass Board - {class}` |
| class.not-in-class | `&7你还没有加入任何班级，请联系管理员` | `&7You are not in a class yet; ask an admin` |
| class.empty | `&7班级还没有成员` | `&7No members yet` |
| class.disabled | `{prefix}&7班级系统未启用` | `{prefix}&7Class system is disabled` |
| class.created | `{prefix}&a班级 &e{class} &a已创建` | `{prefix}&aClass &e{class} &acreated` |
| class.deleted | `{prefix}&a班级 &e{class} &a已删除` | `{prefix}&aClass &e{class} &adeleted` |
| class.member-added | `{prefix}&a已将 &e{player} &a加入班级 &e{class}` | `{prefix}&aAdded &e{player} &ato class &e{class}` |
| class.reassigned | `{prefix}&a已将 &e{player} &a从旧班级移入 &e{class}` | `{prefix}&aMoved &e{player} &afrom the old class into &e{class}` |
| class.member-removed | `{prefix}&a已将 &e{player} &a移出班级` | `{prefix}&aRemoved &e{player} &afrom the class` |
| class.member-not-in | `{prefix}&e{player} 不在该班级中` | `{prefix}&e{player} is not in that class` |
| class.member-full | `{prefix}&c班级人数已满（上限 {max} 人）` | `{prefix}&cClass is full (max {max} members)` |
| class.name-exists | `{prefix}&c已存在同名班级` | `{prefix}&cA class with that name already exists` |
| class.not-found | `{prefix}&c找不到班级 &e{name}` | `{prefix}&cClass &e{name} &cnot found` |
| class.name-invalid | `{prefix}&c班名无效（1-{max} 字符，不能含空格）` | `{prefix}&cInvalid class name (1-{max} chars, no spaces)` |
| class.player-not-found | `{prefix}&c找不到该玩家` | `{prefix}&cPlayer not found` |
| class.usage | `{prefix}&7用法: /lea class create\|delete\|add\|remove\|list` | `{prefix}&7Usage: /lea class create\|delete\|add\|remove\|list` |
| class.list-header | `{prefix}&6===== 班级 {class}（{count} 人）=====` | `{prefix}&6===== Class {class} ({count} members) =====` |
| class.list-entry | `{prefix}&e#{rank} &f{player} &7- &b{score} 分` | `{prefix}&e#{rank} &f{player} &7- &b{score} pts` |

注：`season.disabled` / `leaderboard.entry` 为复用既有活键，不新增。`class.list-entry` 若 4A-1 自决降级为只列成员名，fan-in 时相应改文本（去掉 {rank}{score}）。

**上报纪律**：范围外 bug 只上报（含文件:行号）；自决点（如 list 分数列的降级方案）写入最终报告。

---

## 4A-2（mc-dev）：班级榜与玩家入口

**白名单**：`data/PlayerDataStore.java`、`gui/ClassBoardGUI.java`（新）、`command/LearnCommand.java`、`listener/ClassBoardListener.java`（新，或自决既有落点）、`LetsLearnEnglish.java`（仅注册监听一行 + 如有 getter）

**背景**：US-CLS-2。班级榜 = **当前赛季窗**内班级成员聚合分（复用 SeasonManager 窗口）；同学视角**保留隐身过滤**（show_in_square=0 的成员不列出/不计入——隐私跨界面一致性）；榜单排序/展示复用 LeaderboardEntry。

**任务**：
1. `PlayerDataStore.getRankedPlayersForPeriodFiltered(int count, LocalDate since, LocalDate until, Collection<UUID> members)`：以 `getRankedPlayersForPeriod`（:1149）为模板加 `AND s.uuid IN (?,?…)` 动态占位符；**空集合早退返回空表**（防 `IN ()` 语法错误）；保留 `COALESCE(pf.show_in_square,1)<>0` 隐身过滤；保持既有线程安全姿态（connectionLock + ResultSet 关闭后再组装，不碰 Bukkit API）
2. `ClassBoardGUI`（新）：54 格；标题 `class.board-title{class}`（等值判定用作点击路由依据）；成员头像按赛季分排序渲染（参考 LeaderboardGUI 的头像渲染 + ENTRY_SLOTS 布局）；空态 class.empty（barrier）；返回键 slot 8 → MainMenu；**GUI 打开零查库**——走"点击/命令 → 异步查 → runTask 回主线程 open(数据在手)"链路（PF-SEA-04 教训）；快照语义（打开后不重查）。
   注意：`getRankedPlayersForPeriodFiltered` 是 4A-1 并行流的间接依赖吗？——**不是**，它是本流自己的交付物（PlayerDataStore 归本流）。班级存在性/成员列表读的是 4A-1 的 `DatabaseManager.getClassByName/getClassMembers` 冻结签名——依赖 4A-1 **接口**（并行安全），若 4A-1 尚未落盘则该调用不编译：**处理方式**：接口已冻结（见 4A-1 提示词），按签名写代码；若编译时 4A-1 未落盘，将依赖调用处写好后等编排者 fan-in 时统一验证（或与本流编译检查解耦——在报告中说明）。
3. `/le class` 入口（LearnCommand：SUBCOMMANDS 列表 + switch case + handler）：
   - 自身无班 → class.not-in-class
   - 有班 + class-system.enabled=true → 异步拉成员 + 分数 → 开 ClassBoardGUI
   - class-system.enabled=false → class.disabled
   - 赛季不可用（SeasonManager.isAvailable=false）→ season.disabled（班级榜依赖赛季窗）
   - tab 补全加 class（可选）
4. 点击处理（ClassBoardListener 新文件 + LetsLearnEnglish 注册一行）：返回键 slot 8 → menu-click 音效 + MainMenu；其余忽略。标题判定用**等值比对**（按 zh/en 双语言渲染 class.board-title 后比对；参考 ChatHistoryListener 先例 + F6-02 教训：禁裸子串匹配）；标题含班级名——等值比对需处理运行时班级名（自决实现，如 PDC 存班名 or 从 GUI 实例快照读，说明方案）。**drag guard**：上半区 onInventoryDrag 守卫（照抄 ChatHistoryListener 形态）
5. 编译检查：javac 全仓（不跑 mvn）

**i18n 键清单（编排者 fan-in）**：`class.title` / `class.board-title{class}` / `class.not-in-class` / `class.empty` / `class.disabled`（4A-1 共用）/ `season.disabled`（复用既有）/ `leaderboard.entry{rank}{player}{score}`（复用既有——注意是活键，/le season 也在用）

**上报纪律**：同 4A-1。

---

## 4B（mc-dev）：MC-7 死键/死类大扫除

**白名单**：`gui/ProgressGUI.java`（删）、`resources/messages/zh.yml`、`resources/messages/en.yml`、`resources/config.yml`、`util/MessageUtil.java`（仅 javadoc 示例两处）、`listener/SocialListener.java`（仅 :83 注释清理，如适用）

**核销表**：见 construction-tracker.md "4B 死键核销表"小节（含行号快照 + 活键保护名单）。**每键删除前全路径 grep 复核**（行号会漂）。

**纪律**：
- zh/en 对称删（删后两边叶子键数相等）
- 死类 ProgressGUI.java 与 progress-gui.* 键段**同批成对删**（否则删类后键变新死键）
- 活键保护：`leaderboard.header/entry/footer`（/le season 快览在用）、`word-learning-gui.answer-broadcast-correct`、`dialogue.daily-limit-reached`、messages 版 `gui.main-menu-title`、`progress-system.rewards.types.money.*` 子树——**绝不误删**
- config 删除段的注释保留说明（如 difficulty-settings 整段删掉后可留一行注释指向 words/*.yml 自动发现）
- 删后跑 `node scripts/audit-i18n-keys.js` 确认零新增缺失
- javac 检查（不跑 mvn）

**上报**：发现核销表之外的死键/死配置只上报，不扩大删除范围。
