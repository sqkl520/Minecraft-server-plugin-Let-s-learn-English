# Phase 3 规划：广场社交最小循环（域 4）

- 日期： 2026-10-13
- 施工状态： **已施工**（P3 Wave 2 域 4，提交 `13dc44a`；三维 tester GO/PASS/PASS；PF-SOC-06 留账 Wave 3）
- 基线： 1.0.7-SNAPSHOT（P2 批次 commit `17b0c02` 之后）
- 来源： `tasks/plan-roadmap-usable-to-delightful.md` 第 8 节方向清单第 4 条；`tasks/p2-polish-execution-plan.md` 第五节域 4
- 规划纪律： 本轮为只读规划，未修改任何源码/配置；规划期发现见第 8 节
- 规模估计： **M**（新增约 700~800 行 + 12 个触及文件），**5 个 story**（4 开发 + 1 编排者 fan-in）

---

## 1. 操作逻辑

### 1.1 方案选定：学习动态流 + 鼓励（cheer）+ 回应闭环

候选对比（"最小循环"= 看 → 互动 → 被回应 → 回来）：

| 方案 | 循环强度 | 新增机制面 | 结论 |
|------|---------|-----------|------|
| 学习动态流 + 点赞（本方案） | 强（事件自动产生，单键互动，离线可回应） | 2 新表 + 1 新 Manager + 广场第 5 模式 | **选定** |
| 求助帖（"这个单词怎么记"） | 中 | 需要第 5 个聊天消费者（自由文本输入），并入 quiz/决斗/AI/练习互斥矩阵（F1-17 已欠账），另有内容审核面 | v1 不做（第 1.8 节） |
| 好友系统 | 中 | 请求/接受/删除状态机 + 第三张表，不强化核心循环 | 后续再评估 |
| 组队学习 | 强 | 需 party/房间状态机（DuelRoom 级复杂度） | 后续再评估 |

本方案不引入任何自由文本内容（动态全部由既有学习事件自动生成，鼓励是单按钮动作），从根上规避内容审核与聊天互斥问题，符合"宁可窄而完整"。

### 1.2 最小闭环定义（二人剧本）

1. **A 做什么**：A 自然走完一次学习会话（或解锁成就 / 签到）→ 系统自动生成一条动态（按日按类型合并）。
2. **B 看到什么**：B 打开英语广场（默认落在"动态"模式）→ 看到"A 完成了一次拼写会话"条目（含答对数字，受 A 的隐私开关制约）。
3. **B 做什么**：B 左键该条目 → 播放音效，条目 lore 的"❤ 鼓励 ×1 / ✔ 你已鼓励"即时更新。
4. **A 收到什么**：
   - A 在线：立即收到聊天消息"B 鼓励了你的学习动态！"+ 音效；
   - A 离线：下次进服收到摘要"你不在的时候，你的学习动态收到了 N 个鼓励！输入 /le square 看看是谁"；
   - A 打开广场：自己条目显示鼓励数，左键自己的条目 → 聊天列出鼓励者名单（B、C……）。
5. **闭环回流**：A 知道了谁鼓励了自己 → A 去鼓励别人的动态（回到步骤 3 的对方视角）→ 循环自我维持。

### 1.3 入口

- **主入口（唯一新增全局入口）**：英语广场内新增第 5 个模式按钮"动态"（`gui-layout.english-square.mode-feed`，默认 slot 6，行 0 的空位）。空位说明：slot 0=自己头像、1 空、2~5=四种既有模式、6~7 空、8=返回。
- **默认落点**：`/le square` 与主菜单广场按钮打开时默认落"动态"模式（配置 `english-square.default-mode: feed|all`，默认 `feed`）。动态流是"看"的钩子，放首页最大化循环入口；四个榜单模式一键可达（既有按钮不动）。动态为空时用空态文案引导去学习。
- **命令**：`/le square [feed|weekly|monthly|alltime|all]` 增加可选参数（直达某模式）。**不新增** `/le feed`、`/le friend` 子命令——广场即入口，避免命令面膨胀。
- **不做**：主菜单不加新按钮（主菜单布局槽位紧张，且广场按钮已存在）；不加单独的"社交"二级菜单。

### 1.4 每类动态的产生规则

v1 只有 3 类事件（AI 对话完成、决斗胜利等留后续版本，理由：3 类已足够闭合循环，且都是既有"成就/会话/签到"锚点）：

| 事件 | 产生点 | 线程 | 合并规则 | 展示内容 | 明确不产生 |
|------|--------|------|---------|---------|-----------|
| `SESSION_DONE` | `WordLearningManager.showSessionComplete`（自然走完会话，含 review 模式） | 主线程（聊天回调） | 同日同玩家合并为 1 行：`events_count+1`、`sum_correct/sum_total` 累加、`last_mode` 覆盖为最近模式 | "{player} 完成了一次{mode}会话"（复用既有 `word.mode-*` 键）+ "答对 X/Y"（仅当作者 showResults=true，见 1.6） | stop/quit/死亡中断不产生（与"不奖励没走完的会话"同一哲学，防刷屏） |
| `ACHIEVEMENT` | `AchievementManager.tryUnlock` 的**新解锁**返回分支 | 任意线程（异步聊天可达，F1-03 类） | 同日合并为 1 行：计数 +1，成就名取**最新** | "{player} 解锁了成就 {achievement}！"（成就名按 viewer 语言经 `Achievement.fromId().getDisplayName(lang)` 渲染）；同日多条显示"今日解锁了 N 个成就，最新：X" | 已解锁重复调用 no-op 不产生；`isAchievementEligible=false` 不产生 |
| `CHECKIN` | `LearnCommand.handleCheckinCommand` 成功分支（`CheckInResult.success`） | 主线程 | 天然每日至多 1 条（结构上限） | "{player} 连续签到 {streak} 天" | 重复签到/断签失败不产生 |

**防刷屏 = 结构聚合**：每玩家每自然日每类型恒定 1 行（UPSERT 合并），全量上限 3 行/玩家/日。不需要额外的"每日动态配额"计数器——合并本身就是配额。

### 1.5 互动动作的冷却与限流

