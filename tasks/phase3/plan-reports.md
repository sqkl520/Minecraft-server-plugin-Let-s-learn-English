# Phase 3 规划：老师端/家长报告（域 6）

- 日期： 2026-10-13
- 施工状态： **已施工**（P3 Wave 2 域 6，提交 `d926bde`；三维 tester GO/PASS/PASS）
- 基线： 1.0.7-SNAPSHOT（P2 批次后）
- 来源： `tasks/plan-roadmap-usable-to-delightful.md` 第 8 节方向清单第 6 条（"老师端/家长报告（/api/player 周报 + 一键导出文本）"）；`tasks/p2-polish-execution-plan.md` 第五节域 6
- 规划纪律： 本轮为只读规划，未修改任何源码/配置；规划期发现见第 6 节
- 规模估计： **M**（新增约 700~900 行 + 2 个新类 + 1 处小规模数据补充；**4 个 story**：3 开发 + 1 编排者 fan-in）

---

## 0. 目标读者先定（设计前提）

报告不是给玩家看的，是给**不上游戏**的家长/老师/服主看的。三层读者，v1 按前两层设计：

| 读者 | 渠道 | 语言 | 内容口味 |
|------|------|------|---------|
| **家长（主读者）** | 老师/服主生成 TXT 文件后自己转发（微信/QQ/打印） | **强制中文**（`report.language`，与玩家/执行者语言无关） | 无 Minecraft 术语、无颜色码、无英文标识符；重点 = 坚持（学习天数）+ 进步（正确率趋势）+ 薄弱点（错题）+ 一句可执行的建议 |
| **老师/服主（出报告的人）** | 游戏内 `/lea report` 命令（在线看聊天摘要卡）/ 控制台 | 按执行者语言（复用既有 i18n） | 能批量出一班学生的对比表；报告文件落在 `exports/` 便于收集转发 |
| 玩家本人 | 不在本域（家长报告不发给玩家） | — | — |

由此倒推三个硬决策：
1. **报告文件用纯文本 TXT、强制中文（可配）、无颜色码**——最大兼容性（记事本/手机都能开）。
2. **生成方式 = 命令即时生成（异步）**，不做定时后台生成——文件没有収件人会自动堆积，老师按需导出才贴场景；"定时 + 推送到 QQ 群"属代理通道（第 4.5 节明确不做）。
3. **AI 对话原文不进报告**——只进统计数（会话数），隐私边界见 1.6。

---

## 1. 操作逻辑

### 1.1 命令设计草案

沿用 `/lea` 入口（`AdminCommand`），新增子命令 `report`。命令语法：

```
/lea report <玩家名>                → 默认周期（config report.period，默认 week=本周）个人报告
/lea report <玩家名> <周期>         → 覆盖周期：week | lastweek | <N>d（N=1..90，如 7d/14d/30d）
/lea report all [周期]              → 批量：统计周期内有学习活动的全部玩家（上限 config report.batch.max-players，默认 50）
```

- `all` 是保留字（名为 all 的玩家走不了单报，属可接受边角，文档化）。
- 玩家名解析复用 `AdminCommandHandler.resolvePlayerUUID` 的三级查找（在线 → player_data.player_name → 服务器名册）——**该方法目前是 private，需提为 public 或抽到共享 helper**（见 3.3）。
- Tab 补全：第二参数 = 在线玩家名 + `all` + `report` 已知离线名（见 3.3）；第三参数 = `week` / `lastweek` / `7d` `14d` `30d`。

**权限节点**（plugin.yml 新增）：

```yaml
letslearnenglish.report:
  description: "Allows generating student learning reports (/lea report)"
  default: op
```

`AdminCommand.onCommand` 当前顶部统一 `letslearnenglish.admin` 门（第 41-44 行）。改造：`report` 子命令允许 **admin 或 report 任一权限**通过，其余子命令维持 admin 门。意义：服主可把一个只读报告权限发给老师/家长代表，而**不**泄漏 reset/reload/broadcast 能力——这是"家长报告"场景的权限最小化落地。

```java
// AdminCommand 门改造草稿（顺序：先解析子命令名再判权限）
String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
boolean isReport = "report".equals(sub);
boolean allowed = sender.hasPermission("letslearnenglish.admin")
        || (isReport && sender.hasPermission("letslearnenglish.report"));
if (!allowed) { /* general.no-permission，return */ }
```

**生成方式**：命令即时生成 + 异步执行 + 结果回主线程反馈（主线程零 DB IO，见 3.4 线程模型）。控制台执行同样支持（`CommandSender`，报告文件路径打到控制台日志）。

### 1.2 报告周期（"周"的定义）

| 周期值 | 区间定义（服务器时区，LocalDate） | 用途 |
|--------|--------------------------------|------|
| `week`（默认） | 本周一 00:00 ~ 今天（含） | 周日晚上/周一早上出"本周周报"；与 `LearningCalendarGUI` 的周一锚点（`today.with(DayOfWeek.MONDAY)`）同口径 |
| `lastweek` | 上周一 ~ 上周日（完整 7 天） | 周一潮老师出"上一完整周"，数字不随当天变化 |
| `<N>d` | 今天-(N-1) ~ 今天 | 家长随时抽查"最近 7/14/30 天" |

周期计算抽纯函数 `ReportPeriod.compute(String spec, LocalDate today)`（from/to + 展示 label），无 Bukkit 依赖、可单测（对齐 INFRA-01 方向）。

### 1.3 内容清单（逐项数据源映射）

**审计结论先行（关键可行性判断）**：

- "学了哪些**新词**" **不能**从现有表得出——`word_progress` 无任何首学时间戳（只有 mastery/correct/incorrect/last_review_time/review_count），`learning_log` 表**全库零写入**（死表，见 PF-RPT-02）。→ **需要数据补充**：`word_progress.first_learned_at`（见 1.4 补充 A）。
- "时长"**不能**精确得出——无任何时长列。→ v1 用**估算**（答对+答错数 × 配置系数），可选补充 B（`player_sessions.seconds_spent`）留增强。
- "正确率趋势"**可以**从 `player_sessions` 按日行直接得出（每日 words_count/correct_count）。
- "本周签到 X 天"**不能**得出（`checkin_data` 只存 last_date+streak，无逐日历史）→ 报告中"坚持"维度改用 `player_sessions` 的**学习天数**（语义对家长更直观："学习天数"优于"签到天数"），`checkin_data.streak_count` 只作为"当前连续签到"一行展示（见 PF-RPT-05）。
- 决斗维度**不能**得出（`DuelManager` 全是内存态，零持久化）→ 报告不含决斗（见 PF-RPT-07）。

