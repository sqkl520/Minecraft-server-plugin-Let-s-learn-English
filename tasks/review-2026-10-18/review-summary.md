# 全仓审查汇总报告（review-summary）

- 日期：2026-10-18
- 基线：`3bf8eae`（mvn clean verify 全绿：49 测试 + jacoco 门）
- 执行方式：13 域并行审查 + 编排者接管 2 域（R5 决斗 / R6 挑战签到）+ R5 红队验证 + 编排者冲突裁决
- 各域全文：`findings-r1.md` ~ `findings-r13.md` + `findings-r5v.md`（本目录）

---

## 一、总统计

| 域 | 高 | 中 | 低 | 待验证 | 备注 |
|----|----|----|----|--------|------|
| R1 学习核心 | 0 | 2 | 1 | 1 | 词库 995 词全检 clean |
| R2 AI 对话 | 1* | 4 | 4 | 4 | *R2-H1 建议降中（见裁决 3） |
| R3 数据层 | 3 | 4 | 4 | 5 | reset 逐表核对通过（最高价值产出） |
| R4 社交成就 | 1 | 2 | 4 | 6 | 前任 10 TODO 全核销 |
| R5 决斗 | 0 | 1 | 1 | 0 | 编排者接管 + R5V 红队证实 |
| R5V 红队 | 0 | 1 | 1 | 0 | 新发现 R5V-1（修 R12 履历表误判） |
| R6 挑战签到 | 0 | 0(2†) | 1 | 3 | †R10-H1/R11-M4 复核证实（并入主账） |
| R7 赛季跨服引导 | 2 | 2 | 5 | 3 | 自我撤回 8 条误报（纪律佳） |
| R8 命令菜单 | 0 | 1 | 4 | 2 | M1 返回栈残留帧完全取证 |
| R9 管理面 | 3 | 4 | 6 | 2 | R9-X1 被裁决证伪 |
| R10 基础设施 | 1 | 5 | 5 | 2 | R10-H1 重启刷钱（R6 复核证实） |
| R11 UX 旅程 | 0 | 4 | 4 | 3 | 旅程地图 + 静默失败清单 |
| R12 状态机 | 0 | 3 | 2 | 3 | 物品交互履历表 17 GUI 全表 |
| R13 横切面 | 0 | 4 | 7 | 2 | 线程全图/任务配对/SQL 全参数化 clean |
| **合计** | **11** | **37** | **49** | **36** | |

## 二、高危清单（11 条终判，按修复优先级排序）

| # | 编号 | 一句话 | 影响面 |
|---|------|--------|--------|
| 1 | **R10-H1**（R6 复核证实） | 同日重启后单词挑战奖励可重复发放——已付台账仅内存 Set，重启后答对任意词即重入发放；daily-cap 同为内存态不拦 | 可复现刷钱（100 金币/次重启） |
| 2 | **R4-H1** | `/lea reload` 后 tryUnlock 用空 Set 跳库 → 已解锁成就重复解锁，INSERT OR REPLACE 覆盖 unlocked_at 时间戳 + 重复播放通知/跨服广播 | 数据失真 + 社交面尴尬 |
| 3 | **R3-H3** | PlayerDataStore 五处锁外取 Connection 引用锁内使用（F1-20 范式未全库收敛）→ 重连窗口静默丢写（recordSession/savePlayerData 等） | 偶发静默数据丢失 |
| 4 | **R3-H1** | 备份任务 wal_checkpoint 在非 WAL 库为空操作 + Files.copy 在锁外 + quick_check 查错对象 → 撕裂备份被记"成功" | 灾难恢复安全网失效 |
| 5 | **R9-H1** | `/lea export` 主线程做全量文件 IO + cache-miss 查库（同族 report 已全异步——唯一漏网） | 主线程阻塞（卡服） |
| 6 | **R9-H3 / R7-H1**（同根合并） | `/lea broadcast` 无长度上限：writeUTF 65535 字节 + Minecraft 单包 32767 上限，超限异常逃逸/静默丢帧/可能踢无辜信使玩家 | 广播失败 + 误伤玩家 |
| 7 | **R9-H2 / R3-H2**（同根族） | 报告 Q4 AI 对话数：本地日期边界 vs SQLite UTC CURRENT_TIMESTAMP 时区错位（8 小时窗口平移） | 报告数字偏错（中国服） |
| 8 | **R7-H2** | 赛季 anchor-date/duration 点读可改 + tryClaimSettlement 不校验"窗口已结束" → 改锚点可对进行中赛季提前结算发奖 | 不可撤销发奖 |
| 9 | **R3-H2** | cleanupOldChatHistory 双后端日期语义分叉 + ai_chat_feedback 零清理无限增长 | 时间显示错位 + 表膨胀 |
| 10 | **R7-H1** | （与 #6 同根，跨服侧）GlobalMessage 无长度上限 | 见 #6 |
| 11 | **R2-H1**（建议降中，见裁决 3） | dispatchChat assistant 写入未包 runAsync（与同函数 user 路径不一致）——commonPool 线程持 connectionLock 写库，DB 慢时阻塞主线程等待 | 潜在卡顿窗口 |

