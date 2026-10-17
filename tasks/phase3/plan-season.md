# Phase 3 规划：赛季与班级 + 跨服榜（域 5）

- 日期： 2026-10-13
- 基线： 1.0.7-SNAPSHOT（P2 批次 commit `17b0c02` 之后，工作树含 Phase 3 规划只读产出）
- 来源： `tasks/plan-roadmap-usable-to-delightful.md` 第 8 节方向清单第 5 条（赛季与班级）与第 9 条（跨服榜）；`tasks/p2-polish-execution-plan.md` 第五节域 5
- 规划纪律： 本轮为只读规划，未修改任何源码/配置；唯一产出为本文件；规划期发现见第 8 节
- 规模估计： 批次 A（跨服榜 + 赛季）= **M-L**（约 800 行新增）；批次 B（班级）= **M**（约 500 行新增）；合计 **L**，**5 个开发 story + 2 个 fan-in**，两批次拆分见 1.1

---

## 1. 操作逻辑

### 1.1 总体裁决与批次拆分

**三块内容的相互关系**：

- **跨服榜是"零成本先行"的验证面**（roadmap 原文）：现有五条榜单 SQL（`getTopCoins` / `getTopWordsLearned` / `getTopCombo` / `getRankedPlayers` / `getRankedPlayersForPeriod`）全部是"查全表取 TOP N"，**天然就是跨服榜**——多服连同一 MySQL 库即共享。真正要修的只有两处本地化假设：(a) `%lle_rank%` 的内存快照只含本服（`Leaderboard.scores` 启动载入 + 本服增量，从不重载）；(b) `ProxyMessenger` 的跨服消息协议读写不一致（见第 8 节 PF-SEA-01，**跨服消息通道实际大概率不可用**，是赛季广播的前置必修项）。
- **赛季 = 时间窗口视图**，不是新累加账本：`player_sessions` 按日聚合（`UNIQUE(uuid, session_date)`），赛季榜直接复用 `getRankedPlayersForPeriod(起始日, 结束日)` 查询模式——这就是"直接用现有表加 WHERE 时间窗"的可行性结论：**完全可行**，无需赛季累加分表。唯一新表是"结算台账"（赛季行 + 奖励发放行），行数量级 = 每季 1 + top N。
- **班级砍到最小**（裁决见 1.5）：v1 做"管理型班级"（OP/老师创建、加减成员 + 班级榜），不做玩家自建/邀请/审批状态机。

**拆批结论（任务授权自行拆分）**：

| 批次 | 内容 | 顺序依赖 |
|------|------|---------|
| **批次 A：跨服榜 + 赛季**（US-SEA-1/2/3 + fan-in） | 跨服底座先行（SEA-1）→ 赛季定义与赛季榜（SEA-2）→ 赛季结算与仪式感（SEA-3） | SEA-1 冻结接口先行；SEA-2/3 依赖 SEA-1；三 story **串行**（文件重叠多，无并行收益） |
| **批次 B：班级**（US-CLS-1/2 + fan-in） | 班级数据层与管理命令（CLS-1）→ 班级榜与 GUI（CLS-2） | 依赖批次 A 的"时间窗查询 + 缓存刷新"基础设施；可整批延后而不阻塞 A |

两批次合计规模 L；若要求压缩，**批次 B 可整批延后到下一版本**（班级不阻塞任何赛季功能）。

### 1.2 赛季定义（编号、起止、存储）

- **窗口模型：锚点 + 固定时长（本地日粒度）**。
  - `season.anchor-date`（配置，`yyyy-MM-dd`，服务器时区本地日解释）+ `season.duration-days`（默认 30）。
  - `seasonId(today) = floor(ChronoUnit.DAYS.between(anchor, today) / duration) + 1`（1-based）。
  - 第 N 季窗口 = `[anchor + (N-1)*duration, anchor + N*duration - 1]`，**闭区间**，日期比较用 `yyyy-MM-dd` 字符串（与周/月榜、`session_date` 列完全同构）。
  - `today < anchor`（锚点在未来，含首次误配）：返回 `seasonId = 0`，全系统视为"赛季未开启"，所有赛季功能休眠（含结算调度与 PAPI 输出空值）。
- **不采用"上次切换 + 30 天"**：那种模型在停服/漂移后边界会累积偏移；锚点模型是纯函数推导，任何时刻（启服、午夜、PAPI 调用）重算结果一致，跨服各子服只要**时区配置一致**即推导出同一赛季号。
- **时区裁决**：锚点与窗口一律走 `LocalDate.now()`（服务器本地时区），与 `player_sessions.session_date`、签到、挑战、`AchievementManager` 的日期口径同源。**多服必须同 host 时区**（proxy 网络运维约定，写入 README/配置注释；不同时区的子服赛季边界会错位，见 4.6 R3）。不复制 `AchievementManager.isAchievementEligible` 的 Asia/Shanghai 硬编码先例（见第 8 节 PF-SEA-06）。
- **存储**：`seasons` 表只存"需要结算/回顾的行"，不预建未来季。写入时机：结算器保证"至少有已经跨越的季的行"（UPSERT，见 3.1）。行 = `season_id`(PK) + `start_date` + `end_date` + `settled_at`（NULL=未结算）+ `top_snapshot`（结算时写入的 top10 JSON 文本，供"上一季回顾"不依赖重聚合）。
- **停服跨季处理（catch-up）**：不做"逐日补跑"（不需要——窗口聚合是查询驱动，历史数据躺在 `player_sessions` 里，无需补算任何"缺的聚合"）。两个触发点调用同一个幂等的 `settleIfNeededAsync()`：
  1. **onEnable 启动异步**（在 `DatabaseManager.initialize()` 与 `ProxyMessenger.register()` 之后，挂在现有排行榜异步加载任务旁）；
  2. **午夜自递归任务体**（`LetsLearnEnglish.scheduleNextMidnightRollover`，R-75 模式的复用点，见 3.2）。
  幂等推导：游标 = `seasons` 表 `MAX(season_id) WHERE settled_at IS NOT NULL`，目标 = `seasonId(today) - 1`，逐季结算 `cursor+1 .. 目标`（长停服跨多季时逐季补，无数据季只写 settled 行不发奖）。
- **首次启用语义**：`seasons` 表为空时**不追既往**——把当前季 ID 直接写成"已建立、未结算"的行，历史 30 天不当作"上一季"补结算（防首次开启即大规模补发奖）。写入代码注释。

### 1.3 赛季重置语义（裁决：只切榜单窗口，零数据重置）