| # | 报告节 | 数据源 | 聚合逻辑 |
|---|--------|--------|----------|
| 1 | 头部（玩家名/区间/生成时间） | `player_data.player_name`（H10 已写入，空回落 uuid）；区间来自命令；生成时间 now | 单行读 |
| 2 | 本周概览：学习天数 / 答对 / 正确率 / 得分 | `player_sessions` 窗口行 | 天数 = 窗口行数（UNIQUE(uuid,session_date) 保证一天一行）；答对 = Σcorrect_count；总题 = Σwords_count；正确率 = Σcorrect/Σwords；得分 = Σscore |
| 3 | 每日明细表 | `player_sessions` 窗口行（按 session_date 升序） | 逐日：答题数/答对数/正确率/得分。**不展示 mode**——UPSERT 合并时 mode 列不更新（当天首会话模式主导，见 PF-RPT-04） |
| 4 | 本周新学单词（前 N 个，带中文义） | 【补充 A 后】`word_progress.first_learned_at` 窗口 + `WordManagerImpl.getWord(english)` 取 `getChinese()`（内存索引，异步安全） | WHERE first_learned_at ∈ 窗口 ORDER BY ASC LIMIT report.new-words-limit（默认 20）；词库已删词回落纯英文 |
| 5 | 需要加强的单词 Top N | `difficult_words`（当前状态） | ORDER BY wrong_count DESC LIMIT report.weak-words-limit（默认 5）；附同法取中文义；≥3 词时补一行"集中在 {难度}"（经 getWord().getDifficulty() + difficulty 显示名） |
| 6 | 长期积累：累计答对 / 累计会话 / 累计分 / 成就 n/16 / 当前签到连续 / AI 对话（窗口 + 累计） | `player_data`（total_words_learned/total_sessions/total_score/conversation_count）+ `achievements` COUNT + `checkin_data.streak_count` + `ai_chat_history` 窗口 COUNT(DISTINCT session_id) | 累计数在线玩家**读内存缓存快照**（与 `/le stats` 同源，见 1.5）；离线读库直查 |
| 7 | 每日挑战完成率（可选一行） | `daily_challenges` 窗口 GROUP BY challenge_date | Σcompleted/Σ行数 |
| 8 | 给家长的话（规则生成 1~2 句） | 由 2/3/5 派生 | 规则见 2.3，无 AI 调用（报告不能依赖外部 API） |
| 9 | 诚实脚注 | 静态 | "数据由服务器自动记录；时长按每题 X 秒估算；逐日数据只含完整完成的会话；错题本为当前状态" |

### 1.4 数据补充方案（两个可分离的小改动）

**补充 A（必要，随本域实施）：`word_progress.first_learned_at BIGINT`**
- 建表：`CREATE TABLE` 加列 + `addColumnIfMissing(word_progress, first_learned_at, BIGINT)`（老库零迁移；存量行 NULL → 读侧视为"窗口前"）。
- 写入**不走 SQL COALESCE**（SQLite 侧 `savePlayerData` 用 `INSERT OR REPLACE`，整行替换会丢旧值——不能靠 SQL 兜底）：在 `PlayerDataStore` 加 `firstLearnedCache`（Map<UUID, Map<String, Long>>，与 masteryMap 平行）：`loadPlayerData` 随 SELECT 多读一列；`updateWordProgress` 对每个词 `putIfAbsent(word, now)`（首见即写）；`savePlayerData` 随 UPSERT 写值（缺失/NaN 写 0，读侧 ≤0 视为未知）；`unloadPlayerData/clearCache` 同步清理。
- 语义近似：首次进入进度缓存（即首次答过该词）的时刻，答错词也算"接触过"——报告标题用"本周新学的单词"可自洽；若要严格"答对才算"，取 `correct_count > 0` 过滤（首学时间仍是接触时间，可接受，注释说明）。

**补充 B（可选增强，S 规模，可独立排期）：`player_sessions.seconds_spent BIGINT DEFAULT 0`**
- `AnswerResult` 加 elapsed 字段（`LearningSession.getElapsedSeconds()` 已有）→ `recordSession` UPSERT 累加。得到"完整会话实际时长"；未落地前报告用估算（1.3 #9 脚注如实标注）。**v1 默认走估算**，补充 B 只作为后续精度提升留位。

### 1.5 在线/离线玩家的数字一致性策略

- **窗口数字**（概览/每日/新词/错题/AI）一律读 DB：`player_sessions` 在 `showSessionComplete` 主线程同步落库，窗口数据对完成会话即时准确。
- **累计数字**：目标玩家**在线**时读 `PlayerDataStore` 缓存快照（与 `/le stats`(ProfileGUI) 完全同源 → 验收可逐字对齐）；**离线**时读 `player_data` 直查（与重新登录后的显示一致）。快照在主线程命令入口处取好、随参数传入异步生成器，避免异步线程去触碰缓存懒加载路径。
- 已知口径差（写入脚注）：**中途停止/退出的会话**进累计（endQuiz→endSession 照常结算，R-59 既定裁决）但**不进** `player_sessions` 窗口 → 窗口 Σ 与累计增量不恒等，属预期，脚注说明"逐日数据只含完整完成的会话"。

### 1.6 家长/老师报告的隐私边界（默认策略）

**默认策略：权限即同意边界 + 聚合数据原则。**

1. 报告只能由持有 `letslearnenglish.report`（或 admin）的玩家/控制台生成——谁被信任由服主决定（老师/家长代表），玩家侧不需要额外"同意开关"。理由：玩家侧现有开关（showInSquare/showResults）语义是"对**其它玩家**的展示控制"，管理/监护通道若也受它约束，孩子关一下开关家长就什么都看不到，功能失效；插件无法验证"是否真的是家长"，任何"玩家同意机制"都只是自欺。
2. **但不包含 AI 对话原文**——只含统计数。孩子在对话里可能写了私人内容，监护报告不需要也不应携带。
3. 配置 `report.respect-privacy`（默认 **false**）：若服主想收紧，置 true 后——单报目标 `showInSquare=false` → 拒发提示；批量表按同款 `LEFT JOIN player_profiles ... COALESCE(show_in_square,1) <> 0` 过滤（与榜单/广场同 SQL 口径）。两种模式均在 README/注释中写明语义。
4. 报告文件只落服务器本地 `exports/`，插件**不**主动外发任何东西（无网络调用）。