## 三、中危速览（37 条，按域）

- **R1**：整场会话进行中可再开局静默作废旧会话（分数丢钱照发）/ 错题本练习与答题互斥缺失（输入被错误消费者吞）
- **R2**：场景 >7 时第 7+ 位 GUI 不可见不可点 / AI 轮次重启清零（daily-round-limit 绕过，烧钱面）/ 总结回调串台到新会话 / 场景选择 GUI 无决斗门（双模式困局）
- **R3**：getWordMastery CHM 递归反模式（死代码）/ decrementWrongCount 缓存-DB 竞态（错题本边界状态漂移）/ savePlayerData 缓存并集覆盖窗口 / total_sessions 双口径
- **R4**：广场 weekly 直达无快照（打开→点击窗口排名变化即点错人）/ 他人主页"过往学习"打开自己的日历
- **R5**：1 秒内 quit→join→quit 连锁 → 宽限任务覆盖僵尸 + 对局悬挂 + 离线玩家 GUI 操作（R5V 三子主张全部证实）
- **R5V-1**：DuelGUI 空槽 + bottom shift-click 放行 → 物品塞入后刷新即丢
- **R7**：参与奖结算规模无上限 / 班级名含 `&`/`§` 未过滤（染色注入）
- **R8**：返回栈残留帧被无关返回键消费（R11-L4 合并）
- **R9**：statistics-server 配置 reload 不生效无提示 / export 返回绝对路径 / tab 补全主线程 DB 全表 LIKE
- **R10**：daily-reminder reload 后关不掉 / messages 版本升级静默覆盖用户编辑 / auto-save-interval 无钳位（设 0 = 保存风暴）/ 备份校验对象错（与 R3-H1 同根）/ config"reload 即可"承诺 vs 多键快照失效
- **R11**：WordLearningGUI 标题透传英文标识 / MiscGUIListener 词库点击异常逃逸 / 挑战/日历异步预载失败静默不开 / 签到 DB 失败伪装"已签到"（R6 复核证实）
- **R12**：openAsync 无 in-flight 去重（连点/交叉火并）/ ConfirmationGUI 等 8 GUI 空槽无填充（纵深单点依赖——R5V-1 证实 DuelGUI 真实触发）
- **R13**：join 主线程同步 checkAndAssign / ChatHistoryGUI 打开 12 连查 / 广场 ALL_PLAYERS N+1 / 4 个 per-player map 无清理者

## 四、冲突裁决记录（编排者读源码终判）

1. **R9-X1 vs R3（deletePlayerData 覆盖面）**：R9 声称漏删 social_feed/social_cheers → **证伪**。编排者读 `DatabaseManager.java:2331-2401` 全文：三步删除（2369-2399）真实存在（cheerer_uuid 发出 / feed_id IN 收到 / social_feed 本体）。R3 逐表核对表正确。R9-X1 撤销。
2. **R5V-1 vs R12 履历表（DuelGUI 空槽安全）**：R12 履历表判"DuelGUI 安全（fill 是纵深冗余）"→ **R12 误判，R5V-1 成立**。R5V 精读 DuelGUIListener:39-44：bottom 点击（rawSlot >= topSize）直接 return 不 cancel → shift-click 从背包塞入 top 空槽（0-8/18/21 等 13 格）→ 每题刷新重建 Inventory 即弃置丢物。修复以 R5V-1 为准；R12 的"8 GUI 空槽无填充"清单（M3）与 R5V-1 合并处理（GUIUtils.fillEmpty 基建 + DuelGUI 立即铺满）。
3. **R2-H1 严重度商榷**：R2 判[高·确定]。编排者复核：该路径 leaf DB 访问在 connectionLock 保护下无数据竞争，无 Bukkit 面违规（R13 线程全图的"全部包 runTask/仅 DB"不与之冲突——它属"仅 DB"类）；实质 = 写法不一致 + DB 慢时主线程锁等待放大。**建议降为中危**（汇总口径按中危计；若认为锁等待=卡服判据，维持高危亦可，修复成本 3 行）。
4. **R7-H1 与 R9-H3 同根合并**：同一缺口（sendForward 无长度上限）从发送侧（R7）与命令侧（R9）两次独立发现——合并为一条修复（handleBroadcast 校验 + sendForward try/catch）。
5. **R3-H2 与 R9-H2 同族合并**：时区错位族（DB 默认值 UTC vs Java 本地）两处发现——统一修复口径（存储侧显式 UTC 或读取侧转换，与 P3-12 时区统一对齐）。