| 数据 | 赛季行为 | 理由 |
|------|---------|------|
| 赛季榜（学习分 30 天窗 SUM） | 新赛季窗口内无数据 ⇒ 显示为空，**自动"重置"** | 窗口即重置，无需任何清零写操作 |
| 总榜（`total_score` / `total_coins_earned` / `total_words_learned` / `longest_streak`） | **保留不动** | 需求原文"总榜保留"；且这些都是 lifetime 列，动它们会牵连成就（`COINS_*`/`WORDS_*`）与 PAPI |
| `player_sessions` 历史行 | 保留（赛季结算的唯一数据源） | 结算台账 `top_snapshot` 只做回顾快照，不替代原始数据 |
| 段位（rank tier） | **不做** | 段位是新的机制面（升降级规则 + 展示体系 + 防掉段焦虑设计），无运营输入支撑；v1 用"赛季名次 + 奖励分层"替代荣耀出口。留 roadmap |
| 掌握度 / 错题本 / 签到 streak / 成就 | **完全不动** | 与赛季无耦合 |

**结论一句话**：赛季切换在数据面是**零写操作**（除结算台账），风险面极小；这是"只切窗口"方案相对"赛季分独立累加"的最大优势。

### 1.4 赛季结算与奖励

- **结算时机**：赛季结束日次日的午夜任务（常态）+ 启服 catch-up（停机跨季）。结算读数 = 结算时刻 DB 中的窗口数据（`player_sessions` 的行在窗口结束后不再增长——同服内 `recordSession` 是主线程同步写；他服 23:59:5x 的末班会话存在毫秒级残余竞态，接受并注释）。
- **多服单发保护（关键设计）**：同一 MySQL 库的多台子服都会各自触发结算——用 **DB 条件 UPDATE 抢占**：`UPDATE seasons SET settled_at=?, top_snapshot=? WHERE season_id=? AND settled_at IS NULL`，`executeUpdate() == 1` 的服获得"结算权"写台账并发奖；其余服直接跳过（不报错）。**不引入 `season.settlement-server` 配置**（运维配错 = 重复发奖，抢占式零配置更稳）。
- **奖励分层（config 可配）**：`top1 / top2-3 / top4-10 / 参与奖`（参与 = 赛季窗内在本服/任意服至少有一次会话记录，即 `player_sessions` 窗口内存在该 uuid）。v1 奖励类型 = **金币**（需 Vault；无 Vault 时只发公告不发钱，与全项目"无经济则静默降级"一致）；命令奖励列 future（注释预留）。
- **经济门裁决**：赛季奖励**不走 `money.daily-cap`**（`depositCappedReward` 会把 5000 的大奖 clamp 到当日剩余额度），走直发：`eco.depositPlayer` + `DatabaseManager.recordCoinsEarned`（lifetime 记账），**不注入** `todayCoinsCache`（不挤占当日答题额度）。理由：赛季奖励是一次性、排名门槛、有台账幂等防重，与"可重复的日常收益"（挑战/签到，已收口 cap）语义不同。若用户要求纳入 cap，实现期改调 `depositCappedReward` 即可（一行）——列入待用户决策。
- **发放路径（在线/跨服/离线全覆盖）**：
  1. 结算服写 `season_rewards` 台账（每人一行：season_id / uuid / rank / score / reward_coins / claimed=0），**台账先落库**；
  2. 结算服经 ProxyMessenger 广播 `SeasonSettled` 子通道（含 season_id）——**ALL 各服（含自己，含 origin 过滤）收到后在主线程本地发放**：检查本服在线玩家的 `getPendingSeasonRewards(uuid)`，命中则发钱 + 标记 `claimed`（条件 UPDATE 幂等）+ 个人名次消息；
  3. 结算时不在线/不在任何服的玩家：**下次进服补发**——`PlayerJoinListener` 挂 `claimPendingRewards(player)`（对齐 `consumeOfflineDuelNotice` / join 摘要的既有消费模式）。玩家在任意子服进服都能领（台账共享）。
- **重复进入不进二次**：`markSeasonRewardClaimed` 用条件 UPDATE（`WHERE claimed=0`，affected=1 才发）——双击/双服竞态均防住（对齐 R-02 双击重发教训）。
- **首次结算无人在线的公告丢失语义**：启服 catch-up 时无人在线，全服公告不补播（公告面向在线者）；个人名次与奖励经 join 摘要送达。写入注释。

### 1.5 班级子域裁决：**做进本域（批次 B），但收窄为"管理型班级"**

裁决与理由：

1. **做进本域而非切给 P3-6（报告域）**：
   - 班级榜 = "时间窗聚合 + 成员集合过滤"，正是本域"赛季榜查询基建"（`getRankedPlayersForPeriod` + `IN (uuid 集合)`）的直接第二个消费者；切给域 6 会迫使域 6 重新发明查询层与缓存刷新，双份实现。
   - 班级 GUI（班级榜 / 成员列表）是"界面"形态，与域 6 的"报告"形态（周报 + 一键导出 + `/api/player`）不同向；"老师视角"的报告部分留给域 6 **读表消费**（见 4.2 单向接口约定）。
2. **收窄为管理型（不做玩家自建）**：创建/删除班级、加/减成员全部收在 `/lea class …`（admin 权限），老师 = 受信 OP——中国教育服场景与"管理员开好班级"的运营现实契合。**不做**邀请 / 审批 / 玩家退班申请——那是社交状态机（与域 4 裁决"好友系统缓做"同一判断，避免与广场社交域抢资产）。
3. **备选方案（若编排者要压缩总规模）**：整批延后；此时域 6 若做班级需自建 `classes/class_members`（表结构见 3.1，不重复设计）。

班级 v1 行为定义：

- **实体**：班级（班名唯一，显示名可选）+ 成员（一个玩家 v1 只能属于一个班——多班归属 = 复杂化，明确不做；加入新班自动退出旧班，admin 命令提示）。
- **班级榜口径**：**当前赛季窗内**班级成员的聚合得分（复用赛季窗口，单口径不搞多档）；榜内排序与展示复用 `LeaderboardEntry`；**隐身过滤沿用**（`showInSquare=false` 的成员在同学视角不列出/不计入——隐私开关跨界面一致性优先；老师的全量需求由 `/lea class list <班>` 管理路径满足，该路径不受隐身过滤）。
- **上限**：`class-system.max-members`（默认 50），`max-name-length`（默认 32）；班名冲突 / 容量满 / 玩家不在班 等分支全部 i18n 提示。
- **跨服**：班级表在共享 MySQL 下天然跨服（成员可分布多子服，班级榜跨服正确）；SQLite 单机降级（与榜单同现状，文档化）。

### 1.6 跨服榜（零成本先行）