### 1.7 批量模式（班级场景）

- `/lea report all`：一条汇总 SQL 取"窗口内有 `player_sessions` 行的玩家"（上限 `report.batch.max-players`，默认 50，超出截断并在文件头注明），生成**一个**合并 TXT：开头一张按答对数降序的对比总表（学生 / 学习天数 / 答对 / 正确率 / 得分），随后逐人小节（复用单人模板的精简版：概览+错题，不回放每日明细，避免文件爆炸）。
- 班级（花名册）粒度**不做**：名单管理属 P3-5（赛季与班级）域；本域先交付"全服活跃"批量，P3-5 落地 `class_members` 一类结构后，本域批量入口加一个 `class <名字>` 过滤参数即可（接口预留：批量查询方法接受 UUID 列表，名单来源可替换）。

### 1.8 导出格式与目录

- 格式：**纯文本 TXT（UTF-8，无颜色码）**；写 `\r\n` 换行（Windows 记事本友好）+ UTF-8 BOM 头（防旧记事本识别成 ANSI 乱码）。
- 目录：`plugins/LetsLearnEnglish/exports/`（**既有约定**，`AdminCommandHandler.exportWordBank` 同目录同风格；注意 `DataExporter` 的 CSV 落在插件根目录是历史不一致，见 PF-RPT-06——新实现向 exports/ 收敛）。
- 命名：`report_<玩家名>_<yyyyMMdd>-<yyyyMMdd>_<HHmmss>.txt`；批量 `report_all_<from>-<to>_<HHmmss>.txt`。时间戳后缀 = 同周期重开可留档对比（word bank 导出同款习惯）。
- 清理：启动异步任务删除 `exports/report_*.txt` 中超过 `report.keep-days`（默认 30，0=不清理）的旧文件（对齐 `cleanupOldChatHistory` 模式）。
- 命令反馈：聊天里发**相对路径**（`exports/…`，与 wordbank.export-success 同口径），控制台日志打绝对路径。

---

## 2. 用户体验

### 2.1 服主/老师操作剧本（完整链路）

```
① 服主一次性设置
   - 服务器装插件（已有）→ 关闭无关配置无需动（report 默认 enabled: true）
   - 给老师发权限: /lp user TeacherA permission set letslearnenglish.report true
   - （老师不需要管理员权限，这是本设计的要点）

② 老师出报告（游戏内）
   - 老师（或家长代表在控制台）输入: /lea report Steve
   - 聊天栏看到摘要卡（<10 秒内，含文件路径）:
     [英语学习] ===== 学习报告 · Steve =====
       周期 10-06 ~ 10-12 · 学习 5 天 · 答对 87 词 · 正确率 82.1%
       薄弱词: quiet · through · weather
       报告文件: exports/report_Steve_20261006-20261012_093000.txt
   - 周日出"上一整周": /lea report Steve lastweek
   - 一班学生: /lea report all lastweek → 得到一张全服对比总表 + 逐人小节

③ 老师转发给家长
   - 从服务器文件夹（或面板文件管理器）拿 exports/report_…txt
   - 微信/QQ 直接发文件或截图（纯文本、中文、无格式门槛）

④ 家长看到什么
   - 一份可直接读的中文周报（模板见 2.4），重点三块：
     坚持（学习几天）、进步（正确率）、需要加强的词（可听写）
```

### 2.2 游戏内查看版（聊天栏摘要卡）

见上 ② 的摘要卡；由**执行者语言**渲染（复用 `MessageUtil.getPlayerMessage`，彩色 & 码正常）。控制台执行则只打日志。摘要与文件同源（同一份 `PlayerReportData` 渲染），数字必然一致。

### 2.3 "给家长的话"规则（确定性，无 AI）

```
if 学习天数 == 0 → "本周还没有学习记录，可以问问孩子是不是遇到了什么困难~"
else 拼接:
  - 学习天数 >= 5 → "孩子本周坚持学习 {days} 天，很不容易，记得鼓励一下！"
  - 正确率趋势（前半窗口 vs 后半窗口，各 ≥2 天才有比较）:
      上升 → "正确率比前半周提升明显，进步看得见。"
      下降 → "正确率后半周有些下滑，可能是题目变难，别着急。"
  - 错题 Top 非空 → "建议陪TA把'需要加强的单词'里的前 {k} 个听写一遍。"
保底（命中过少时）→ "保持每周 3~4 次、每次 10 分钟，比一次学很久更有效。"
```

### 2.4 报告模板草案（真实字段示例，可直接给家长看）

```
================================================
           英语学习周报
================================================
玩家:     Steve
服务器:   XX 中学英语服
统计区间: 2026-10-06 (周一) ~ 2026-10-12 (周日)
生成时间: 2026-10-13 09:30

──── 本周概览 ─────────────────────────────
  学习天数: 5 天  (周一、周二、周三、周五、周日)
  答对单词: 87 个
  正确率:   82.1%  (答对 87 / 共 106 题)
  学习得分: 1,240 分
  学习时长: 约 53 分钟  (按每题 30 秒估算)

──── 每日明细 ─────────────────────────────
  日期        答题   答对   正确率   得分
  10-06 周一   20     17    85.0%    260
  10-07 周二   25     21    84.0%    310
  10-08 周三   18     16    88.9%    240
  10-10 周五   23     18    78.3%    230
  10-12 周日   20     15    75.0%    200

──── 本周新学单词（共 34 个，列出前 20）──────
  library(图书馆)  weather(天气)  borrow(借)  camera(相机)
  quiet(安静的)  through(穿过)  village(村庄)  subway(地铁)
  ... 其余 14 个见服务器记录

──── 需要加强的单词（当前错题本 Top 5）────────
  quiet(安静的) ×5    through(穿过) ×4    weather(天气) ×3
  borrow(借) ×2       camera(相机) ×2
  （集中在：初级）

──── 长期积累 ─────────────────────────────
  累计答对: 513 个单词        累计学习: 61 次会话
  成就解锁: 9/16              当前连续签到: 7 天
  AI 情景对话: 本周 3 次 / 累计 22 次
  每日挑战:   本周完成 12/15 项

──── 给家长的话 ───────────────────────────
  孩子本周坚持学习 5 天，很不容易，记得鼓励一下！
  正确率比前半周略下降，建议陪TA把"需要加强的单词"
  里的前 3 个听写一遍。

──── 说明 ─────────────────────────────────
  数据由 Minecraft 服务器学习插件自动记录，仅含学习统计，
  不含聊天内容。逐日数据只含完整完成的会话；学习时长按每题
  30 秒估算；错题本为当前状态（练会即移除）。
================================================
```