- **鼓励唯一约束**：`UNIQUE(feed_id, cheerer_uuid)`（DB 级），一人对一条动态至多 1 次。再次点击不写入，回 i18n 提示。
- **防抖**：`SocialListener` 对鼓励点击做 per-player 750ms 防抖（`Map<UUID, Long> lastCheerAt`，quit 时清理——吸取 F2-10/F1-24 "只增不删"教训），防宏连点。配置 `social.cheer.click-debounce-ms: 750`。
- **不可自赞**：`cheerer == 作者` 时拒绝并提示（自己的条目左键改为"查看谁鼓励了我"，见 1.2 步骤 4）。
- **无经济面**：鼓励不发金币、不给被鼓励者金币（明确决策，防止重演 F1-01/F1-11 类"绕过 money.daily-cap"问题；也杜绝大小号对刷动机）。唯一的"收益"是 P3-3 互助成就的进度（见 4.3）。

### 1.6 隐私开关的延伸

| 开关 | 对动态流的延伸 | 实现层 |
|------|--------------|--------|
| `showInSquare=false` | **覆盖动态流**：① 产生侧——记录事件前查 `getProfileSettingsReadOnly(author).isShowInSquare()`，false 则整条不产生；② 读取侧——FeedCache 刷新 SQL 用与榜单同款的 `LEFT JOIN player_profiles ... WHERE COALESCE(show_in_square,1) <> 0` 过滤（含对已存在行的追溯隐藏，与榜单语义一致：重新打开后条目回归）；③ 点击守卫——鼓励/右键打开主页前复查作者当前开关（缓存刷新窗口内的陈旧条目兜底）；④ 自己看自己：与榜单口径一致，隐身者条目对**所有人**（含本人）不显示 | 与既有"隐私四层"（装配 + SQL JOIN + 点击守卫 + 命令直达）完全同口径 |
| `showResults=false` | 细节行降级：动态条目只显示"完成了一次学习会话"通用文案，不显示"答对 X/Y" | 渲染时读 `getProfileSettingsReadOnly(author)`（只读版，不建缓存条目——L-05 教训） |
| `showAnswers` | 无关联：动态不含任何答案文本 | — |

边界决策：A 隐身期间收到鼓励的离线摘要**照常发送**（摘要是给 A 本人的，不向任何第三方泄露；且鼓励行为发生在 A 隐身之前或缓存窗口内，无法完全避免）。此语义写入代码注释。

### 1.7 离线 / 跨服场景

- **离线**：动态与鼓励全量落 DB；A 不在线时 B 的鼓励写 `read_by_owner=0`，A 进服走摘要（见 2.2 与 3.2 线程模型）。全状态可跨重启。
- **跨服（共享 MySQL）**：动态流天然全局（读同一张表）；离线摘要跨服聚合（在哪台子服进服都读到全量未读计数）。**不需要** ProxyMessenger——v1 无实时跨服推送需求（不追随 P2 引入的跨服广播，保持窄）。
- **跨服（SQLite）**：每子服独立动态流（与榜单/广场现状同一限制），文档化即可。
- **玩家改名**：沿用 `player_data.player_name` + uuid 回落惯例，动态渲染时经 FeedCache 的 JOIN 取名字，不落冗余名字。

### 1.8 数据保留策略

- **过期**：动态保留 `social.feed.retention-days`（默认 7 天），清理任务（删除过期 feed 行 + 其级联 cheers）在**启动异步**（对齐 `cleanupOldChatHistory` 模式）与**午夜 rollover 异步**各跑一次。0 = 永久保留（不推荐，注释说明）。
- **条数上限**：结构上限 3 行/玩家/日（1.4）+ 7 天保留 ⇒ 百活跃玩家服稳态约 2100 行，千行级，无需额外爆表防护。
- **缓存上限**：FeedCache 仅缓存最近 200 条（≈7 页 ×28），翻页在内存切片，无逐页查库。
- **不做的**：不保留已删动态（无软删除）；不做个人动态归档页。

---

## 2. 用户体验

### 2.1 广场信息架构改版草案（ASCII）

**A. 动态流页**（54 格；标题沿用 `square.title` 加模式后缀 " | 动态"，监听器标题白名单与 `GUIUtils.isPluginGUI` 零改动）：

```
┌─────────────────────────────────────────────────────┐
│ 英语广场 - 第1页 | 动态                                │
├──────┬───┬────┬────┬────┬────┬────┬───┬──────┤
│0 自己 │1空 │2周 │3月 │4总 │5广场│6动态★│7空 │8 返回 │   ★=当前选中(LIME 高亮)
│ 头像 │   │ 榜 │ 榜 │ 榜 │    │      │   │      │
├──────┴───┴────┴────┴────┴────┴────┴───┴──────┤
│10     11     12     13     14     15     16   │  动态条目（玩家头像）
│  ●A    ●B     ●A     ●C    ●A     ●D    ●B    │  每条 = 一个 (玩家×类型×日) 合并行
│19     20     21     22     23     24     25   │
│  ●C    ●A     ●E    [空态]  ●B    ●A     ●F    │  lore 示例（A 的拼写会话）:
│28     29     30     31     32     33     34   │   &f"A 完成了一次拼写会话"
│  …    …      …      …      …      …      …    │   &7答对 &a8&7/&e10        ← showResults 关→通用文案
│37     38     39     40     41     42     43   │   &c❤ 鼓励 ×3
│  …    …      …      …      …      …      …    │   &a✔ 你已鼓励            ← 仅当 viewer 已鼓励
├───────────────────────────────────────────────┤
│45空 │46空 │47←上页│48空│49空│50空│51下页→│52空│53空│   自己的条目: "&d左键查看收到的鼓励"
└───────────────────────────────────────────────┘
```

**B. 鼓励交互流**（单键 + 右键分义，单槽双动作避免每条目占两格）：