- **天然共享面**（UUID 全局一致，无需任何代码）：五条榜单 SQL、`player_profiles`（隐身过滤 LEFT JOIN 跨服同样生效）、`player_sessions`（周/月/赛季窗口榜）、成就/签到/挑战数据。
- **需修正的三个本地化假设（US-SEA-1 范围）**：
  1. `Leaderboard.scores` 内存快照（`%lle_rank%` 数据源）：启动载入一次 + 本服 `updateScore` 增量（R-42 修复后语义），**从不重载**——多服下 `%lle_rank%` 看不到他服进度。修法：`refreshCache()`（5 分钟异步班车）里追加 `reloadRankScores()`（`loadFromDB` 全量重载）。竞态注释：重载的 clear→fill 窗口内到达的 `updateScore` 增量会丢一次（影响 `%lle_rank%` 单次偏差 ≤5 分钟），接受并注释（E-10 的 `RANK_SNAPSHOT_LIMIT=1000` 语义不变——跨服大服可能超 1000 人，超出者 `%lle_rank%` 未上榜，文档化；若运营需要可将 1000 提为配置，故事内可选）。
  2. `ProxyMessenger` 协议读写不匹配（PF-SEA-01）+ 无 origin 标识（广播回环去重）——扩展方案见 4.3。
  3. `proxy.server-name` 新配置（本子服名，空 = 单服/仅本地广播）——origin 去重与未来定向发送的基础。
- **SQLite 服降级**：单机 = 现状（每服独立库，榜单只含本服）；`ProxyMessenger.register()` 已静默跳过；赛季广播走"本地 broadcast + （registered ? Forward : 无）"双路径（见 2.2），无代理时行为不变化。

### 1.7 存档、归档与容量

- `seasons` 表：每季 1 行，永恒（可忽略）。`season_rewards` 表：每季 ≤ 全部参与者行数（参与奖发放时每人一行）——若参与奖开启且大服，行数 = 每季活跃人数（千级），**可接受、不清理**。
- **不做** `player_sessions` 归档/删除（它是既有运营数据 + 学期/月榜/成就 streak 的共同底座）；若将来瘦身需求出现，单列 backlog（不在本域）。
- 赛季榜缓存：`Leaderboard` 加 `seasonCache`（volatile List，随 5 分钟班车刷新，容量沿用 `DEFAULT_CACHE_LIMIT=50`），GUI/PAPI 读缓存——**不复制**广场周/月榜"开界面主线程查库"的旧模式（PF-SEA-04 同族问题）。

---

## 2. 用户体验

### 2.1 榜单界面的赛季 tab（LeaderboardGUI 第 4 个 tab）

`LeaderboardGUI` 现为 3 tab（coins/words/combo，slot 0/1/2）——新增 **tab-season**（`gui-layout.leaderboard.tab-season`，默认 slot 3，行 0 空闲位）。入口仅此一个 + PAPI + `%lle_season%`：

- 为什么不做广场第 6 模式按钮：广场 4 模式（周/月/总/广场）是"学习分窗口族"，但 slot 6 已被域 4（社交）规划为"动态"模式，slot 7 单独放赛季会让按钮密度过高、且需改 `SocialListener` 的模式分支；`/le leaderboard` 的 tab 是任务原文要求的"榜单界面的赛季 tab"，语义最顺。若用户要广场入口，S 级追加（读 1 节 `RankingMode` 扩展已为其留好路）。
- tab-season 三行信息架构（54 格空余空间复用现有 `ENTRY_SLOTS` 10 格布局不变）：

```
┌──────────────────────────────────────────┐
│ 排行榜 / Leaderboard | 赛季                 │
├───┬───┬───┬────────┬──────────────────────┤
│0金│1词│2连│3赛季★  │                      │
├───┴───┴───┴────────┴──────────────────────┤
│ slot4: 赛季信息牌（PAPER）                  │
│   "第 3 赛季"                              │
│   "剩余 12 天"                             │
│   "你的名次: #7 (得分 1234)"               │
├──────────────────────────────────────────┤
│ 10/16/19/25/28/34/37/43/46/52: top10 头像  │
│ 8: 返回                                    │
└──────────────────────────────────────────┘
```

- 空态（新赛季第 1 天/未开启）：slot22 现有空态图标文案"本赛季还没有人上榜，快去学习成为第一！"；`season.enabled=false` 时 tab 按钮不渲染（真 gating，R-55i 教训）。
- 剩余时间：`剩余 {days} 天 {hours} 小时`（天数为主、小时细节在 lore 第二行；`achievement-wall.time-remaining` 的 `{time}` 渲染先例可参考）。

### 2.2 仪式感（开始 / 结束）

| 时机 | 表现 | 送达面 |
|------|------|--------|
| 赛季开始（结算完成后立即） | 全服广播 `season.started-broadcast`（"第 N 赛季开始了！持续 30 天，冲榜赢奖励"）+ `complete` 音效 | 结算服在线者即时 + 经 `SeasonSettled`/`SeasonStarted` 跨服子通道在**各子服本地广播**（origin 过滤防回环重复） |
| 赛季结束（结算完成） | 全服广播 `season.ended-broadcast`（冠军名 + 分数）+ 在线获奖者收到个人消息 `season.reward-earned`（名次 + 金额） | 同上 |
| 错过的玩家 | join 摘要：`season.reward-pending-join`（"上个赛季你获得第 {rank} 名，奖励 {amount} 金币已发放"——发钱与摘要同一时刻） | join 消费路径（跨服可领） |
| 回顾 | tab-season 信息牌 + "上一季冠军"lore（读 `top_snapshot`，免重聚合） | GUI 内 |

**不做**独立"结算 GUI / 赛季回顾大页面"：仪式感收敛为"广播 + 个人消息 + tab 内信息"，避免新 GUI 面（如用户要，S 级追加一个只读回顾页）。

### 2.3 i18n key 草案（`zh.yml` / `en.yml` 双文件，`MESSAGE_CONFIG_VERSION` 11 → 12）