### 2.5 i18n key 草案（`report.*` 段，zh/en 双文件，config-version 11→12）

报告文件正文**按 `report.language`（默认 zh）**从 messages/{lang}.yml 读模板（纯文本值，不含 & 码）；命令反馈/摘要卡按**执行者语言**读同段里带 & 码的键。落地时先 grep 确认键不存在。

| key | zh（草案） | en（草案） | 用途 |
|-----|-----------|-----------|------|
| report.title | 英语学习周报 | English Learning Report | 文件标题 |
| report.meta-player | 玩家: {player} | Player: {player} | 头部 |
| report.meta-server | 服务器: {server} | Server: {server} | 头部（取 config prefix/服务器名，取不到用插件名） |
| report.meta-period | 统计区间: {from} ~ {to} | Period: {from} ~ {to} | 头部 |
| report.meta-generated | 生成时间: {time} | Generated: {time} | 头部 |
| report.weekday-mon..sun（7 键） | 周一…周日 | Mon…Sun | 每日明细/区间标注 |
| report.section-overview | ──── 本周概览 ──── | ──── Overview ──── | 节标题 |
| report.overview-days | 学习天数: {days} 天 | Days studied: {days} | 概览 |
| report.overview-correct | 答对单词: {correct} 个 | Words correct: {correct} | 概览 |
| report.overview-accuracy | 正确率: {accuracy}%  (答对 {correct} / 共 {total} 题) | Accuracy: {accuracy}% ({correct}/{total}) | 概览 |
| report.overview-score | 学习得分: {score} 分 | Score: {score} | 概览 |
| report.overview-minutes | 学习时长: 约 {minutes} 分钟  (按每题 {seconds} 秒估算) | Study time: ~{minutes} min (est. {seconds}s/question) | 概览 |
| report.section-daily / daily-header / daily-row | ──── 每日明细 ──── / 表头 / `{date} {weekday}  {answered} 题 / 答对 {correct} / {accuracy}% / {score} 分` | … | 每日表 |
| report.section-new-words | ──── 本周新学单词（共 {count} 个，列出前 {shown}）──── | … | 新词节 |
| report.new-words-more | … 其余 {count} 个见服务器记录 | … {count} more on the server | 截断行 |
| report.section-weak-words | ──── 需要加强的单词（当前错题本 Top {k}）──── | … | 错题节 |
| report.weak-word-item | {word}({chinese}) ×{count} | {word}({chinese}) x{count} | 词条 |
| report.weak-focus | （集中在：{difficulty}） | (Mostly: {difficulty}) | 难度归组行 |
| report.section-cumulative | ──── 长期积累 ──── | ──── Long-term ──── | 累计节 |
| report.cumulative-line | 累计答对: {words} 个单词  / 累计学习: {sessions} 次会话 | … | 累计 |
| report.cumulative-extra | 成就解锁: {unlocked}/{total}  / 当前连续签到: {streak} 天 | … | 累计 |
| report.cumulative-ai | AI 情景对话: 本周 {recent} 次 / 累计 {total} 次 | … | 累计 |
| report.cumulative-challenge | 每日挑战: 本周完成 {done}/{all} 项 | … | 累计（可选行） |
| report.section-advice / advice-none / advice-good / advice-up / advice-down / advice-weak / advice-default | 给家长的话 + 6 条规则句（见 2.3） | … | 建议节 |
| report.section-note / note-line | ──── 说明 ──── / 数据由…（2.4 示例原文） | … | 脚注 |
| report.chat-header | {prefix}&6===== 学习报告 · {player} ===== | … | 摘要卡 |
| report.chat-overview | {prefix}&7周期 {from} ~ {to} · 学习 &e{days}&7 天 · 答对 &e{correct}&7 词 · 正确率 &e{accuracy}% | … | 摘要卡 |
| report.chat-weak | {prefix}&7薄弱词: &c{words} | … | 摘要卡（无则整行不发） |
| report.chat-generated | {prefix}&a报告已生成: &e{file} | … | 摘要卡 |
| report.chat-failed | {prefix}&c报告生成失败: {reason} | … | 失败 |
| report.usage | {prefix}&c用法: /lea report <玩家名\|all> [week\|lastweek\|<天数>d] | … | 用法 |
| report.no-data | {prefix}&c该玩家在统计周期内没有学习数据喵~ | … | 窗口为空但玩家存在（报告仍生成累计节） |
| report.player-not-found | （复用 admin.player-no-data，不加新键） | — | 三级查找失败 |
| report.batch-start | {prefix}&7正在生成 &e{count} &7名玩家的批量报告... | … | 批量异步中 |
| report.batch-complete | {prefix}&a批量报告已生成: &e{file} &7({count} 人{truncated}) | … | 批量完成 |
| report.batch-truncated |  &c（已达上限 {max} 人，其余未包含） | … | 截断标注 |
| report.disabled | {prefix}&c报告功能未启用喵~ | … | `report.enabled=false` 真接线 |
| report.hidden | {prefix}&7该玩家已隐身，按服务器设置不生成报告喵~ | … | respect-privacy=true 时 |
| report.period-invalid | {prefix}&c无效周期: {input}&c，可用: week / lastweek / <天数>d | … | 参数校验 |

修改现键：`admin-help` 段新增 `report` 行（`&e/lea report <玩家|all> [周期] &7- 生成学习报告（exports/ 文本文件）`）；`AdminCommand.SUBCOMMANDS` 加 `"report"`。两文件同步 + `ConfigManager.MESSAGE_CONFIG_VERSION` 11→12。

### 2.6 config.yml 新键草案