```
[B 左键 A 的条目]
  ├ 音效: cheer（gui.sounds.cheer，默认 ENTITY_EXPERIENCE_ORB_PICKUP）
  ├ lore 即时更新: ❤ 鼓励 ×1 / ✔ 你已鼓励（缓存乐观 +1，重开必刷新）
  ├ A 在线 → A 立即收到: "[英语学习] B 鼓励了你的学习动态！输入 /le square 查看"（+cheer 音效）
  └ A 离线 → 写 read_by_owner=0；A 进服: "你不在的时候，你的学习动态收到了 2 个鼓励！"
[B 再点同一条] → "&7你已经鼓励过这条动态了喵~"（不写库）
[B 点自己/别人的边界]:
  ├ B 点自己的条目 → 聊天列出鼓励者: "&dB、C 鼓励了你的动态！&7（共 2 个）" / 无人鼓励时提示
  ├ B 右键任意条目 → 打开该作者主页（隐身者的陈旧条目 → 已隐身提示，不开界面）
  └ 作者已隐身 / 条目已过期 → "该玩家已隐身，暂不可互动喵~" / "这条动态已经翻篇了喵~"
```

**C. 看看谁在学（既有玩家列表页，保持现状 + 仅新增动态按钮）**：

```
┌─────────────────────────────────────────────────────┐
│ 英语广场 - 第1页                                       │
├──────┬───┬────┬────┬────┬────┬────┬───┬──────┤
│0 自己 │1空 │2周 │3月 │4总 │5广场★│6动态 │7空 │8 返回 │
├──────┴───┴────┴────┴────┴────┴────┴───┴──────┤
│10-43  玩家头像（答对/答错 lore，点击看主页；        │
│       隐身者 → 笑话页，现状不变）                   │
│47/51  翻页（现状不变）                              │
└─────────────────────────────────────────────────────┘
```

### 2.2 互动反馈设计

- **音效**：鼓励点击音走新增 `gui.sounds.cheer` 键（`SoundManager.reloadSoundCache` 注册新键，未知键 WARNING 机制自动覆盖）；被鼓励者在线提醒复用同一音效。
- **被回应通知（离线安全）**：形态参考 `DuelManager.OfflineDuelNotice` 的"join 时 consume 一次"设计，但**存储取 DB 而非内存队列**——理由：① 鼓励是多次事件，DuelManager 的单槽覆盖语义会丢；② 内存队列重启即丢，鼓励属"可持续查看的社交账本"；③ 共享 MySQL 下摘要天然跨服聚合。接口形状对齐：`PlayerJoinListener.onPlayerJoin` 里 `plugin.getSocialManager().notifyPendingCheers(joining)`（内部异步查未读计数 → 主线程发消息 → 异步标记已读；方法返回 void，调度差异写入 javadoc）。
- **在线即时提醒**：`SocialListener` 在 cheer 写库成功后，主线程给在线作者发消息并标记该条已读（作者恰在此间隙退服的极小竞态允许下次进服重复提醒一次——宽容语义，注释说明）。
- **GUI 即时性**：鼓励成功后对 FeedCache 条目做乐观 `cheer_count+1`（volatile 列表整体替换），当前打开的界面重渲染即见；其余玩家 ≤1 个刷新周期可见。

### 2.3 混服语言处理

动态内容**只在渲染时组装**（DB 只存类型 + 数字 + 成就 id + 模式 id + 玩家名），按 **viewer 语言**取模板渲染——即 R-86m"按收件人渲染"模式（区别于 R-86m 的"双语各渲染一次再分发"，此处每个 viewer 打开界面时渲染一次即可，无需广播循环）。成就名经 `Achievement.getDisplayName(viewerLang)`；模式名复用既有 `word.mode-spelling/choice/fill_blank/review` 键；玩家名语言中立。纯函数抽 `FeedTextFormatter`（静态方法：事件行 + 数字行 + 语言 + showResults → 模板 key 与占位符），便于无 Bukkit 单测（对齐 INFRA-01 方向）。

### 2.4 i18n key 草案（`square.feed.*` / `square.cheer.*`，zh/en 双文件，config-version 11→12）

| key | zh（草案） | en（草案） | 用者 |
|-----|-----------|-----------|------|
| square.feed.mode-label | 动态 | Feed | 模式按钮 + 标题后缀 |
| square.feed.empty | &7广场还很安静~ 完成一次学习就能留下第一条动态喵！ | &7The square is quiet~ Finish a lesson to post the first update! | 空态 |
| square.feed.session | &f{player} 完成了一次&e{mode}&f会话 | &f{player} finished a &e{mode}&f session | SESSION_DONE |
| square.feed.session-detail | &7答对 &a{correct}&7/&e{total} | &7Correct: &a{correct}&7/&e{total} | 细节行（showResults 开放时） |
| square.feed.session-merged | &f{player} 今天完成了 &e{count} &f次学习会话 | &f{player} completed &e{count}&f sessions today | 合并行 |
| square.feed.achievement | &f{player} 解锁了成就 &6{achievement}&f！ | &f{player} unlocked the achievement &6{achievement}&f! | ACHIEVEMENT |
| square.feed.achievement-merged | &f{player} 今日解锁了 &e{count} &f个成就，最新：&6{achievement} | &f{player} unlocked &e{count}&f achievements today, latest: &6{achievement} | 合并行 |
| square.feed.checkin | &f{player} 连续签到 &e{streak} &f天 | &f{player} checked in &e{streak}&f days in a row | CHECKIN |
| square.feed.hidden-detail | &7完成了一次学习会话 | &7Completed a learning session | showResults=false 降级行 |
| square.feed.cheer-count | &c❤ 鼓励 ×{count} | &c❤ Cheers: {count} | 条目 lore |
| square.feed.cheer-hint | &7左键鼓励 · 右键看主页 | &7Left-click: cheer · Right-click: profile | 条目 lore |
| square.feed.own-hint | &d左键查看收到的鼓励 | &dLeft-click: see who cheered you | 自己条目 lore |
| square.feed.cheered | &a✔ 你已鼓励 | &a✔ You cheered | 条目 lore（已鼓励态） |
| square.feed.cheer-success | {prefix}&a你鼓励了 &e{player} &a的学习动态！ | {prefix}&aYou cheered &e{player}&a's learning update! | 点击反馈 |
| square.feed.cheer-already | {prefix}&7你已经鼓励过这条动态了喵~ | {prefix}&7You already cheered this update~ | 重复点击 |
| square.feed.cheer-own | {prefix}&7不能鼓励自己的动态喵~ | {prefix}&7You can't cheer your own update~ | 自赞（防伪调用） |
| square.feed.cheer-gone | {prefix}&7这条动态已经翻篇了喵~ | {prefix}&7This update is gone already~ | 条目过期/被清 |
| square.feed.cheer-hidden | {prefix}&7该玩家已隐身，暂不可互动喵~ | {prefix}&7That player is hidden right now~ | 点击守卫拦截 |
| square.feed.cheerers | {prefix}&d{names} &f鼓励了你的动态！&7（共 {count} 个） | {prefix}&d{names} &fcheered your update! &7({count} total) | 自条目左键 |
| square.feed.cheerers-none | {prefix}&7这条动态还没有收到鼓励喵~ | {prefix}&7No cheers on this update yet~ | 自条目左键（空） |
| square.feed.notify-online | {prefix}&e{player} &f鼓励了你的学习动态！&7输入 &e/le square &7查看 | {prefix}&e{player} &fcheered your learning update! &7Use &e/le square &7to view | 在线即时提醒 |
| square.feed.notify-join | {prefix}&e你不在的时候，你的学习动态收到了 &c{count} &e个鼓励！&7输入 &e/le square &7看看是谁~ | {prefix}&eYour learning updates received &c{count} &echeers while you were away! &7Use &e/le square &7to see who~ | 进服摘要 |