| key | zh 草案 | en 草案 |
|-----|--------|---------|
| `season.tab` | `&6赛季榜 / Season` | `&6Season / 赛季榜` |
| `season.title` | `&6&l第 {season} 赛季` | `&6&lSeason {season}` |
| `season.remaining` | `&7剩余: &e{days} 天 {hours} 小时` | `&7Remaining: &e{days}d {hours}h` |
| `season.your-rank` | `&7你的名次: &e#{rank} &7(得分 {score})` | `&7Your rank: &e#{rank} &7(score {score})` |
| `season.unranked` | `未上榜` | `Unranked` |
| `season.empty` | `&7本赛季还没有人上榜，快去学习成为第一！` | `&7No one on the board yet — be the first!` |
| `season.disabled` | `&7赛季系统未启用` | `&7Season system is disabled` |
| `season.started-broadcast` | `&6&l第 {season} 赛季开始了！&7持续 {days} 天，冲榜赢奖励！` | `&6&lSeason {season} has begun! &7{days} days — climb the board!` |
| `season.ended-broadcast` | `&6&l第 {season} 赛季结束！&7冠军: &e{top1} &7({score1} 分)` | `&6&lSeason {season} ended! &7Champion: &e{top1} &7({score1} pts)` |
| `season.reward-earned` | `{prefix}&a赛季奖励已发放：&e{amount} &a金币（第 {season} 赛季第 {rank} 名）` | `{prefix}&aSeason reward paid: &e{amount} &acoins (Season {season}, rank {rank})` |
| `season.reward-pending-join` | `{prefix}&a上个赛季你获得第 {rank} 名，奖励 &e{amount} &a金币已发放！` | `{prefix}&aLast season you ranked #{rank} — &e{amount} &acoins paid!` |
| `season.participated-reward` | `{prefix}&a赛季参与奖已发放：&e{amount} &a金币` | `{prefix}&aSeason participation reward: &e{amount} &acoins` |
| `season.proxy-settled` | `{prefix}&7第 {season} 赛季已结算，奖励正在发放…`（跨服台账同步时的提示，可选） | `{prefix}&7Season {season} settled; rewards are being paid…` |
| `class.title` | `&6&l我的班级` | `&6&lMy Class` |
| `class.board-title` | `&6&l班级榜 - {class}` | `&6&lClass Board - {class}` |
| `class.not-in-class` | `&7你还没有加入任何班级，请联系管理员` | `&7You are not in a class yet; ask an admin` |
| `class.empty` | `&7班级还没有成员` | `&7No members yet` |
| `class.created` / `class.deleted` / `class.member-added` / `class.member-removed` / `class.member-full` / `class.name-exists` / `class.not-found` / `class.name-invalid` | admin 命令反馈族（8 键，实现期落稿） | 同左 |
| `proxy.server-name-missing` | `&7未配置 proxy.server-name，跨服广播使用兼容模式`（首次 WARNING 用，或仅控制台） | 同左 |

### 2.4 config.yml 新键草案

```yaml
# ============================================================
#  赛季系统 (Season System)
# ============================================================
season:
  # 是否启用赛季（关闭时：无结算调度、无赛季 tab、无广播、PAPI 返回空）
  enabled: false          # 默认关：运营属性功能，先让用户确认再开（对齐 money.enabled 先例）
  # 单季时长（天）。所有边界由 anchor-date 纯函数推导，停服/漂移不累计误差
  duration-days: 30
  # 首季开始日（服务器本地时区，yyyy-MM-dd）。修改本键 = 重定义全局赛季轴，慎改
  anchor-date: "2026-11-02"
  # 结算奖励分层（金币；需 Vault，无 Vault 只公告不发钱）
  rewards:
    enabled: true
    top1: 5000.0
    top3: 2000.0          # 第 2-3 名
    top10: 500.0          # 第 4-10 名
    participated: 0.0     # 参与奖（>0 时窗口内有过会话即发）
  # 开始/结束是否全服广播（跨服经 ProxyMessenger，见 proxy.server-name）
  announce: true

# ============================================================
#  班级系统 (Class System)
# ============================================================
class-system:
  enabled: false
  max-name-length: 32
  max-members: 50

# proxy 段新增：
proxy:
  # 本子服在代理网络中的服务器名（与 BungeeCord/Velocity 配置一致）。
  # 用于跨服广播回环去重；留空 = 单服模式（仅本地广播）
  server-name: ""
```

注：主 config（`config-version: 2`）无迁移机制（`ConfigManager` 仅对 messages 做版本覆盖），新键靠各读取点 `getX(key, default)` 兜底——无需版本迁移，注释写明。

### 2.5 PAPI 占位符（`LLEPapiExpansion`）

| 占位符 | 值 | 数据源 |
|--------|----|-------|
| `%lle_season%` | 当前赛季号（如 `3`）；未启用/未开启返回空串 | 纯计算（零 IO，可缓存无关） |
| `%lle_season_days_left%` | 剩余天数 | 纯计算 |
| `%lle_season_rank%` | `#7` 或未上榜文本 | `seasonCache` 内存查（top 50 内可见；之外未上榜，与 `%lle_rank%` 的 1000 上限同语义文档化） |

三个占位符一律套用既有 R-43 缓存机制（30s TTL / 隐私门禁：非本人查看沿用 `showInSquare`/`showResults` 规则）。`season.enabled=false` 时返回空串（真 gating）。

---

## 3. 实现要点

### 3.1 数据模型（`schema.sql` 同步 + `DatabaseManager.createTables()` 双处）

```sql
-- 赛季记录（结算台账头）：每季 1 行
CREATE TABLE IF NOT EXISTS {prefix}seasons (
    season_id INT PRIMARY KEY,          -- 由锚点纯函数推导，1-based
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,             -- 闭区间（与周/月榜 getRankedPlayersForPeriod 口径一致）
    settled_at BIGINT,                  -- NULL = 未结算；结算抢占 = 条件 UPDATE 本列
    top_snapshot TEXT                   -- 结算时 top10 JSON（回顾用，免重聚合）
);

-- 赛季奖励发放台账：防重复 + 跨服/离线补发队列
CREATE TABLE IF NOT EXISTS {prefix}season_rewards (
    season_id INT NOT NULL,
    uuid VARCHAR(36) NOT NULL,
    rank_position INT NOT NULL,         -- 注意：列名避开 SQL 保留字 rank
    score BIGINT NOT NULL,
    reward_coins BIGINT NOT NULL,       -- 单位"分"（对齐 RewardEngine 厘级记账惯例）
    claimed BOOLEAN DEFAULT 0,
    claimed_at BIGINT,
    PRIMARY KEY (season_id, uuid)
);

-- （批次 B）班级
CREATE TABLE IF NOT EXISTS {prefix}classes (
    class_id INTEGER PRIMARY KEY {AUTOINCREMENT|AUTO_INCREMENT},
    class_name VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(64),
    created_by VARCHAR(36) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS {prefix}class_members (
    class_id INT NOT NULL,
    uuid VARCHAR(36) NOT NULL,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY (class_id, uuid)
);
-- 辅助索引（本域顺带补的既有表索引，见 PF-SEA-02/03）：
-- player_sessions(session_date)：周榜/月榜/赛季榜/成就 streak/日历共同受益
-- 双后端安全写法：SQLite "CREATE INDEX IF NOT EXISTS"；
-- MySQL 用裸 "CREATE INDEX" + catch 吞 "Duplicate key name"（8.0 不支持 IF NOT EXISTS）
```

**日期列比较**：沿用字符串传参（`stmt.setString(n, localDate.toString())`），SQLite/MySQL 双后端实测先例（`getRankedPlayersForPeriod`）。