```yaml
# ============================================================
#  学习报告 (Learning Report)
# ============================================================
report:
  # 是否启用 /lea report 报告功能。false 时命令提示 report.disabled（真接线，非静默 no-op）
  enabled: true
  # 默认统计周期: week(本周一~今天) / lastweek(上一完整周)
  # 命令行的周期参数可覆盖；家长抽查可用 7d/30d 形式
  period: "week"
  # 报告文件语言（给家长看，独立于玩家与执行者语言）: zh / en
  language: "zh"
  # 时长估算系数: 每题折合的分钟数（默认 0.5 = 每题 30 秒，与 answer-timeout 默认一致）
  minutes-per-question: 0.5
  # 报告文件保留天数（exports/report_*.txt 的清理阈值），0 = 不清理
  keep-days: 30
  # 是否尊重玩家隐私开关（showInSquare）。
  # false（默认）= 报告属管理/监护通道，不受玩家展示开关约束 —— 权限即同意边界；
  # true = 隐身玩家不出现在批量报告、单报拒发（与榜单过滤同口径）
  respect-privacy: false
  batch:
    # /lea report all 的单次最大玩家数（按窗口内活跃人数取前 N，超出截断并标注）
    max-players: 50
  # 报告中列表的截断长度
  new-words-limit: 20
  weak-words-limit: 5
```

主 config 的 `config-version` 维持 2（追加段，不做主配置迁移，与 P2/S8 做法一致）。

---

## 3. 实现要点

### 3.1 触及文件清单

| 文件 | 改动 | 归属 story |
|------|------|-----------|
| `data/DatabaseManager.java` | 新增查询方法族（见 3.2）+ `createTables` 补 `word_progress.first_learned_at`（补充 A） | US-RPT-1 |
| `resources/schema.sql` | `word_progress` 加列文档（无新表） | US-RPT-1 |
| `data/PlayerDataStore.java` | 补充 A：`firstLearnedCache`（load/update/save/unload/clear 五处随 masteryMap 平行改动） | US-RPT-1 |
| `core/report/ReportGenerator.java`（新） | 编排：异步查询→组装→写文件；含 `ReportPeriod`、`PlayerReportData`、`DailyRow` records（嵌套） | US-RPT-2 |
| `core/report/ReportTextFormatter.java`（新） | 纯函数渲染（接收数据 + `Function<String,String>` 模板查取器 → String），可无 Bukkit 单测 | US-RPT-2 |
| `LetsLearnEnglish.java` | DI：字段 + `getReportGenerator()`（构造注入 plugin/databaseManager/wordManager，仿 DataExporter 位置） | US-RPT-2 |
| `command/AdminCommand.java` | `SUBCOMMANDS` + `report` 分支 + 权限门改造 + tab 补全 | US-RPT-3 |
| `core/manager/AdminCommandHandler.java` | `resolvePlayerUUID` 提为 public（或抽共享 helper）+ 新增 `generateReport(...)` 薄封装（主线程快照 + 异步派发） | US-RPT-3 |
| `resources/plugin.yml` | `letslearnenglish.report` 权限节点 | US-RPT-3 |
| `resources/config.yml` / `messages/zh.yml` / `messages/en.yml` / `CHANGELOG.md` | report 段 / 全部 i18n 键 + admin-help + config-version 12 / 变更行 | US-RPT-4（编排者 fan-in） |
| `src/test/.../ReportTextFormatterTest.java`、`ReportPeriodTest.java`（新） | 纯逻辑单测（模板选择、截断、周期边界、建议规则分支） | US-RPT-4/2 |

**明确不改**：`DataExporter.java`（保持现状；其"仅在线玩家导 CSV"的限制由本域报告功能覆盖，协议化改造 P2-09 是独立欠账，不在本域顺手动——见 3.5）。

### 3.2 数据查询 SQL 草案（全部持 `connectionLock`，双后端对照，风格对齐 `getRankedPlayersForPeriod`）

所有方法加在 `DatabaseManager`（沿既有惯例：内部吞异常记 WARNING 返空）。`session_date` 比较用 `yyyy-MM-dd` 字符串（既有 `getSessionDays` 同口径）；`unlocked_at` 用毫秒长整；`ai_chat_history.timestamp` 用 `yyyy-MM-dd HH:mm:ss` 字符串边界（SQLite 文本比较与 `CURRENT_TIMESTAMP` 写入格式一致，MySQL 自动转 TIMESTAMP；注释说明）。

```sql
-- Q1 窗口逐日（单人）
SELECT session_date, words_count, correct_count, score
FROM {p}player_sessions
WHERE uuid = ? AND session_date >= ? AND session_date <= ?
ORDER BY session_date ASC;

-- Q2 终身汇总 + 名字
SELECT player_name, total_words_learned, total_sessions, total_score, conversation_count
FROM {p}player_data WHERE uuid = ?;

-- Q3 成就（总数 + 窗口内新增一次查）
SELECT COUNT(*) AS total,
       SUM(CASE WHEN unlocked_at >= ? AND unlocked_at < ? THEN 1 ELSE 0 END) AS recent
FROM {p}achievements WHERE uuid = ?;

-- Q4 AI 对话（窗口内完成的对话会话数）
SELECT COUNT(DISTINCT session_id) FROM {p}ai_chat_history
WHERE player_uuid = ? AND timestamp >= ? AND timestamp <= ?;

-- Q5 错题本 Top N（当前状态）
SELECT word, wrong_count FROM {p}difficult_words
WHERE player_uuid = ? ORDER BY wrong_count DESC LIMIT ?;

-- Q6 签到（当前连续）
SELECT last_checkin_date, streak_count FROM {p}checkin_data WHERE player_uuid = ?;

-- Q7 新学词（补充 A 落地后）
SELECT word FROM {p}word_progress
WHERE uuid = ? AND first_learned_at >= ? AND first_learned_at < ?
ORDER BY first_learned_at ASC LIMIT ?;

-- Q8 每日挑战完成度（窗口，可选行）
SELECT challenge_date,
       SUM(CASE WHEN completed THEN 1 ELSE 0 END) AS done,
       COUNT(*) AS total
FROM {p}daily_challenges WHERE uuid = ? AND challenge_date >= ? AND challenge_date <= ?
GROUP BY challenge_date;

-- Q9 批量：窗口活跃玩家汇总（NEW）
SELECT s.uuid, MAX(p.player_name) AS player_name,
       COUNT(*) AS active_days,
       COALESCE(SUM(s.words_count), 0) AS total_answered,
       COALESCE(SUM(s.correct_count), 0) AS total_correct,
       COALESCE(SUM(s.score), 0) AS total_score
FROM {p}player_sessions s
LEFT JOIN {p}player_data p ON p.uuid = s.uuid
WHERE s.session_date >= ? AND s.session_date <= ?
GROUP BY s.uuid
ORDER BY total_correct DESC
LIMIT ?;   -- 传 max-players + 1 探测截断
-- respect-privacy=true 时追加：
-- LEFT JOIN {p}player_profiles pf ON pf.uuid = s.uuid
-- WHERE ... AND COALESCE(pf.show_in_square, 1) <> 0
-- （MAX(player_name) 包法兼容 MySQL ONLY_FULL_GROUP_BY，沿 R-05/周榜惯例）
```