修改键：`help.square` 补 `[feed|weekly|monthly|alltime|all]` 参数说明。落地时先 grep 确认键不存在；两文件同步 + `ConfigManager.MESSAGE_CONFIG_VERSION` 11→12。

### 2.5 config.yml 新键草案

```yaml
# ============================================================
#  广场社交 (Square Social)
# ============================================================
social:
  # 是否启用广场社交（动态流 + 鼓励）。false 时：不产生动态、动态按钮隐藏、
  # 鼓励点击无效、进服摘要不发。四处接线，非静默 no-op（F0-11 教训）
  enabled: true
  feed:
    # 动态保留天数，过期动态连同其鼓励记录一并清理；0 表示永久保留（不推荐）
    retention-days: 7
  cheer:
    # 是否启用鼓励按钮
    enabled: true
    # 连续点击防抖（毫秒），防宏连点
    click-debounce-ms: 750
  # 玩家进服时提示"离线期间收到的鼓励"
  notify-on-join: true

english-square:
  # 广场打开时的默认模式: feed（动态流，推荐）/ all（玩家列表）
  default-mode: "feed"

# gui.sounds 段新增:
gui:
  sounds:
    cheer: "ENTITY_EXPERIENCE_ORB_PICKUP"   # 鼓励音效

# gui-layout.english-square 段新增:
gui-layout:
  english-square:
    mode-feed:
      slot: 6
      material: "BELL"
```

主 config `config-version` 维持 2（不做主配置迁移，随注释追加；messages 两个文件升级到 12）。

---

## 3. 实现要点

### 3.1 数据模型

两张新表（`schema.sql` 文档 + `DatabaseManager.createTables` 双后端建表；新表用 `CREATE TABLE IF NOT EXISTS`，老库升级零迁移）：

```sql
-- 学习动态流（每玩家 × 每自然日 × 每事件类型 合并为 1 行）
CREATE TABLE IF NOT EXISTS {prefix}social_feed (
    id INTEGER PRIMARY KEY <AUTOINCREMENT(SQLite)/AUTO_INCREMENT(MySQL)>,
    player_uuid VARCHAR(36) NOT NULL,     -- 动态作者
    feed_date DATE NOT NULL,              -- 服务器时区自然日（合并键）
    event_type VARCHAR(16) NOT NULL,      -- SESSION_DONE / ACHIEVEMENT / CHECKIN
    events_count INT DEFAULT 1,           -- 当日同类型累计次数
    sum_correct INT DEFAULT 0,            -- SESSION_DONE 累计答对
    sum_total INT DEFAULT 0,              -- SESSION_DONE 累计题数
    last_mode VARCHAR(16),                -- SESSION_DONE 最近会话模式
    last_streak INT DEFAULT 0,            -- CHECKIN 最近连续天数
    achievement_id VARCHAR(64),           -- ACHIEVEMENT 最近解锁 id
    cheer_count INT DEFAULT 0,            -- 鼓励计数（去规范化，写侧维护）
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,           -- 排序键（新事件/合并时刷新；鼓励不改它，保持排序稳定）
    UNIQUE(player_uuid, feed_date, event_type)
);
CREATE INDEX IF NOT EXISTS {prefix}idx_feed_order  ON {prefix}social_feed(feed_date, updated_at);
CREATE INDEX IF NOT EXISTS {prefix}idx_feed_player ON {prefix}social_feed(player_uuid, feed_date);

-- 鼓励记录（一人一条动态至多一次）
CREATE TABLE IF NOT EXISTS {prefix}social_cheers (
    id INTEGER PRIMARY KEY <同上>,
    feed_id INTEGER NOT NULL,             -- 逻辑关联 social_feed.id（沿用"无外键直删"惯例）
    cheerer_uuid VARCHAR(36) NOT NULL,    -- 鼓励者
    created_at BIGINT NOT NULL,
    read_by_owner BOOLEAN DEFAULT 0,      -- 作者离线摘要已读标记
    UNIQUE(feed_id, cheerer_uuid)
);
CREATE INDEX IF NOT EXISTS {prefix}idx_cheers_feed    ON {prefix}social_cheers(feed_id);
CREATE INDEX IF NOT EXISTS {prefix}idx_cheers_cheerer ON {prefix}social_cheers(cheerer_uuid);
```