**新查询方法（`DatabaseManager` 或 `PlayerDataStore`，实现期定，建议 DatabaseManager 收口 SQL）**：

- `ensureSeasonRow(seasonId, start, end)`——`INSERT OR IGNORE` / `INSERT IGNORE` 双分支（对齐 `persistLongestStreak` 先例）。
- `tryClaimSettlement(seasonId, settledAt, topSnapshot) -> boolean`——纯 UPDATE 条件抢占（无后端差异）。
- `insertSeasonRewards(List<Row>)`——批量 INSERT（台账行写前先 `deleteSeasonRewards(seasonId)` 兜底？不需要：抢占者唯一，直接 INSERT）。
- `getPendingSeasonRewards(uuid) -> List<Row>` / `markSeasonRewardClaimed(seasonId, uuid) -> boolean`（`WHERE claimed=0` 条件 UPDATE）。
- `getSettledSeason(seasonId) -> Row`（读 `top_snapshot` 回顾）。
- 赛季榜聚合：**复用** `PlayerDataStore.getRankedPlayersForPeriod(count, start, end)`（零新 SQL）。
- （批次 B）`getRankedPlayersForPeriodFiltered(count, since, until, Collection<UUID> members)`——在上述 SQL 加 `AND s.uuid IN (?,?…)` 动态占位符；**空集合早退**（防 `IN ()` 语法错误）；成员列表 ≤ 50（max-members），无 SQL 长度风险。

**UPSERT 双后端对照（F2-01 教训，全部新 SQL 遵守）**：

| 语义 | SQLite | MySQL |
|------|--------|-------|
| 插入忽略 | `INSERT OR IGNORE` | `INSERT IGNORE` |
| UPSERT | `ON CONFLICT(...) DO UPDATE SET c=excluded.c` | `ON DUPLICATE KEY UPDATE c=VALUES(c)` |

**所有新 SQL 校验清单**：全程 `synchronized (databaseManager.getConnectionLock())`（F0-01/F0-02 收口约定）；异常吞掉记 WARNING（既有惯例）；MySQL `ONLY_FULL_GROUP_BY` 合规（聚合列全 MAX/SUM 包裹，参考 `getRankedPlayersForPeriod` 的写法）。

### 3.2 窗口计算与调度

- 新类 `core/season/SeasonManager.java`（建议包位对齐 `core/progress`/`core/manager` 惯例，实现期定；**内部服务类，不新增独立包**时可放 `core/manager`）：
  - `record SeasonWindow(int id, LocalDate start, LocalDate end)`（无副作用纯数据）。
  - `int currentSeasonId(LocalDate date)` / `SeasonWindow windowOf(int id)` / `long millisUntilEnd()`（纯函数，可随时调用，PAPI 直接消费）。
  - `void settleIfNeededAsync()`——异步跑：读游标 → 逐季（`ensureSeasonRow` + 聚合 top N → `tryClaimSettlement` 抢占 → 抢占成功则写台账 + 主线程回投"广播 + 本地发放在线者"）→ 完成后重刷 `seasonCache`。
  - `void claimPendingRewards(Player)`——join 路径（先异步读 unclaimed，再主线程发钱 + 条件标记）。
- **调度挂载点（复用午夜自递归模式，R-75）**：
  - `LetsLearnEnglish.onEnable`：在现有排行榜异步加载任务（`LetsLearnEnglish.java:188-191`）旁挂 `seasonManager.settleIfNeededAsync()`（此时 messenger 已注册、DB 已初始化）。
  - 午夜任务体（`scheduleNextMidnightRollover`，`LetsLearnEnglish.java:476-498`）：现有 rollover 逻辑后追加 `seasonManager.settleIfNeededAsync()`——**只在"跨未跨季边界"时真干活**（纯日期检测零 IO：`currentSeasonId(today) != currentSeasonId(yesterday)`；避免每日白跑查库，也呼应 F2-11"午夜主线程查库"教训——结算 IO 全在异步任务里）。
  - `onDisable`：无新增资源（无自持任务），仅确保 `settleIfNeeded` 在途任务不崩（结算任务本身短平快；`isEnabled()` 守卫）。
- **线程模型**（对齐 plan-social 3.2 的"异步快照 + 主线程投递"模式，F2-11 教训）：
  1. 调度触发（主线程）→ 只做日期纯计算判断；
  2. 结算 IO（异步任务）：查聚合 / 抢占 / 写台账；
  3. 回主线程：`Bukkit.broadcastMessage`（含音效）、Vault `depositPlayer`、玩家消息；
  4. 标记 claimed 可在发钱后同主线程同步写（单条 SQL 快，先例 `persistLongestStreak`）或再异步——实现期依代码惯例定，防重靠条件 UPDATE 而非时序。

### 3.3 ProxyMessenger 扩展（US-SEA-1 核心，见 4.3 详述）

- 修 `onPluginMessageReceived` payload 解析（对齐官方 Forward 格式：subChannel → `readShort` 帧 → 帧内 `readUTF(data)`）；
- 子通道扩充：`SeasonSettled`（payload：seasonId）、`SeasonStarted`（payload：seasonId）——复用既有 `sendForward("ALL", …)` + 每服本地广播模式；
- payload 前置 `originServerName`（读 `proxy.server-name`），接收端 `origin == self` 时跳过广播（回环去重）；`server-name` 为空时回环语义退化为"可能重复"（配置注释说明）——**实现期必须双服实测**确认 Forward "ALL" 是否回环（验收用例含两种配置）。
- 发送前提：至少一个在线玩家（`getAnyOnlinePlayer`，既有行为——赛季结算发生在午夜/启服，0 人在线时 Forward 静默跳过，此时本地也无接收者，语义自洽）。

### 3.4 触及文件清单