## 五、回归核验汇总（D1-D28 全部有结论）

**PASS（23 项）**：D1 统计真源 / D2 streak 真源 / D3 午夜两段式 / D4 C-3 openAsync / D5 C-7 openAsync / D6 R-01 清洗三字段 / D7 帧解析 / D8 无双计漏计 / D9 P2-08 快照 / D11 模块门全入口 / D12 榜单快照全登记 / D13 update-interval 钳位 / D14 config-version 三处 / D15 money 门双分支 / D16 bye 主线程+守卫 / D17 F1-18 两分支反馈 / D18 僵尸任务 self-cancel（到期分支）/ D19 exports 路径 / D20 Asia/Shanghai 清零 / D21 死链移除 / D22 8 死键+补键 / D23 CountdownTier 双域 / D24 J-3/J-4 / D25 索引幂等 / D26 音效盲点 / D27 PF-ONB / D28 注释面
**部分裂口（2 项）**：D10（F1-20 只修了 loadPlayerData，同文件 5 处旧式仍在 → R3-H3）；D18（到期分支已修，但"覆盖写"路径漏网 → R5-M1）
**新增回归证据**：R-02 死变量删除未引入双计（R9 补记）；F3-01/D12 快照全登记（R4 正面确认）；updateScore B5 债已修复（R4）

## 六、正面结论（大面 clean，防过度悲观）

1. **线程纪律**：31 个异步调用点逐点回溯——零未包裹 Bukkit 直触（R13+R2 交叉）；22 处任务取消链全成对（R13）
2. **SQL**：全仓零字符串拼接注入（唯一动态 = tablePrefix + IN 占位符展开，值全参数化）（R13）；2 处非参数化均为 int 常量（R3-L2/R7 已记）
3. **物品交互安全**：17 GUI × 5 交互型履历表——11/11 listener 有独立 drag guard；唯一真实缺口 = DuelGUI 空槽 shift-click（R5V-1）；无复制漏洞（R12+R5V）
4. **reset 覆盖面**：runtime 19 表逐表核对——18 张玩家数据表全删、2 张无玩家列正确豁免、schema 双源列级零漂移（R3）
5. **词库内容**：995 词全检——零重复/零缺字段/难度分布与档位吻合（R1）
6. **成就判据诚实性**：22 成就判据与文案逐一对齐（R4）
7. **i18n**：zh/en 725/725 键完全对称、零硬编码玩家文案、死键已清（R13+R11）
8. **回归面**：28 项历史大修 23 项直接 PASS、2 项部分裂口、其余正面确认（见五）
9. **回环/幂等**：赛季结算抢占 + relay origin + 台账幂等链完整（R7）；挑战/签到双击竞态双保险成立（R12）；离线解散/宽限自取消主体正确（R5V）
10. **审计文化**：3 域审查 agent 自我撤回过误报（R2 撤 6 条 / R7 撤 8 条 / R8 前任台账），1 条跨域冲突被证伪（R9-X1）——"先证实再立案"纪律有效运转

## 七、待验证区汇总（36 条，实机/双服/运行期类）

- **实机验证类**：R1-V1 错题本 DB 往返 / R2-V1 反馈行时序 / R5-M1 真机复现（静态已全闭合）/ R8-M1 两序列实机 / R11 三条（重开拽回/零词库/ClickEvent）/ R12-V1/V2/V3 / R6 三条
- **双服验证类**：R7-T2 origin 回环实测 / R3-T1 MySQL 后端整链
- **运行期量化类**：R13-V1 关服窗口 / R13-V2 统计 HTTP 负载 / R3-T2/T3 备份撕裂概率 / R9-V2 单连接排队
- **裁决类**：R10-T2 %lle_streak% 隐私门归属（R4 口径）/ R13-V2 量化

## 八、修复批次建议（供后续排期）

- **批 A（高危安全网，建议立即）**：R10-H1（挑战奖励持久化）/ R4-H1（tryUnlock 懒加载 3 行）/ R3-H3（锁外取引用机械修）/ R3-H1（备份三修或 VACUUM INTO）
- **批 B（高危体验/稳定）**：R9-H1（export 异步化）/ R9-H3+R7-H1（broadcast 长度校验）/ R9-H2+R3-H2（时区统一）/ R7-H2（结算窗口校验）
- **批 C（中危玩家可感）**：R1-M1/M2（会话互斥）/ R2-M1~M4 / R5-M1（三处小改）/ R5V-1（DuelGUI 铺满）/ R4-M1/M2 / R8-M1 / R11-M2~M4
- **批 D（卫生与基建）**：R12-M3（GUIUtils.fillEmpty）/ R13-M1~M4（join 异步化等）/ 死代码批（R13-L1 等）/ R10-M1~M5 配置面 / R9-M2/M4