**DatabaseManager 新增方法（全部持 `connectionLock`，双后端 SQL 对照，风格对齐 `adjustCoins`/`updateCheckIn`）**：
- `upsertFeedEvent(uuid, type, 数据载荷)` —— 单语句 UPSERT 合并（SQLite `ON CONFLICT(player_uuid, feed_date, event_type) DO UPDATE` / MySQL `ON DUPLICATE KEY UPDATE`），累加 `events_count/sum_*`，覆盖 `last_mode/last_streak/achievement_id`，刷新 `updated_at`。**无读-改-写**，并发天然安全。
- `getFeedCacheRows(retentionDays, limit)` —— `SELECT ... LEFT JOIN player_data(名字) LEFT JOIN player_profiles WHERE feed_date >= ? AND COALESCE(show_in_square,1) <> 0 GROUP BY f.id ORDER BY updated_at DESC LIMIT ?`（MySQL `ONLY_FULL_GROUP_BY` 兼容：非分组列用 `MAX()` 包住，沿 `getRankedPlayersForPeriod` 惯例；勿在结果消费阶段调 Bukkit API）。
- `cheerFeed(feedId, cheererUuid)` —— 同一 `synchronized` 块内两短语句：`INSERT OR IGNORE / INSERT IGNORE` cheers → 受影响行数为 1 才 `UPDATE social_feed SET cheer_count = cheer_count + 1`；仿 `savePlayerData` 在锁内 `setAutoCommit(false)`/`commit()` 保证两语句原子（连接状态不外泄）。返回枚举 `CheerResult{OK, ALREADY, GONE}`。
- `getCheerers(feedId, limit)` —— 鼓励者名单（`LEFT JOIN player_data` 取名，空回落 uuid），供"谁鼓励了我"。
- `markFeedCheersRead(ownerUuid)` / `markSingleCheerRead(feedId, cheererUuid)` —— 摘要已读标记（UPDATE 子查询只涉及 social_feed，跨表无 MySQL 更新-子查询自引用限制）。
- `countUnreadCheers(ownerUuid)` —— 进服摘要计数（`JOIN social_feed` + `read_by_owner=0`，走 `idx_cheers_feed`）。
- `cleanupOldFeedEvents(retentionDays)` —— 先删过期 feed 行的 cheers（`DELETE ... WHERE feed_id IN (SELECT id FROM social_feed WHERE feed_date < ?)`）再删 feed 行，同锁同事务。
- `deletePlayerData` 扩展（R-03 惯例，防 reset 残行）：**顺序敏感**——① 删该玩家的喂出的 cheers（`cheerer_uuid = ?`）；② 删其 feed 行上的他人 cheers（`feed_id IN (SELECT id FROM social_feed WHERE player_uuid = ?)`，必须先于第 ③ 步）；③ 删 `social_feed WHERE player_uuid = ?`。注意 `social_cheers` 的玩家列名是 `cheerer_uuid`（非既有两套 `uuid`/`player_uuid` 循环），需要第三个删除循环并注释。

### 3.2 核心类与线程模型（F2-11 教训的明确方案）

新类 `core/social/SocialManager.java`（唯一新 Manager，形态对齐 DuelManager：嵌套 record + 内聚逻辑）：

- `recordSessionDone(uuid, mode, correct, total)` / `recordAchievement(uuid, achievementId)` / `recordCheckin(uuid, streak)` —— 统一入口 `recordEvent(...)`：① `social.enabled` 门（真接线，F0-11 教训）；② `getProfileSettingsReadOnly` 隐身门；③ 若 `Bukkit.getScheduler()` 可用 → **`runTaskAsynchronously` 执行 DB 写**（本方法可能从异步线程（成就解锁 F1-03 路径）或主线程（会话完成）两路进入，异步派发两路都合法且统一）；④ 写成功后调度一次**去抖 5 秒的异步 FeedCache 刷新**（`AtomicLong` 去抖，同一窗口内多次写只刷一次）。
- `refreshCacheAsync()` —— 异步快照刷新（对齐 `Leaderboard.refreshCache` 模式）：`volatile List<FeedItem>` 整体替换；启动时随 leaderboard 预热块一起调；reload 时由 HotReloadManager 调一行。`FeedItem` record：`(id, authorId, authorName, type, eventsCount, sumCorrect, sumTotal, lastMode, lastStreak, achievementId, cheerCount, updatedAt)`。
- `cheer(Player cheerer, long feedId)` —— 主线程：校验条目在缓存中存在且作者≠自己（否则 `cheer-own`/`cheer-gone`）→ 点击守卫复查作者 `showInSquare`（陈旧条目 → `cheer-hidden`）→ 750ms 防抖 → **异步**调 `databaseManager.cheerFeed` → 回调主线程分发结果：`OK` 时播放 cheer 音效 + 发给点击者 `cheer-success` + 乐观更新缓存计数 + 若作者在线则立刻发 `notify-online`（主线程）并异步 `markSingleCheerRead`，否则留 `read_by_owner=0`。
- `notifyPendingCheers(Player joining)` —— 进服摘要：**异步** `countUnreadCheers` → `runTask` 主线程发 `notify-join`（count>0 且 `social.enabled` 且 `notify-on-join`）→ 异步 `markFeedCheersRead`。不统计谁（摘要只给计数，名单进广场自条目左键看）。
- `getCheerersAsync(uuid, feedId, Consumer<List<String>>)` —— 自条目左键：异步查名单 → 主线程发 `cheerers`/`cheerers-none`。
- `getCheerStats(uuid)` —— 供 P3-3 互助成就（见 4.3），返回 `CheerStats(given, distinctTargets, received)`，异步或按调用方线程上下文执行（成就侧调用点自己保证不阻塞主线程）。
- `unloadPlayer(uuid)` —— 清防抖 map（挂进 `PlayerJoinListener.onPlayerQuit` 的 S5 quit 编排点）。
- `isEnabled()` —— 供 GUI/Listener/摘要四处门槛。

**线程模型总结（对 F2-11 的正面回答）**：

| 路径 | 线程 | 依据 |
|------|------|------|
| 动态/鼓励**全部写** | 一律异步（scheduler） | 主线程零写库 |
| 广场打开读动态页 | **零 DB**：读 FeedCache `volatile` 快照（上限 200 条内存切片） | 对齐 LeaderboardGUI 读缓存榜模式 |
| 每页"你已鼓励"集合 | 打开时 1 条**有界**同步查询（`WHERE cheerer_uuid=? AND feed_id IN (≤28 ids)` 走唯一键索引） | 量级与既有 `getPlayerStats` 兜底同级；注释标注权衡与后续优化（并入内存 set） |
| 进服摘要 | 异步查 + 主线程发 + 异步标已读 | 对齐"DB IO 不落主线程、玩家可见动作回主线程" |
| 清理任务 | 启动异步 + 午夜 rollover 里 fire-and-forget 异步 | 午夜任务本体已有的同步 IO 是 P2-11 记录在案的欠账，本域**不加重**它 |
| 缓存新鲜度 | 写入后 5s 去抖刷新；周期刷新挂在既有 5 分钟 leaderboard 定时器旁（或独立 300s 异步任务） | 新动态 ≤6s 可见，验收可测 |