| 文件 | 改动 | 归属 story |
|------|------|-----------|
| `data/DatabaseManager.java` | 新表建表 + session_date 索引（双后端安全写法）+ 6 个新方法 | SEA-1/3、CLS-1 |
| `resources/schema.sql` | 同步 4 张新表 + 索引 | SEA-4/CLS-3（编排者） |
| `core/ProxyMessenger.java` | 协议修正 + origin + 新子通道 + `sendCrossServer(subChannel, data)` | SEA-1 |
| `core/progress/Leaderboard.java` | `seasonCache` + `reloadRankScores()`（refreshCache 扩展） | SEA-1/2 |
| `data/PlayerDataStore.java` | `getRankedPlayersForPeriodFiltered`（CLS）；确认无其他改动 | CLS-1 |
| `core/manager/SeasonManager.java`（新） | 窗口计算 / 结算 / 补发（也可放 `core/season/`，DI 由 `LetsLearnEnglish` 承载，对齐既有手动 DI） | SEA-2/3 |
| `LetsLearnEnglish.java` | DI + 两个调度挂载点 + onEnable 结算 | SEA-3 |
| `gui/LeaderboardGUI.java` | tab-season + 信息牌 + 空态 | SEA-2 |
| `listener/SocialListener.java` | leaderboard tab 第 4 分支（读配置槽位） | SEA-2 |
| `listener/PlayerJoinListener.java` | `claimPendingRewards` 消费点（一行，对齐 `consumeOfflineDuelNotice` 模式） | SEA-3 |
| `expansion/LLEPapiExpansion.java` | 3 个占位符 + isKnownParams 扩展 | SEA-2 |
| `config/ConfigManager.java` | 无需改（`MESSAGE_CONFIG_VERSION` 12 随 fan-in 升） | SEA-4 |
| `resources/config.yml` | season / class-system / proxy.server-name 段 | SEA-4/CLS-3 |
| `core/duel/DuelManager` 等 | 不动 | — |
| `command/AdminCommandHandler.java` / `AdminCommand.java` | `/lea class` 族 + `/lea season info`（可选 advance 见 5.1） | CLS-1 / SEA-3(可选) |
| `command/LearnCommand.java` | `/le class`（查看自己班级+榜入口） | CLS-2 |
| `gui/ClassBoardGUI.java`（新） | 班级榜界面 | CLS-2 |

### 3.5 双后端差异注意点（汇总）

1. UPSERT 三分支表（3.1）——**F2-01 教训**：不做 `INSERT` 裸写法。
2. `CREATE INDEX IF NOT EXISTS` **MySQL 官方版语法不支持**（MariaDB 支持）——见 PF-SEA-02：现状 `ai_chat_history` 两索引在 MySQL 后端可能从未建立（异常被 `try/finally fine` 吞）。本域补索引与修旧索引都用安全写法，顺带修正（一行改动，属本域正当范围）。
3. `IN` 动态占位符 + 空集合早退（班级榜）。
4. 生产环境实跑验证矩阵（验收）：SQLite 全功能；MySQL 单服全功能；MySQL 双服（跨服剧本）；**MySQL 8 与 MariaDB 尽量各测一次**（尤其索引与 UPSERT）。

### 3.6 结算流程伪代码（实现期基准）

```
settleIfNeeded():
  target = currentSeasonId(today) - 1
  cursor = maxSettledSeasonId()   # 表空 → 首启语义：写入当前季行（未结算）后 return
  for s in cursor+1 .. target:
      ensureSeasonRow(s, window(s))
      top = getRankedPlayersForPeriod(10, window(s).start, window(s).end)   # 跨服聚合
      if tryClaimSettlement(s, now, jsonOf(top)):        # 条件 UPDATE 抢占，唯一赢家
          rows = buildRewardRows(s, top, participants(窗口))   # 分层 + 参与奖
          insertSeasonRewards(rows)
          broadcastSeasonEnded(s, top)                    # 主线程
          sendCrossServer("SeasonSettled", s)             # 各服本地发放在线者
          broadcastSeasonStarted(currentSeasonId(today))  # 新季开场（仅最近一次）
  refreshSeasonCache()
```

---

## 4. 依赖与风险

### 4.1 与成就域（P3-3）交叉

- **"30 天"成就**（成就重构域的 +6 质量型之一）走 `AchievementManager.checkDailyStreak`（连续日判定，窗口 30 天回看 + `LIMIT 40`）——与赛季的"30 天窗口聚合"是**不同判据**（连续性 vs 累计量），零冲突。
- 若成就域想做"赛季相关成就"（如"获得赛季前 10"）：本域提供数据（`season_rewards` 台账即判据源），成就定义与判定归 P3-3——**单向依赖**（AchievementManager → 读表/查方法），本域不引用 AchievementManager（防 P2-26 式 Core↔Core 环，模式对齐 plan-social 4.3）。
- 同文件冲突：无（本域不碰 `AchievementManager` / `Achievement`）。

### 4.2 与社交域（P3-4）与报告域（P3-6）边界

- **社交域**：plan-social 已占用 `EnglishSquareGUI` slot 6（动态模式）+ 默认落点改造。本域**不碰广场 GUI**（赛季入口收敛到 `LeaderboardGUI` tab），零冲突；若后续要加广场赛季按钮，须与动态模式统一排 slot（定位备注）。
- **报告域**：班级实体与班级榜在本域（1.5）；"老师周报 / 一键导出 / `/api/player` 扩展"在域 6。**读表约定**：域 6 若做班级报告，读 `classes`/`class_members`/`player_sessions` 即可，不反向依赖本域类（单向）；本域不为域 6 预留代码接口（班级榜只需在 period 查询加 IN 过滤，域 6 无需前置改造）。

### 4.3 ProxyMessenger 消息机制够不够用？（读码结论：**不够，需三处扩展**）

现状（`ProxyMessenger.java`）：

- 只有发送 `Forward("ALL", subChannel, data)`；接收只认 `GlobalMessage` / `AchievementUnlock` 两子通道；无 origin 标识；无定向发送。
- **协议读写不一致（PF-SEA-01，关键）**：发送端按官方格式写 `[subChannel][short 帧长][帧内 UTF payload]`（100-113 行），接收端（132-151 行）却跳过 short 帧直接连续 `readUTF()` —— 解析时会拿帧长 short 当字符串长度、把帧内容当 modified UTF-8 硬解，**大概率抛 `UTFDataFormatException`（Bukkit 捕获丢消息）或产生带二进制前缀的乱码串**。结论：跨服消息通道现状大概率不可用（单服部署无感，故一直未被发现）——**方法论修正**：单服开发环境无法验证，实现期必须双服实测（验收 C 组）。
- 扩展方案（US-SEA-1 交付）：① 解析修正（含帧长消费 + 帧内 `readUTF`）；② payload 带 origin（`proxy.server-name`）供回环去重；③ 新子通道 `SeasonSettled` / `SeasonStarted`（发送/接收/本地广播三段闭环）。**不做** GetServers 动态服列表/定向发送（v1 无需求，ALL + origin 足够；未来要"通知指定服刷新"时再扩 `Forward <serverName>`，需先查服务器名列表——列 backlog 注释）。

### 4.4 MySQL 大表查询性能

- `player_sessions` ≈ 每玩家每日 1 行：千活跃玩家全年 ≈ 36.5 万行。赛季/周月窗口聚合（`GROUP BY uuid` + `SUM`）无 `session_date` 前置索引时全表扫——**本域补索引后为范围扫（30 天窗 ≈ 千行级 segment）**。缓存策略（5 分钟班车 + GUI/PAPI 读缓存）保证查询频率有界；`%lle_season_rank%` 走内存缓存不触发逐次 SQL。
- `RANK_SNAPSHOT_LIMIT=1000` 对跨服大服的意义：`%lle_rank%` 超出 1000 者未上榜（E-10 既定语义延伸），文档化；可选将 1000 提为配置（S 级可选项，不阻塞）。