批量模式取 Q9 名单后，对每人跑 Q1（概览/每日由 Q1 派生）与 Q5（错题 Top3 精简）→ 上限 50 人 × 约 2 查询 = ~100 条小查询，串行于 connectionLock，**全部在异步线程执行**（见 3.4），量级安全。

### 3.3 命令与玩家名解析

- `AdminCommandHandler.resolvePlayerUUID` 当前 private 且 Javadoc 注明"仅被 reset 调用、主线程安全"——report 需要同一逻辑：提为 public 并更新 Javadoc（调用点仍限主线程命令入口）；若想更干净可抽 `core/manager/PlayerNameResolver`，但改动面更大，**建议直接提权**（S4 教训：签名改造量大时选最小面）。
- tab 补全（`AdminCommand.onTabComplete`，主线程）：第 2 参数 = 在线玩家名 + `"all"` + 离线名（新增 `DatabaseManager.getKnownPlayerNames(prefix, limit=25)`：`SELECT DISTINCT player_name FROM {p}player_data WHERE player_name IS NOT NULL AND player_name LIKE ? LIMIT ?`，单表小查询，主线程可接受的既有量级；注释标注权衡）。第 3 参数 = `week/lastweek/7d/14d/30d` 过滤。

### 3.4 线程模型（F2-11 教训的正面回答）

| 阶段 | 线程 | 内容 | 依据 |
|------|------|------|------|
| ① 参数解析 / UUID 解析 / 在线快照 | **主线程**（命令回调） | 校验 report.enabled、周期参数；`resolvePlayerUUID`（含 `Bukkit.getOfflinePlayer` 阻塞查询）；目标在线时快照 `PlayerStats` 与原版名字 | Bukkit API 只在主线程 |
| ② DB 查询 + 文本渲染 + 写文件 | **异步**（`runTaskAsynchronously`） | 全部 Q1~Q9 + 词库内存索引取中文（ConcurrentHashMap 只读安全）+ `Files.write` | 主线程零 DB IO、零文件 IO（F2-11 同族的反面样板） |
| ③ 摘要卡 / 失败反馈 / 控制台日志 | **回主线程**（`runTask`） | i18n 消息发送 | 玩家可见动作回主线程 |
| 守卫 | — | 异步完成时复查 `plugin.isEnabled()`（shutdown 竞态，对齐 S2 shutdown 守卫精神） | 防止关服后调度 |

批量模式同一模型（② 中循环处理 N 人）。同玩家并发点两次：允许（各自产出带时间戳文件），不做互斥（成本低收益低）；报告中写文件用 `FileWriter(UTF_8)` 显式编码。

### 3.5 与 DataExporter 的关系（扩展还是新写 → 新写，不动旧件）

| 维度 | DataExporter（现状） | ReportGenerator（本域） |
|------|---------------------|------------------------|
| 读者 | 服主做数据分析 | 家长/老师 |
| 范围 | **仅在线玩家**（类注释自述） | 离线可查（三级 UUID 查找），批量含全窗口活跃玩家 |
| 格式 | CSV（机器读） | 中文 TXT（人读） |
| 返回协议 | 字符串 `"Export failed: …"`（P2-09 教训点） | **record 结果对象** `ReportResult(boolean success, File file, String reason)`，命令侧按字段判定，永不靠字符串前缀 |
| 文件名/目录 | 插件根目录 | `exports/`（既有约定） |

结论：**新写独立类**（职责、读者、格式全不同）；不顺手改 DataExporter 的 P2-09 协议问题（独立 backlog 项，避免混批）。报告实现从第一天就采用协议化返回——这正是 P2-09 的教训落地。

### 3.6 Story 拆分与规模（总规模 M）