### 3.3 GUI 分页与 SnapshotMap 模式复用

- `EnglishSquareGUI` 新增 `RankingMode.FEED` 分支：扩展 `RankingMode` enum（该 enum 已含非榜单语义的 `ALL_PLAYERS`，加 `FEED` 一致；`Leaderboard.getTopPlayers` 的 default 分支对 FEED 返回空列表——**调用方契约：FEED 永不传给它**，加注释防误用）。
- 快照扩展：`currentPlayerIds` 保持（列表模式），新增 `getCurrentFeedItems()`（FEED 模式返回本次渲染的 `List<FeedItem>`，immutable 快照）；`currentMode` 已有。分页沿用 `GUIUtils.extractPageFromTitle` + 配置槽 47/51；页数 = `ceil(缓存条数 / 28)`。R-36 的"上一页/下一页独立门禁"照抄。
- `SocialListener` 的快照 map 复用不变：`squareGuis`（按 viewer 分键）、quit 清理、无快照兜底分支——兜底重建保持 `ALL_PLAYERS`（FEED 界面打开即登记快照，无快照时窗口内不可能有点击；与该文件既有注释口径一致）。
- FEED 分支点击路由（在 `handleEnglishSquareGUI` 内按 `myGui.getCurrentMode()==FEED` 分流，独立方法 `handleFeedSlot`）：左键=鼓励 / 自条目=看鼓励名单；右键=`AuthorProfile` 打开（复用既有"隐身→openHidden 笑话页"守卫，pushBack 闭包捕获 `(page, FEED)`）；禁止把 FEED 的 slot 映射进 `playerIds` 定位（越界保护照旧 `globalIndex < size`）。
- 拖拽 guard：标题白名单不变（同标题），已覆盖——**不需要**动 `onInventoryDrag`。

### 3.4 Command 入口

`LearnCommand`：`handleSquareCommand(player, args)` 接受可选模式参数（`feed/weekly/monthly/alltime/all`，非法值回 `general.unknown-command` 或直接忽略开默认）；`SUBCOMMANDS` 不变（square 已在列）；tab 补全第二参数可选模式（小增强，随做）。`/le checkin` 成功分支追加 `socialManager.recordCheckin(...)` 一行。

### 3.5 与 GuiNavigationManager 返回栈的配合

- 动态页右键 → 主页：`nav.pushBack(uuid, () -> square.open(page, FEED))`（照抄既有玩家头点击的闭包写法）；主页返回键 pop 回动态页原页。成就墙/设置链（profile→settings→back）不受影响。
- 自条目左键"看鼓励名单"是聊天输出，不入栈。
- quit 清栈行为不变（R-41 已有）。

### 3.6 触及文件清单

| 文件 | 改动 | 归属 story |
|------|------|-----------|
| `data/DatabaseManager.java` | 7 个新方法 + deletePlayerData 扩展 + createTables 建表 | US-SOC-1 |
| `resources/schema.sql` | 两表 DDL 文档 | US-SOC-1 |
| `core/social/SocialManager.java`（新） | 核心逻辑（~250 行） | US-SOC-2 |
| `LetsLearnEnglish.java` | DI 字段 + getter + 启动缓存预热一行 | US-SOC-2 |
| `core/manager/HotReloadManager.java` | reload 时 `socialManager.refreshCacheAsync()` 一行 | US-SOC-2 |
| `core/progress/Leaderboard.java` | RankingMode 加 FEED（+注释） | US-SOC-3 |
| `gui/EnglishSquareGUI.java` | FEED 分支渲染 + `FeedTextFormatter` 调用 + 模式按钮 + default-mode | US-SOC-3 |
| `listener/SocialListener.java` | FEED 点击路由 + 防抖 + 模式按钮槽 + 回栈闭包 | US-SOC-3 |
| `core/word/WordLearningManager.java` | `showSessionComplete` 追加一行 recordSessionDone | US-SOC-4 |
| `core/achievement/AchievementManager.java` | `tryUnlock` 新解锁分支追加一行 recordAchievement | US-SOC-4 |
| `command/LearnCommand.java` | square 可选参数 + checkin 成功一行 | US-SOC-4 |
| `listener/PlayerJoinListener.java` | join 摘要一行 + quit 编排点一行 | US-SOC-4 |
| `core/SoundManager.java` | cheer 键注册 | US-SOC-4 |
| `core/social/FeedTextFormatter.java`（新，或并入 SocialManager 静态内部） | 纯函数文案组装（可单测） | US-SOC-2 |
| `resources/config.yml` / `messages/zh.yml` / `messages/en.yml` / `CHANGELOG.md` | i18n / config / 变更日志 | US-SOC-5（编排者 fan-in） |
| `src/test/.../FeedTextFormatterTest.java`（新） | 纯逻辑单测 2~3 例 | US-SOC-5/2 |

**实施拆分与流建议（规模 M）**：US-SOC-1（表+DAO，独占 DatabaseManager）与 US-SOC-2（SocialManager+DI，只新增文件 + LetsLearnEnglish/HotReloadManager）可并行：先冻结接口（本文 3.1/3.2 的方法签名即冻结清单）；US-SOC-3（GUI+Listener，依赖 2 的缓存与 cheer API）与 US-SOC-4（4 个 hook 文件，依赖 2 的 recordEvent 签名）随后并行；US-SOC-5 编排者收口（i18n 双文件 + config + message config-version 12 + CHANGELOG + schema 复核）。AchievementManager 与 P3-3（成就重构域）**同文件冲突**：若两域同批实施，AchievementManager 串行给一方（建议社交先落一行 hook，P3-3 在其上重构）。

---

## 4. 依赖与风险

### 4.1 DB 容量与查询频率（F2-11 对照表）