### 4.5 数据归档策略

- `seasons`/`season_rewards`/`classes`/`class_members` 存量极小（每季 & 每班级），**不归档**。
- `player_sessions` 是共同底座（周/月/赛季/streak 成就/日历），**本域不动**；将来瘦身单列 backlog。

### 4.6 风险表

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | ProxyMessenger 协议修正后仍与某代理变体不兼容（Velocity bungeecord-compat 行为差异） | 中 | 双服实测矩阵（BungeeCord 与 Velocity 至少各一次）；payload 校验失败记 WARNING 不崩（接收端 try/catch 防御） |
| R2 | Forward "ALL" 回环语义未实测（是否含发送服） | 中 | origin 去重设计对两种行为都正确；`proxy.server-name` 空时的退化行为写入文档；实测确认后回填注释 |
| R3 | 多子服 host 时区不一致 → 赛季边界错位（错位服会晚 1 天结算/或多结算一空季） | 中 | 抢占语义保证不重复发奖（最坏 = 一服认为上季已结算而跳过）；文档强约定同 host 时区；`/lea season info` 输出各服可核对 |
| R4 | 赛季奖励直发绕过 daily-cap（4.1 经济门裁决） | 低 | 台账幂等防重 + 一次性语义；列待用户决策项（若要 cap 内，一行改） |
| R5 | 结算与 `recordSession` 末班会话竞态（窗口边缘毫秒级） | 低 | 已文档化"以结算时刻 DB 为准"；跨服时钟漂移同理 |
| R6 | 与域 4/域 6/域 3 同批实施时的文件冲突 | 低 | 本域不碰 `EnglishSquareGUI`/`AchievementManager`/`SocialManager`；`PlayerJoinListener` 仅一行消费点（编排者收口） |
| R7 | `Leaderboard.scores` 定期重载与 `updateScore` 的丢增量窗口（4.6 文内注释） | 低 | 影响面 = `%lle_rank%` 单次 ≤5 分钟偏差；注释声明 |

---

## 5. 验收标准（端到端人工用例）

### 5.1 单服：模拟赛季切换（核心脚本）

前置：`season.enabled=true`、`duration-days=30`。锚点技巧（`seasonId(today) = floor((today-anchor)/30)+1`）：**`anchor-date=<今天-30>`** 使今天落在第 2 季、第 1 季窗口 = [今天-30, 今天-1] 已于昨日结束（用于测 catch-up）；**`anchor-date=<今天-29>`** 使今天是第 1 季第 30 天/最后一天（用于测午夜翻季）。

1. **catch-up 结算**：以 `anchor-date=<今天-30>` 启动服务器 → 观察日志 `Season settled: 1`；`seasons` 行 `settled_at` 非空；`season_rewards` 台账行数 = 获奖者数（不重复）；等待进服提示/在线者收 `season.reward-earned`。
2. **幂等**：重启服务器再观察 → 不重复结算、不发二次奖励（台账 `claimed` 全 1 或行数不变）。
3. **赛季榜**：`/le leaderboard` → 点 tab-season → 标题/信息牌显示"第 2 赛季 / 剩余 30 天"；学习一局后（缓存 5 分钟内）自己出现在榜、`%lle_season_rank%` 有值、`%lle_season%`=`2`、`%lle_season_days_left%`>0。
4. **空窗**：新赛季第 1 天开 tab → 空态文案正常；上一季回顾 lore 显示 snapshotted 冠军。
5. **关闭语义**：`season.enabled=false` + reload → tab 消失、PAPI 三占位符空串、无调度日志、无广播（真 gating）。
6. **跨天不白跑**：非跨季日的午夜 rollover 日志无结算动作（零 IO 检测生效）。

### 5.2 单服：离线补发

7. 结算时玩家 B 离线（结算前用 B 学习上榜）→ B 进服 → 收 `season.reward-pending-join` + Vault 到账 + `claimed=1`；再退再进 → 不重复。

### 5.3 跨服双服剧本（同 MySQL）

前置：A/B 两服同库同 host 时区；两服 `proxy.server-name` 分别配 `serverA`/`serverB`；代理在线。

8. **榜单天然共享**：A 服玩家 A1 学习 → B 服打开榜单/广场 → 看到 A1（SQL 层面预期直接通过）。
9. **rank 趋同**：等待 B 服 5 分钟刷新 → B 服 `%lle_rank%`（对 A1）反映 A 服进度。
10. **结算单发**：制造跨季（锚点技巧）→ 两服日志对比：仅一服执行 `tryClaimSettlement` 成功（另一服 skip）；`season_rewards` 无重复行。
11. **广播恰好一次**：两服在线玩家各看到**一次**开始/结束广播（无回环重复、无漏发）；`proxy.server-name` 留空时观察退化行为并记录（此项是 R2 的实测回填点）。
12. **跨服在线领奖**：结算时 A1 在线于 A 服、B1 在线于 B 服 → 两边各自即时收到本地发放（`SeasonSettled` 子通道生效）；A1 途中换服到 B → 不重复领（台账已 claimed）。
13. **SQLite 降级**：单独部署一服 `database.type=sqlite` → 赛季/班级全功能可用（本服数据），无跨服报错，广播走本地路径。

### 5.4 班级（批次 B）

14. `/lea class create 实验一班` → 成功；重名 → `class.name-exists`；`/lea class add 实验一班 <玩家>` ×2 → 玩家 `/le class` 看到班级 + 班级榜（成员按赛季分排序）；`/lea class list 实验一班` 显示全量（含隐身穿透）；同学视角 GUI 过滤隐身成员；`/lea class remove …` 后榜更新；成员满/班不存在/玩家不在班 → 对应 i18n 提示。

### 5.5 回归底线

15. `mvn clean verify` 全绿（26+ 测试）；SQLite 与 MySQL 各跑一次完整用例 1-7；`/lea reload` 后赛季缓存与调度不重复（对齐 R-75/reload 语义）。

---

## 6. Story 拆分与规模汇总