| # | Story | 内容 | 规模 | 文件锁 |
|---|-------|------|------|--------|
| US-RPT-1 | 数据层 | DatabaseManager Q1~Q9 + `getKnownPlayerNames` + 补充 A（建表/迁移 + PlayerDataStore 五处）+ schema.sql | M | data/DatabaseManager.java、data/PlayerDataStore.java、resources/schema.sql |
| US-RPT-2 | 报告核心 | ReportGenerator + ReportTextFormatter + ReportPeriod 纯函数 + DI（LetsLearnEnglish） | M | core/report/*（新）、LetsLearnEnglish.java |
| US-RPT-3 | 命令与权限 | AdminCommand（子命令/权限门/tab）+ AdminCommandHandler（resolvePlayerUUID 提权 + generateReport 派发）+ plugin.yml | S | command/AdminCommand.java、core/manager/AdminCommandHandler.java、resources/plugin.yml |
| US-RPT-4 | 编排者 fan-in | i18n 双文件（~40 新键 + admin-help 改 + config-version 12）+ config.yml report 段 + CHANGELOG + 单测补例 | S | zh.yml、en.yml、config.yml、CHANGELOG.md、src/test |

执行顺序：US-RPT-1 ∥ US-RPT-3 前置冻结接口（3.2 SQL 签名 / 3.4 派发签名）→ US-RPT-1 ∥ US-RPT-2 ∥（US-RPT-3）→ US-RPT-4 → 三 tester → 门禁。（US-RPT-3 可并入 US-RPT-2 一个流执行，若求最简两流即可。）

---

## 4. 依赖与风险

### 4.1 查询性能

- 单人报告：约 8 条按主键/唯一键的小查询，串行于 `connectionLock`，毫秒级。
- 批量：Q9 一条 GROUP BY（`player_sessions` 在 uuid 上有 UNIQUE 索引，但 (uuid, session_date) 唯一即覆盖；窗口过滤走全表扫该时间窗，行量 = 活跃玩家 × 天数，百人服窗口 7 天 ≈ 700 行） + ≤50 人 × ~2 查询；**全部异步**，对 TPS 零影响。
- 大服（千活跃）时 Q9 LIMIT 截断保护 + 文件头标注，不会失控。

### 4.2 数据缺失兜底

| 场景 | 表现 |
|------|------|
| 玩家从未学习（有帐号无数据） | `resolvePlayerUUID` 查不到 → `admin.player-no-data`（既有键） |
| 玩家存在但窗口内零活动 | 报告照常生成：窗口节显示 0 / `report.no-data` 提示行，累计节正常（"本周没有学习记录"版建议句） |
| 补充 A 未落地（first_learned_at NULL 存量） | "本周新学单词"节整节省略（不回填、不报错），其余节不受影响——保证该节可独立开关式降级 |
| 词库已删词出现在错题/新词 | 中文义查不到 → 回落纯英文词条 |
| 名字列 NULL（从未 join 后保存的旧行） | 回落 uuid 前 8 位（既有惯例） |

### 4.3 跨服场景

- 共享 MySQL：Q1~Q9 天然全网聚合（报告 = 全网络数据，正确语义）；批量 `all` 即"全网活跃"，与榜单/统计仪表盘同款行为。
- SQLite：单服数据（与现有全部功能同一限制），文档化。
- 无需 ProxyMessenger（无实时推送需求）。

### 4.4 与 P3-5（赛季/班级）的交叉与顺序建议

- **顺序建议：本域（无班级依赖）先做**。理由：单人报告 + 全服活跃批量已完整覆盖"家长周报"主场景；班级花名册（`class_members` 类结构）属 P3-5 的能力面，本域批量查询已按"接受 UUID 列表"预留（1.7），P3-5 落地后加一个 `class <名>` 参数即可，无返工。
- 反向提示给 P3-5 规划：若赛季做"赛季榜重置"，报告窗口走 `player_sessions`（不依赖赛季字段），两者零冲突；赛季若新增按赛季聚合的表，报告可选择后续叠加。

### 4.5 风险表

| # | 风险 | 等级 | 缓解 |
|---|------|------|------|
| R1 | 与 P3-4（广场社交）**同改 DatabaseManager 与 zh/en.yml** | 中 | 两域同批实施时 DatabaseManager 串行给一方（各自方法互不重叠，合并易）；i18n/CHANGELOG 一律编排者 fan-in 收口 |
| R2 | 补充 A 改动 `savePlayerData` 热路径（每次自动保存携带新列） | 低 | 只加一列读写，不改变事务结构；缓存平行 map 与 masteryMap 同生命周期；验收含"重启后 first_learned_at 不漂移"用例 |
| R3 | 窗口数字 vs 累计数字不恒等（quit/stop 会话只进累计） | 低 | 脚注如实说明 + 验收按数据源各自对账（5.2-③） |
| R4 | 在线玩家累计数 ±5 分钟落盘滞后 | 低 | 设计已消：在线读缓存快照（与 /le stats 同源）；离线读库 |
| R5 | 报告语言强制 zh 引起英文服困惑 | 低 | `report.language` 可配 + config 注释说明"独立于玩家语言，报告读者是家长" |
| R6 | `all` 保留字与玩家重名冲突 | 低 | 文档化；单报一律可走完整名（`/lea report all2` 之类不受影响） |
| R7 | 报告被非信任者拿到（权限发多了） | 低 | 权限节点默认 op、文档建议只给信任的人；不含 AI 聊天原文收窄泄露面 |
| R8 | exports/ 目录文件堆积 | 低 | `keep-days` 启动清理（对齐既有清理任务模式） |

---

## 5. 验收标准（人工用例：前置 / 操作 / 预期）

### 5.1 主链路

| # | 前置 | 操作 | 预期 |
|---|------|------|------|
| 1 | 玩家 Steve 一周内 5 天各完成 1 次完整会话（seed 数据或实测积累）；服主在线 | `/lea report Steve` | <10 秒内聊天栏出摘要卡（天数/答对/正确率/薄弱词/文件路径）；`exports/report_Steve_<from>-<to>_<HHmmss>.txt` 存在，记事本可读、中文无乱码、无 & 码；报告各节与模板（2.4）结构一致 |
| 2 | 同上 | 用 `/lea report Steve lastweek` 在周一执行 | 区间 = 上一完整周一~周日；数字与直接 SQL 查 `player_sessions` 该区间一致 |
| 3 | 同上 | `/lea report Steve 7d` | 区间 = 今天-6 ~ 今天 |
| 4 | 教师帐号仅有 `letslearnenglish.report`（无 admin） | `/lea report Steve` | 成功；`/lea reset Steve` 等其余子命令回 `general.no-permission`（权限最小化验证） |
| 5 | 无权玩家 | `/lea report Steve` | `general.no-permission` |
| 6 | 控制台 | `lea report Steve` | 控制台日志含绝对路径；文件生成成功 |
| 7 | 从未进服的陌生人名 | `/lea report NotARealPlayer` | `admin.player-no-data`，零文件产出 |

### 5.2 数字一致性（核心验收，任务指定用例的落法）

| # | 前置 | 操作 | 预期 |
|---|------|------|------|
| ① | Steve 离线 | 先 `/lea export`（或直接 SQL 读 `player_data`）记下累计值 | 报告的"累计答对/累计学习/累计分"与 DB 值**逐字一致**（离线走直查，确定性） |
| ② | Steve 在线且刚答完一局 | `/lea report Steve` 并同时 `/le stats`（ProfileGUI） | 报告累计节 = ProfileGUI 的"已学单词/学习次数"（同源缓存快照，允许差 0）——**注意不要拿 ProfileGUI 的"正确率"行对账**（该行有已知 bug，见 PF-RPT-01） |
| ③ | 任意 | 用手工 SQL 查 `player_sessions WHERE uuid=? AND session_date BETWEEN ? AND ?` 求 Σ | 报告窗口节的 学习天数/答对/正确率/得分 与手工 SQL **逐项一致**；若期间有 quit/stop 的半途会话，差值只应体现在"累计 - 窗口"的解释里（脚注已述） |
| ④ | 新玩家刚玩一天 | `/lea report <新玩家>` | 累计节正常、趋势句不报错（不足 2 天时不出趋势句）；"本周新学单词"= 当天学过的词（补充 A 生效后） |

### 5.3 批量与边界

| # | 前置 | 操作 | 预期 |
|---|------|------|------|
| 8 | 窗口内 5 名玩家有活动 | `/lea report all` | 生成 `report_all_*.txt`：头部对比总表按答对数降序 5 行 + 逐人小节；聊天收到 batch-complete 含人数 |
| 9 | 窗口内 0 名玩家有活动 | `/lea report all` | 明确提示（batch-complete 0 人 或 no-data 变体），不出空文件（或出仅表头文件——实现自定，取"提示不出文件"） |
| 10 | 活跃人数 > `report.batch.max-players` | 调小上限到 3 后 `/lea report all` | 只含 3 人，文件头 + 聊天标注截断 |
| 11 | `report.respect-privacy=true`，Steve 设 `showInSquare=false` | B 再生成 | 批量无 Steve；`/lea report Steve` 回 `report.hidden`。改回 false（默认）后恢复 |
| 12 | `report.enabled=false` + `/lea reload` | `/lea report Steve` | `report.disabled`；不产生文件（真接线） |
| 13 | 服务器上跑批量期间观察 TPS | `/lea report all`（50 人种子库） | 生成期间 TPS 无可见跌落（异步验证）；日志无 connectionLock 相关 WARN |

### 5.4 回归与自动测试

| # | 内容 | 预期 |
|---|------|------|
| 14 | 重启服务器后重开 | `first_learned_at` 不漂移（老词时间不变、新词时间合理）；报告新词节前后一致 |
| 15 | `/lea reset Steve` | 既有 12 表清理不变（本域无新表）；reports 文件不做清理（服主的存档，保留策略只有 keep-days） |
| 16 | 单测 | `ReportPeriod`（周一锚点/跨月/lastweek/Nd 边界）+ `ReportTextFormatter`（截断、建议规则分支、缺数据占位）≥4 例；`mvn clean verify` 全绿 |
| 17 | 混服语言抽查 | 英文服（`report.language: en`）生成英文报告；执行者英文时摘要卡为英文（报告语言与执行者语言正交验证） |

---

## 6. 规划期发现（只记录，未修复）

| # | 位置 | 现象 | 等级 | 建议 |
|---|------|------|------|------|
| PF-RPT-01 | `gui/ProfileGUI.java:105` vs `data/PlayerDataStore.java:828-830` | **"正确率"恒显 100%**：M10 把 `getTotalWords()` 语义改为"仅答对数"（return totalCorrect），而 ProfileGUI 沿用旧公式 `totalCorrect / getTotalWords() * 100` → 分子分母同值，恒 100（0 除外）。对比 `LLEPapiExpansion.accuracy`（correct/(correct+incorrect)）是正确算法。玩家可见错误数字，且 PAPI 与 GUI 会互相打架 | 中 | 一行修复：改 `totalCorrect / (totalCorrect + totalIncorrect)`；本域报告**不得**复制该公式（窗口用 `Σcorrect/Σwords`）。此前 dev-findings/review 均未收录，建议进 Phase 3 修复清单 |
| PF-RPT-02 | `data/DatabaseManager.java:955-969、1120-1130`；全库 | **`learning_log` 表零写入**（全库无任何 INSERT）：统计服务 `getTotalSessions()`（仪表盘 "Total Sessions"）恒 0；`getLast7DaysStats()` 的 sessions 曲线恒 0（players/words 走 `player_sessions` 正常）。旧注释还宣称 learning_log 是会话计数数据源 | 中 | 两选一：① 仪表盘改读 `player_sessions` 聚合（推荐，报告同法）；② 补写入路径。本域报告明确**不读** learning_log。建议随仪表盘小修一并排期 |
| PF-RPT-03 | `data/DatabaseManager.java:106`；`expansion/LLEPapiExpansion.java:205`；`core/progress/ProgressManagerImpl.java:40-56` | `player_data.current_streak` **零写入**（同理 `created_at`/`last_login` 零写入，仅建表）：`%lle_streak%` 与 ProgressManager 的 currentStreak 恒 0；真数据源是 `checkin_data.streak_count`（签到系统写） | 低-中 | `%lle_streak%` 改读 `checkin_data`（或补 current_streak 写入）；本域报告用 `checkin_data`。死列（created_at/last_login）随死列清理一并决策 |
| PF-RPT-04 | `data/PlayerDataStore.java:193-194`（recordSession UPSERT） | `player_sessions.mode` 不在 UPDATE 列清单：同一天多会话合并时 mode 保持**当天首会话**值，不反映最近模式 | 低 | 报告不按 mode 出数（本域已规避）；若将来需要"最近模式"，在 UPDATE 清单加 `mode=excluded.mode` |
| PF-RPT-05 | `data/DatabaseManager.java:253-259`（checkin_data 建表） | `checkin_data` 只存 last_date + streak，**无逐日历史** → "本周签到 X 天"不可得；且 `resetCheckInStreak` 全库零调用（死方法） | 低 | 报告改用学习天数（本域已规避）；若运营要签到历史，需新表（不属本域） |
| PF-RPT-06 | `core/manager/DataExporter.java:34-65` | ① 只导出**在线玩家**（类注释自述），老师要导出离线学生用不了；② CSV 落在插件根目录而非 `exports/`（与 wordbank 导出及命令提示的 `exports/` 约定不一致）；③ 返回串 `"Export failed: …"` 与 AdminCommand 前缀判定硬耦合（P2-09 已立项） | 低-中 | ① 由本域 ReportGenerator 覆盖（离线可查）；② 新产物一律 `exports/`；③ 协议化改造顺 P2-09 排期，勿混入本域 |
| PF-RPT-07 | `core/duel/DuelManager.java`（全类） | 决斗数据**零持久化**（房间/战绩全内存）→ 任何统计/报告都无法包含决斗维度；重启即失 | 低 | 报告 v1 明确不含决斗；若将来要"对决战绩"，先决斗落表（独立事项） |
| PF-RPT-08 | `data/PlayerDataStore.java:520-528`（savePlayerData 的 SQLite 分支） | `word_progress` 用 `INSERT OR REPLACE`（整行替换语义），与 `player_data`/`player_sessions` 的 `ON CONFLICT DO UPDATE` 家族不一致——任何"保留旧列值"的增量列（如本域 first_learned_at）都不能靠 SQL 侧 COALESCE 兜底，必须在缓存侧维护（本域方案已按此设计） | 低 | 记录该模式差异，防后人踩坑；统一 SQL 风格属重构候选（不属本域） |

---

*本文件为 Phase 3 规划期只读产出，方案自定，未修改任何源码/配置；3.2 的 SQL 签名与 3.4 的派发签名为实现期冻结基准。*