| 面 | 估算 | 结论 |
|----|------|------|
| 写入频率 | 会话完成 ≤ 3 次/人日（合并后 ≤3 行/人日）+ 鼓励点击（人类点击率 + 750ms 防抖） | 均为小 UPSERT，同 `connectionLock` 串行，单语句为主；cheer 双语句包事务 |
| 读取频率 | 广场打开：**零**（缓存）+ 1 条有界已鼓励集合查询；每人每 5 分钟 1 次缓存刷新查询；每次 join 1 条摘要 count（异步） | 全部有界小查询 |
| 容量 | 七保留天 × 3 行/人日：百人服 ~2100 行；千人服 ~2.1 万行（仍小） | 无需分区/归档；`retention-days` 可调 |
| 主线程 IO 风险 | **无新增**（写全异步、读走缓存、摘要异步）；唯一的打开路径同步查询为 28-id 有界索引查询 | 明确对照 F2-11：不复刻"循环内每人同步查库"模式 |

### 4.2 与隐私域的交叉

- 四层同口径（1.6 表）：产生门 + SQL JOIN 过滤 + 点击守卫 + 摘要不泄露（摘要只发作者本人）。
- `getProfileSettingsReadOnly`（不建缓存条目，L-05）用于产生门与渲染门，防"只读展示污染 `getActivePlayerIds`"重演。
- `showResults` 细节行降级是本域新增的语义延伸，需在实现 PR 的 CHANGELOG 与 tester-code 检查单中显式列出。

### 4.3 与成就域（P3-3 互助成就）的接口约定

- **数据方向**：`SocialManager` **不引用** `AchievementManager`（防 P2-26 式 Core↔Core 环）；`AchievementManager` 单向依赖 SocialManager（同 tryUnlock→recordAchievement 方向一致）。
- **冻结接口**：
  - `SocialManager.getCheerStats(UUID) → record CheerStats(int given, int distinctTargets, int received)`（`given`=累计发出鼓励数；`distinctTargets`=发出对象去重数；`received`=收到鼓励数）。
  - `AchievementManager.checkHelpAchievement(UUID)`（P3-3 实现，判据建议：`distinctTargets >= 10`；**触发点**由 `SocialListener` 在 cheer 成功后按 listener 层编排调用，避免 SocialManager 反向依赖成就域——P3-3 未落地前该调用是 `getAchievementManager() != null` 守卫下的空转，或先加 stub）。
- `Achievement` enum 的 HELPFUL 条目归 P3-3 所有，本域只提供数据。

### 4.4 防滥用

| 面 | 防护 |
|----|------|
| 刷鼓励 | 唯一约束（一人一条一次）+ 750ms 防抖 + 无经济收益（明确不发币，杜绝 F1-01/F1-11 类旁路） |
| 小号对刷 | 有动机但零收益（互助成就用 distinct 阈值，成本 >> 收益）；接受残余风险并文档化 |
| 骚扰内容 | 无自由文本面（动态全自动生成），结构上没有内容审核需求 |
| 刷屏 | 结构聚合（3 行/人日）+ 摘要合并为单条计数消息（非每人每条） |
| 缓存陈旧点击 | 点击守卫复查（隐身/过期条目 → 明确 i18n 反馈，不写脏行）；DB 层 feed 行存在性在锁内复核（单连接串行，无 TOCTOU） |

### 4.5 其他依赖与风险表

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | 与 P3-3 同文件冲突（AchievementManager/Achievement） | 中 | 3.6 串行安排 + 冻结接口先行 |
| R2 | 与 P3-8 菜单域冲突（模块开关语义、主菜单） | 低 | social.enabled 真接线（四门槛）；不碰主菜单布局 |
| R3 | `RankingMode.FEED` 误传 `getTopPlayers` | 低 | default 分支返回空的现状兜底 + 契约注释 |
| R4 | join 摘要竞态（在线提醒后立即退服 → 重复提醒一次） | 低 | 宽容语义，注释说明 |
| R5 | SQLite 多子服动态流不共享 | 低 | 与榜单现状一致，文档化 |
| R6 | 缓存快照 5 分钟周期对"鼓励数"轻微滞后 | 低 | 写入后 5s 去抖刷新 + 点击者本地乐观 +1 |
| R7 | `/lea reset` 残行 | 低 | 3.1 顺序敏感三循环 + 验收用例覆盖 |

---

## 5. 验收标准（端到端人工用例）

**环境**：本服 SQLite，两名在线玩家 A、B（均可执行 `/le`）。

**主剧本（最小循环闭环）**：
1. A 执行 `/le word beginner spelling` 并自然走完 10 题 → 关闭结算。
   - 预期：**≤10 秒**后 B 打开广场（默认动态页）出现"A 完成了一次拼写会话 / 答对 X/10 / ❤ 鼓励 ×0"条目；A 再开一次完整会话 → 条目**原地合并**为"今天完成了 2 次学习会话"，不新增第二行（防刷屏）。
2. B 左键 A 的条目 → 听到 cheer 音效；lore 变为"❤ 鼓励 ×1 / ✔ 你已鼓励"。
3. A 在线 → **立即**收到"B 鼓励了你的学习动态！"消息 + 音效。
4. B 再左键同一条 → 收到"你已经鼓励过这条动态了喵~"，计数不变。
5. A 左键**自己**的条目 → 聊天显示"B 鼓励了你的动态！（共 1 个）"；无鼓励时显示 cheerers-none。