| story | 内容 | 规模 | 依赖/顺序 |
|-------|------|------|----------|
| **US-SEA-1** 跨服榜底座 | session_date 索引（双后端安全）+ ProxyMessenger 协议修正/origin/新子通道 + `Leaderboard` rank 快照定期重载 + `proxy.server-name` 配置 + 文档 | M | 先冻结接口（3.1/3.3 签名）；**最先做**（SEA-3 广播的前置） |
| **US-SEA-2** 赛季定义与赛季榜 | `SeasonManager` 窗口计算 + config + `LeaderboardGUI` tab-season + SocialListener 分支 + PAPI×3 + 空态/剩余时间 | M | 依赖 SEA-1 冻结接口；与 SEA-1 串行（ConfigManager/GUI 面重叠小，但编排者统一 fan-in config） |
| **US-SEA-3** 赛季结算与仪式感 | seasons/season_rewards 表 + 结算器（抢占/台账/广播/跨服分发/join 补发）+ 两个调度挂载点 +（可选）`/lea season info\|advance` | M | 依赖 SEA-1+SEA-2 |
| **US-SEA-4** fan-in（编排者） | i18n 双文件 + `MESSAGE_CONFIG_VERSION` 11→12 + `schema.sql` 同步 + CHANGELOG + config.yml 落盘 | S | 全部回流后 |
| **US-CLS-1** 班级数据层与管理命令 | classes/class_members + DatabaseManager 方法 + `/lea class` 族 + 上限/冲突校验 | M | 批次 B 首战；依赖 SEA-1 的索引/DB 基建惯例 |
| **US-CLS-2** 班级榜与 GUI | `getRankedPlayersForPeriodFiltered` + `ClassBoardGUI` + `/le class` 入口 + 域 6 读表约定注释 | M | 依赖 CLS-1 + 批次 A 窗口查询 |
| **US-CLS-3** fan-in（编排者） | i18n + schema.sql + CHANGELOG | S | CLS-1/2 回流后 |

**总规模：批次 A = M-L（约 800 行），批次 B = M（约 500 行），合计 L**；执行顺序：冻结接口 → SEA-1 → SEA-2 → SEA-3 → SEA-4(fan-in) → 三 tester → 门禁 → （批次 B 同上独立走一遍：CLS-1 → CLS-2 → CLS-3）。

---

## 7. 明确不做（v1 范围外）

1. **段位系统**（升降级/徽章）：新机制面，无运营输入；荣耀出口由"赛季名次 + 奖励分层"承担。
2. **赛季分独立累加表**（season_scores）：窗口聚合已足够，独立账本引入双写一致性成本（0 收益）。
3. **玩家自建班级 / 邀请 / 审批 / 退班申请**：社交状态机，与域 4"好友缓做"同判断；v1 管理型。
4. **多班归属**：一个玩家一班（加入新班 = 自动换班）。
5. **结算 GUI / 赛季回顾大页面**：仪式感收敛为广播 + 个人消息 + tab 信息牌。
6. **赛季奖励命令奖励 / 物品奖励**：v1 金币（命令列 future 注释）。
7. **GetServers 动态服列表 / 定向跨服发送**：ALL + origin 过滤足够；注释 backlog。
8. **赛季榜广场模式按钮**：入口收敛在 `/le leaderboard` tab（slot 冲突与密度理由见 2.1）。
9. **player_sessions 归档/清理**：共同底座，单列 backlog。
10. **跨服实时推送**（"他服玩家进榜"即时刷新）：5 分钟缓存班车为既定刷新语义。

---

## 8. 规划期发现（只记录，未修复）

| # | 位置 | 现象 | 等级 | 建议 |
|---|------|------|------|------|
| PF-SEA-01 | `core/ProxyMessenger.java:100-113`（发送）vs `:132-151`（接收） | **协议读写不一致**：发送按官方 Forward 格式写 `[subChannel][short 帧长][帧内 UTF]`，接收端直接连续 `readUTF()`（未消费 short 帧）——拿帧长当字符串长度、把帧当 modified UTF-8 硬解，**大概率 `UTFDataFormatException` / `EOFException`（被 Bukkit 捕获丢消息）或带二进制前缀乱码串**。跨服消息通道现状大概率不可用（单服无感故未暴露） | 中-高 | US-SEA-1 必修（本域正当范围）；必须双服实测（验收 C 组）；接收端加 try/catch 防御 |
| PF-SEA-02 | `data/DatabaseManager.java:225-239` + `resources/schema.sql:121-122` | `CREATE INDEX IF NOT EXISTS` **MySQL 官方版语法不支持**（MariaDB 10.1+ 支持），异常被 `fine` 吞——若后端是 Oracle MySQL，`ai_chat_history` 两索引**从未建立**（SQLite 正常） | 中 | 本域补 session_date 索引时用双后端安全写法，顺带修正旧两处（一行级改动） |
| PF-SEA-03 | `data/DatabaseManager.java:157-168`（`player_sessions` 建表） | 表无 `session_date` 前置索引（`UNIQUE(uuid, session_date)` 以 uuid 为前导）；周/月榜、`checkDailyStreak`、日历均按日期范围查询——大表全扫描 | 中 | US-SEA-1 补 `(session_date)` 索引（赛季/周月/成就/日历共同受益） |
| PF-SEA-04 | `resources/schema.sql:48-58` + 全库 grep | `learning_log` 表**零写入点**（仅建表 + `getTotalSessions`/`getLast7DaysStats` 读取）——统计面板"总会话数"与 7 日趋势会话列恒为 0/不增长，死数据 | 中 | 不属本域：建议编排者排期"在 `recordSession` 双写或改读 `player_sessions`"（二选一），本域仅记录 |
| PF-SEA-05 | `core/progress/Leaderboard.java:171-179` + `gui/EnglishSquareGUI.java:93` | 广场周/月榜走 `getTopPlayers` = **GUI 打开时主线程同步查库**（与 PF-SOC-01 同族）；`refreshCache` 亦不含 `scores` 重载（多服 rank 冻结，本域 SEA-1 处理） | 低-中 | 赛季榜勿复制即时查库模式（走缓存）；周/月榜改造属 F2-11/查询面治理，本域不越权 |
| PF-SEA-06 | `core/achievement/AchievementManager.java`（`isAchievementEligible`） | 硬编码 `Asia/Shanghai` 时区解析 time-limit，其余全项目用系统时区 `LocalDate.now()`——两套日期口径并存，非东八区服务器行为漂移 | 低 | 本域赛季不复制该先例（用系统时区）；建议择机统一（跨域决策） |
| PF-SEA-07 | `core/progress/Leaderboard.java:62`（`RANK_SNAPSHOT_LIMIT=1000`） | 跨服大服下 `%lle_rank%` 超 1000 人未上榜；且 `loadFromDB` 的 scores 与 refreshCache 三缓存互不同步（rank 与 coins 榜可能短时口径不一致） | 低 | 文档化 + 注释；可选提为配置（不阻塞） |

---

*本文件为 Phase 3 规划期只读产出，方案自定，未修改任何源码/配置；接口签名（3.1/3.3/3.6）为实现期冻结基准。批次拆分与班级裁决（1.1/1.5）为规划期决策，若编排者/用户有异议可按备选方案调整（批次 B 延后 / 班级整批切域 6）。*