**附加用例**：
6. 离线回应：A 下线 → B 鼓励 A 的另一条动态（先让 A 完成一次会话/签到产生第二条）→ A 重新进服 → 收到"你不在的时候……收到了 1 个鼓励"；再次进出不重复提醒（已读标记）。
7. 隐私-隐身：A 设置 `showInSquare=false` → B 刷新广场后 A 的**全部条目消失**；B 在刷新窗口内点 A 陈旧条目 → "该玩家已隐身"提示且无写入；A 打开广场也看不到自己的条目（与榜单口径一致）。
8. 隐私-细节：A 设 `showResults=false` → B 看 A 的新条目只有"完成了一次学习会话"，无"答对 X/Y"；A 恢复 true 后细节回归。
9. 导航：B 右键条目 → 打开 A 主页 → 返回键回到**动态页原页/原模式**；A 隐身时右键 → 笑话页（现状）。
10. 成就/签到事件：A 解锁任一成就、执行 `/le checkin` → 分别出现成就条目（成就名按 B 的语言显示）/ 签到条目；同日第二条成就 → 合并显示"今日解锁了 2 个成就，最新：X"。
11. 混服语言：B 切英文（主菜单语言切换）→ 所有表情态文案、提醒消息均为英文（R-86m 同口径抽查 3 处）。
12. 重启/重载：重启服务器 + `/lea reload` → 动态与鼓励数完整保留；reload 后新动态仍能刷新可见。
13. 防抖：连点宏 5 次/秒点不同条目 → 750ms 内多余点击被吞（消息计数与 DB 行数一致）。
14. 重置：`/lea reset A` → `social_feed` 无 A 行、`social_cheers` 无 A 的发出记录、A 条目上他人给过的鼓励行一并清除；B 的"谁鼓励了我"名单不再出现被删的条目。
15. 功能开关：`social.enabled=false` → 重新打开广场无"动态"按钮且默认落玩家列表；完成会话不产生任何行；`/le square feed` 回退默认模式。

**自动/单测**：`FeedTextFormatter` 纯逻辑单测（合并文案选择、showResults 降级、语言选择）≥2 例；`mvn clean verify` 全绿（含既有测试）。

---

## 6. Story 拆分与规模汇总

| # | Story | 内容 | 规模 | 文件锁 |
|---|-------|------|------|--------|
| US-SOC-1 | 表结构与 DAO | schema.sql + createTables + 7 方法 + deletePlayerData 扩展 | M | data/DatabaseManager.java、resources/schema.sql |
| US-SOC-2 | SocialManager + DI | 核心逻辑 + FeedTextFormatter + DI/reload 接线 | M | core/social/*（新）、LetsLearnEnglish.java、HotReloadManager.java |
| US-SOC-3 | 广场 UI 与交互 | RankingMode.FEED + GUI 渲染 + Listener 路由/防抖/回栈 | M | Leaderboard.java、EnglishSquareGUI.java、SocialListener.java |
| US-SOC-4 | 事件 hook 与摘要 | 4 个产生点 + join 摘要 + quit 清理 + SoundManager 键 | S | WordLearningManager.java、AchievementManager.java、LearnCommand.java、PlayerJoinListener.java、SoundManager.java |
| US-SOC-5 | 编排者 fan-in | i18n 双文件（22 新键 + 1 改键，config-version 12）+ config 新段 + CHANGELOG + 测试补例 | S | zh.yml、en.yml、config.yml、CHANGELOG.md、src/test |

**总规模：M**（约 700~800 行新增，12 个触及文件 + 1 新类 + 2 新表）；4 个开发 story + 1 个 fan-in。执行顺序：冻结接口 → US-SOC-1 ∥ US-SOC-2 → US-SOC-3 ∥ US-SOC-4 → US-SOC-5 → 三 tester → 门禁。

---

## 7. 明确不做（v1 范围外）

1. **求助帖**：引入第 5 个聊天消费者与自由文本审核面（对照 F1-17 已欠的四态互斥账），性价比低；若将来做，建议做成"错题本词条一键求助 + 固定短语回应"，不做自由输入。
2. **好友系统**：请求/接受/删除状态机 + 独立表，对核心循环增益小；待动态流跑通后按数据再评估。
3. **组队学习**：DuelRoom 级房间状态机，超本域体量。
4. **鼓励发金币/被鼓励发金币**：经济面风险（daily-cap 旁路、对刷），明确不发。
5. **动态实时推送（WebSocket/Proxy 广播）**：v1 用缓存刷新 + 进服摘要足够。
6. **"谁在学"排序改造**（活跃时间排序）：非本域最小循环必需，留菜单/列表域。

---

## 8. 规划期发现（只记录，未修复）

| # | 位置 | 现象 | 等级 | 建议 |
|---|------|------|------|------|
| PF-SOC-01 | `gui/EnglishSquareGUI.java:227` | 榜单模式下自己头像 lore 的 `getDatabaseManager().getCoinsEarned(...)` 是**主线程同步查库**（GUI 打开路径），属 F2-11 同类面 | 中 | 本域实现勿复制该模式（feed 走缓存）；建议顺带修（改读缓存或异步预取），随实现 PR 或单独 backlog |
| PF-SOC-02 | `gui/EnglishSquareGUI.java:40` vs `listener/SocialListener.java:51` | 广场玩家区 28 槽布局有两份平行常量（GUI `PLAYER_SLOTS` / Listener `SQUARE_PLAYER_SLOTS`），且不受 `gui-layout` 管辖；feed 模式将引入第三个消费者 | 低 | 实现时收敛为单一常量源（如以 `EnglishSquareGUI.PLAYER_SLOTS` 为唯一真相，listener 引用之），避免三处漂移 |
| PF-SOC-03 | `resources/config.yml` `gui-layout.profile.settings-*` vs `gui/ProfileGUI.java:251-289` / `listener/MiscGUIListener.java:95` | 隐私设置三开关的 slot 配置键（11/13/15）**无人读取**——GUI 与 listener 双侧硬编码；与 P2-25（scenario-select.back-button）同类死配置 | 低 | 归入 Phase 3 配置面统一清理（要么接线要么删键）；本域不依赖该配置 |
| PF-SOC-04 | `core/progress/Leaderboard.java:118` | 若 `RankingMode` 扩入 `FEED`，`getTopPlayers` 的 `default -> emptyList` 分支会**静默**吞掉误传——调用方契约需注释显式约定"FEED 永不进入本方法" | 低 | 随 US-SOC-3 落注释；tester-arch 抽查该契约 |
| PF-SOC-05 | `listener/PlayerJoinListener.java:26-31` | join 路径的 `loadPlayerData/loadPlayerSettings` 均为**主线程同步查库**（每人 2~3 条 SQL），与 F2-11 同族（join 高峰时的主线程压力面） | 低 | 本域摘要已避让（异步）；join 加载整体迁移属跨域决策（F2-11 同批处理），此处仅记录 |

---

*本文件为 Phase 3 规划期只读产出，方案自定，未修改任何源码/配置；接口签名（3.1/3.2/4.3）为实现期冻结基准。*
