# Phase 3 领域 7 规划：资源包音频 + 反馈层（ActionBar / BossBar / 连击）

- 角色：Phase 3 规划 agent（只读，未改任何源码/配置）
- 日期：2026-10-13 · 版本：1.0.7-SNAPSHOT（不 bump）
- 施工状态： **3A 已施工**（P3 Wave 1，提交 `db1e5bc`；三维 tester GO/PASS/PASS；**3A-4 音效开关 UI 漏项已于 2026-10-16 补建** `3beaeb5`——原声明完成但设置页无入口）；**3B 资源包不做**（D7 默认裁决）
- 范围来源：`tasks/plan-roadmap-usable-to-delightful.md` 第 8 节 Phase 3 方向清单第 7 条（资源包音频 + 连击 pitch 递增）与第 8 条（倒计时改 ActionBar/BossBar）+ 3.0 调研结论的自定义音频标准链路
- 规划期发现见文末第 6 节，一律未修

---

## 0. 现状盘点（只读核实结论）

| 事实 | 证据（文件/行） |
|------|----------------|
| SoundManager 现有 5 键：correct / incorrect / complete / menu-open / menu-click，config 路径 `gui.sounds.*`，缓存 `Map<String, Sound>`（enum） | `core/SoundManager.java` L59-64、L40 |
| 静态入口仅 `playSound(Player, String configKey)`，音量/音高写死 1.0f/1.0f | `core/SoundManager.java` L117-136、L135 |
| `resolveSound` 只解析原版 enum 名与 `minecraft:` 命名空间名，**无法解析自定义命名空间**（如 `lleep:ui.correct`） | `core/SoundManager.java` L87-103（`Sound.valueOf`） |
| 未知键首次 WARNING + 静默跳过；空串/非法名回落默认音并 WARNING（不能表达"故意静音"） | `core/SoundManager.java` L66-81、L124-133 |
| `VersionAdapter.playSound(Player, Sound, float, float)` 是跨版本音效唯一出口 | `util/VersionAdapter.java` L70-72 |
| 学习倒计时：`runTaskTimer` 20L 周期，**每秒一条聊天消息**（>5s 走 countdown-normal，≤5s 走 countdown-urgent，硬编码阈值 5） | `core/word/WordLearningManager.java` L294-332（L320 `remaining[0] <= 5`） |
| 超时结算：聊天 timeout 文案 + `SoundManager.playSound(player, "incorrect")`（R-84） | `WordLearningManager.java` L308-319、L200 |
| 答对：`SoundManager.playSound(player, "correct")`（无 pitch 变化）+ 聊天 correct/combo-message | `listener/ChatAnswerListener.java` L215-228 |
| 连击倍率表：阈值 {0,3,5,7,10}，倍率 {1.0,1.5,2.0,2.5,3.0}；`AnswerResult` 已携带 consecutiveCorrect / comboMultiplier / maxComboReached | `WordLearningManager.java` L30-31、L657-694 |
| 对决每题超时：单次 `runTaskLater`（无任何过程提示，静默 30s 后判超时）；任务容器 `wordTimeoutTasks`（key=player1 UUID，cleanup/shutdown 均已收口） | `core/duel/DuelManager.java` L415-434、L788-808、L824-857 |
| 对决超时反馈：双方聊天 `duel.word-timeout` + `incorrect` 音（R-84 已补） | `DuelManager.java` L441-472 |
| 对决宽限（掉线）倒计时：每秒写记分板 title（`updateScoreboardWithGrace`）+ `≤5s 或 %10==0` 聊天（双通道） | `DuelManager.java` L570-623、L937-951 |
| 记分板清理模式：所有拆解路径（endDuel/quit/cancel/grace 到期/内部取消）都汇到 `cleanup(room)` + `removeScoreboard(player)`；shutdown() 幂等清扫 | `DuelManager.java` L788-857、L953-962 |
| TTS：发音提示音 = `BLOCK_NOTE_BLOCK_PLING` @ pitch 1.5（独立 Sound）；下载缓存为 **.wav**（VoiceRSS `f=44khz_16bit_stereo`），注释自述"留作未来资源包集成" | `core/ai/TtsManager.java` L184-199、L94、L27 |
| 玩家偏好现有载体：`ProfileSettings`（age/show_answers/show_results/show_in_square）+ `player_profiles` 表 + join load / quit save 已接线 | `data/PlayerDataStore.java` L833-933、`listener/PlayerJoinListener.java` L28/L91 |
| 设置 GUI 槽位：11/13/15 三个 switch，边框含 {0,1,2,3,5,6,7,9,17,18,19,20,21,22,23,24,25}，**槽 16 空闲**；点击处理 `handlePrivacySettings` 判定 `slot == 11 || 13 || 15` | `gui/ProfileGUI.java` L236-238、`listener/MiscGUIListener.java` L95-106 |
| DB 迁移工具：`addColumnIfMissing(table, column, definition)`（DatabaseManager 已用于 player_data 多列） | `data/DatabaseManager.java` L114-117、L1334+ |
| reload 已接线 `SoundManager.reloadSoundCache()`（config 重载之后） | `core/manager/HotReloadManager.java` L42-45 |
| i18n 双文件版本机制：`MESSAGE_CONFIG_VERSION = 11`（zh/en 对称，v11）；`countdown-normal/urgent` 中英均在（F2-09 已修） | `config/ConfigManager.java` L25、`messages/zh.yml` L428-429、`messages/en.yml` L428-429 |
| 全库**零** ActionBar / BossBar 使用（含 FIX-CHAL-01 落地时改用的聊天版提醒）；无集中 ActionBar 工具 | grep 全库无命中 |
| paper-api = 1.21.4-R0.1-SNAPSHOT，Java 21；`Player.sendActionBar(String)` **已 @Deprecated**（javap 验证 Deprecated: true），Adventure `Audience.sendActionBar(Component)` 可用；`Player.playSound(Location, String, float, float)` **未废弃**（javap 验证）；`Bukkit.createBossBar` 未废弃，但 `BossBar.show()/hide()` 已 @Deprecated（javap 验证） | `pom.xml` L19；本地 m2 的 paper-api-1.21.4 jar javap 输出 |
| Adventure 依赖可用：paper-api pom 对 adventure-api / adventure-text-serializer-legacy 为 compile scope 传递依赖（m2 中 4.20.0 已就位，`LegacyComponentSerializer.legacySection()` 可用，javap 验证） | paper-api pom + 本地 m2 |
| 插件实际已 Paper-only：`VersionAdapter` 构造即调 `Server.getMinecraftVersion()`（Paper 专有），plugin.yml `api-version: "1.21"`；README 仍写 "Paper / Spigot 1.20+"（见发现 7） | `util/VersionAdapter.java` L26、`plugin.yml` |
| pom 打包边界：仅 `src/main/resources` 进 JAR（words/sample-* 已排除）；仓库根目录新文件夹天然不进 JAR | `pom.xml` L95-113 |

---

## 1. 操作逻辑

### 1.(A) 路线判断：全自定义资源包 还是 原版音效编排？

**推荐：分两步走，优先做原版音效编排 + 反馈层（3A，默认路线、立即做）；全自定义资源包降级为可选增强线（3B，config 默认关、由用户裁决是否投入）。**

理由（玩家接受成本 / 服主部署成本 / 收益对比 / 技术可行性）：

1. **玩家接受成本**：资源包在 join 时弹确认框（accept/decline），手机端玩家普遍 decline；decline 后自定义音全静默，体验反而比现在差——必须再叠一层 per-player 回退开关，否则"改造即降级"。
2. **服主部署成本**：包文件不在 JAR 内，需自备 HTTPS 直链托管、每次改动重算 SHA-1、处理缓存失效；对接 Velocity/Bungee 多子服时每服各发一次（客户端按 hash 缓存，尚可接受）；与其它插件资源包**音效键冲突时加载顺序不确定**（重复键互相覆盖）——对"学习插件"这种锦上添花的需求，投入产出比低。
3. **收益对比**：本插件所有反馈点位用**原版音效 + pitch/volume 编排**已能拿 80-90% 的听感提升（连击升调、倒计时 tick、里程碑铃声都是原版音源），剩余的"品牌感"增益装不进"学习闭环"的优先级。
4. **技术可行性（TTS 不兼容，关键否决项）**：TTS 缓存是 **.wav**，资源包只吃 **.ogg Vorbis**——动态 TTS 音频想进资源包必须服务端转码（引入 ffmpeg 或纯 Java 转码依赖，且 VoiceRSS 频率/格式受限），3B 必须明确**排除动态 TTS**，只打包静态 UI 音。也就是说自定义包并不能把已有 TTS 资产"用起来"。
5. **架构成本**：自定义音必须走 `playSound(Location, String, ...)` 字符串通道（见发现 5），SoundManager 的 enum 缓存模型要开第二条腿 + per-player 包状态跟踪（`PlayerResourcePackStatusEvent`），复杂度集中在最底层的公共类上，收益却在边际层。
6. **Roadmap 原文第 7 条本就写了"回退原版音效"**——即自定义路线也必须保留原版编排层。先把编排层做扎实（3A），3B 才是有价值的锦上添花而非空中楼阁。

**若日后走 3B（用户拍板后），标准链路与草案如下**（本规划给出，供决策不预做实现）：

- 下发：`player.setResourcePack(url, sha1Bytes, promptComponent, false)`（1.20.3+ 必须带有效 SHA-1；URL 需可直连下载，重定向/HTML 页会判 INVALID_URL/FAILED_DOWNLOAD；`required=false` 永远不要设 true）；监听 `PlayerResourcePackStatusEvent.Status`（SUCCESSFULLY_LOADED / DECLINED / FAILED_DOWNLOAD / INVALID_URL / FAILED_RELOAD / DISCARDED），内存维护 `Set<UUID> packLoaded`（quit 清理）。
- 播放：`player.playSound(loc, "lleep:ui.correct", vol, pitch)`（String 重载未废弃，javap 已验证）。
- 回退：`packLoaded` 未命中 → 一律走现有 `gui.sounds.*` 原版键；`custom-sounds.enabled=false` 或 `pack-url` 为空 → 整条自定义路不激活（即 3A 行为）。

**sounds.json 结构草案**（命名空间 `lleep`）：

```json
{
  "lleep.ui.correct":          { "sounds": ["lleep:ui/correct"] },
  "lleep.ui.incorrect":        { "sounds": ["lleep:ui/incorrect"] },
  "lleep.ui.complete":         { "sounds": ["lleep:ui/complete"] },
  "lleep.ui.combo_milestone":  { "sounds": ["lleep:ui/combo_milestone"] },
  "lleep.ui.countdown_tick":   { "sounds": ["lleep:ui/countdown_tick"] },
  "lleep.ui.timeout":          { "sounds": ["lleep:ui/timeout"] }
}
```

**音频清单（6 个文件，均为静态 UI 音，不含动态 TTS）**：

| 文件 | 事件 | 风格建议 | 时长 |
|------|------|---------|------|
| `ui/correct.ogg` | 答对 | 清脆上扬双音（C5→E5） | ~0.6s |
| `ui/incorrect.ogg` | 答错 | 低沉短提示（下行小三度） | ~0.5s |
| `ui/complete.ogg` | 会话完成/成就/对决获胜 | 小和弦琶音 | ~1.5s |
| `ui/combo_milestone.ogg` | 连击达 3/5/7/10 | 亮铃一声 | ~0.8s |
| `ui/countdown_tick.ogg` | 倒计时最后 N 秒 | 短 click（pitch 由代码递增） | ~0.1s |
| `ui/timeout.ogg` | 超时（学习/对决共用） | 下行滑音 | ~0.7s |

menu-open/menu-click 保留原版（打开/点击非品牌时刻，不值得占包体积）；**不做氛围 BGM**（游戏自带 music 音轨，注入需替换 music 事件或自定义 sound category，听感冲突风险高、体验争议大，明确排除）。

**回退策略（三级）**：① 包未加载（declined / failed / 下载中）→ 原版 `gui.sounds.*` 键；② 包已加载但 `custom-sounds.enabled=false` → 原版；③ 包已加载且启用 → 自定义键，散件缺失属发布事故（hash 锁定 + 发布检查单兜底，运行时不可测）。

### 1.(B) 反馈层改造

#### B1. 学习倒计时：聊天栏 → ActionBar 状态机

**触发点**：`WordLearningManager.startAnswerTimer()`（唯一入口：`WordLearningListener.onInventoryClose` 关 GUI 启动答题、`start-button` 点击、死亡重生重开 GUI 后再关）。timer 启动即发送一次 ActionBar（全时值），此后每秒更新，与现有 `runTaskTimer(20L, 20L)` 同频——**不新增定时器，只换载体**。

**状态机**（单玩家，题目粒度）：

| 阶段 | 条件 | ActionBar | 音效 | 聊天栏 |
|------|------|-----------|------|--------|
| 出题（GUI 打开中） | `isAnswering=false` | 无（无 timer） | — | 题面 answer-prompt（含总秒数，保留） |
| 计时·常规 | `remaining > urgentSeconds`（默认 5） | `countdown-normal` 渲染（黄 `&e`） | 无 | **无**（本改造的核心：不再刷屏） |
| 计时·紧急 | `urgentSeconds ≥ remaining > 0` | `countdown-urgent` 渲染（红 `&c`，可在文案里自带 `&l`） | 最后 `tick-sound-seconds`（默认 3）秒每秒 `countdown-tick`，pitch 随剩余秒递减递增（如 `1.0 + (tickFrom - remaining) * 0.15`） | **无** |
| 到点 | `remaining == 0` | 停更（客户端 ~2 秒后自动淡出，**无需清理**） | `timeout` 音（缺省回退 incorrect 同音） | 1 条 `word-learning-gui.timeout`（保留） |
| 答对/答错/迟到 | `submitAnswer` → `cancelAnswerTimer` | 停更（自动淡出） | 现有 correct/incorrect（pitch 逻辑见 B3） | 现有 correct/incorrect 文案 |
| 死亡暂停 | `onPlayerDeath` → `cancelAnswerTimer` | 停更（自动淡出） | 无（R-13 语义不变） | death-paused（保留） |

**关键设计点**：
- **文案键复用**：`word-learning-gui.countdown-normal / countdown-urgent` 保留键名与中英文案（ActionBar 直接渲染），仅把 `player.sendMessage` 换成 `versionAdapter.sendActionBar(player, text)`。中英文件均已有键，不产生新键（除下面的里程碑键）。
- **urgent 阈值可配**：`remaining[0] <= 5` 硬编码（L320）提为 `feedback.countdown.urgent-seconds`（默认 5，保持现状）。
- **与题目切换同步**：倒计时窗口严格等于"关 GUI 后到判分"；判分即 `cancelAnswerTimer`，ActionBar 停更自动淡出 ≤2s，下一题关 GUI 重新起表——与 GUI 内 progress-info/combo-info 展示不冲突（GUI 打开时无 timer、无 ActionBar）。
- **不额外清理**：ActionBar 无服务端状态，quit/死亡/会话结束全部走现有 `cancelAnswerTimer`，天然无泄漏——这是选 ActionBar 而非 BossBar 的首要理由。

#### B2. 对决每题倒计时：静默 runTaskLater → 可见倒计时（ActionBar/BossBar）

**现状缺陷**：每题超时是单次 `runTaskLater`（DuelManager L415-424），30 秒内双方**零提示**，到点突然判超时——与学习侧体验严重不对称，也是验收"对决超时音效清晰"要治的对象。

**改造（学习用 ActionBar、对决用 BossBar，各有理由）**：
- 学习流程 = 短促、单人、注意力在聊天输入 → **ActionBar**：无状态、零清理、存在感弱。
- 对决 = 竞赛感、双人、GUI/记分板并存 → **BossBar**：一条随时间排空的进度条天然表达"抢答赛跑"，且对决已有完整的任务/记分板清理模式可套用。同一界面**不再叠加 ActionBar**（避免双通道噪音）。

**对决倒计时状态机（`scheduleWordTimeout` 重构）**：

```
scheduleWordTimeout(room):
  cancelWordTimeout(room)
  round = room.getWordRoundId();  total = getWordTimeoutSeconds()
  remaining = total;  立即渲染（title=第 total 秒文案，progress=1.0）
  task = runTaskTimer(1s):
    if !room.isActive()            → 自清理（remove+self cancel，抄 R-26 grace 模式）; return
    if room.getWordRoundId() != round → 自清理; return        // 已答/已换题（stale）
    remaining--
    if remaining <= 0 → 自清理; handleWordTimeout(room, round); return
    渲染：BossBar progress = remaining/total
    tier：remaining ≤ urgent-seconds → 标题切 urgent 文案 + setColor(RED) + tick 音（最后 N 秒）
    否则标题黄、BarColor.YELLOW
  容器：wordTimeoutTasks.put(room.getPlayer1(), task)   // 沿用现有 Map，类型不变
```

- BossBar 对象**每房间一个、复用不重建**：`acceptDuel` 创建（`Bukkit.createBossBar(...)`，主线程）+ 双方 `addPlayer`；每题只 `setTitle/setProgress/setColor`；换题重置为满格。
- **清理（防泄漏）**：挂钩在 `cleanup(room)`（L788）单点收口 `bar.removeAll()` + 从容器移除——所有拆解路径（endDuel / quitDuel / cancelDuelByAdmin / cancelDuelInternal / grace 到期）都必经 `cleanup`；`shutdown()`（L824）末尾再全量扫一遍（幂等）。断线不主动 removePlayer（宽限期内对手仍看得到条），重连 `handleReconnect`（L629）重新 `addPlayer`。
- 顶部 `handleWordTimeout` 内的 `wordTimeoutTasks.remove` 调整为配合新自清理语义（详见实现要点）。
- 对决宽限倒计时（掉线 30s）**本期不改**：它已有记分板 title 通道，改造为 BossBar/ActionBar 属体验统一候选（记录，不在本期范围）。

#### B3. 连击反馈升级（pitch 递增 + 里程碑）

**pitch 公式**（新纯函数，放 `WordLearningManager.getComboPitch(int combo)`，与 `getComboMultiplier` 并列，便于单测）：

```
pitch(combo) = clamp( pitch-base + pitch-step × (combo - 1), 0.5, pitch-max )
默认 pitch-base=1.0, pitch-step=0.10, pitch-max=1.6
```

- combo=1 → 1.0（与现状一致）；combo=2 → 1.10；combo=5 → 1.40；combo=7 封顶 1.6。
- 客户端对 pitch 有 [0.5, 2.0] 硬钳制，`pitch-max` 默认 1.6 留余量防越界听感跳变。
- 接线点：`ChatAnswerListener` 答对分支 `SoundManager.playSound(player, "correct", getComboPitch(consecutiveCorrect))`（新重载，见实现要点）。

**里程碑**：`consecutiveCorrect` 命中 `COMBO_THRESHOLDS` 中非零值（3/5/7/10，**与倍率表同源，不引入第二份阈值配置**）时：
- ActionBar 一闪 `word-learning-gui.combo-milestone`（如 `&6&l⚡ {count} COMBO!`，`{multiplier}` 同步显示倍率）；
- 播 `combo-milestone` 音（默认 `BLOCK_NOTE_BLOCK_BELL`，pitch 1.0），**该 tick 用里程碑音替代升调 correct 音**（避免双音叠加浑浊）；
- 聊天栏 combo-message 每连击照旧（≥2 时，现状保留）；里程碑不额外刷聊天行——由 ActionBar 承担。
- 开关：`feedback.combo.milestone-enabled`、`feedback.combo.pitch-enabled`。

**与聊天文案配合**：聊天=持久记录（correct + combo-message），ActionBar=瞬时高光（里程碑），音效=情绪曲线（升调）。三层职责分离，无重复信息轰炸。

#### B4. 学习/对决复用关系

| 复用件 | 形态 | 消费方 |
|--------|------|--------|
| `VersionAdapter.sendActionBar(Player, String)` | 新增实例方法（Adventure Component 封装） | 学习倒计时、连击里程碑、（未来：挑战通知等） |
| `SoundManager.playSound(Player, String, float pitch)` / `(…, volume, pitch)` | 新增重载，旧签名原样保留 | 连击、（3B 自定义音通道） |
| `core/feedback/CountdownTier`（enum NORMAL/URGENT + 静态 `tierOf(remaining, urgentSeconds)`） | 新微小工具类（纯函数，可单测） | 学习 timer、对决 timer 共用 tier 判定 |
| `countdown-tick` / `timeout` 音效键 | SoundManager 新键 | 两处倒计时 + 两处超时 |

---

## 2. 用户体验

### 2.1 玩家视角"手感"

**每次答题的反馈序列（spelling 为例）**：
1. GUI 内看到中文题面 → 点"开始答题"（menu-click 音）→ 关 GUI；
2. 聊天栏 1 条题面（含总限时）→ **ActionBar 立刻出现 `⏳ 还需 30s 作答`**；
3. 每秒 ActionBar 数字静默递减（聊天栏干干净净，不再滚屏）；
4. 剩 5s：ActionBar 变红加粗，剩 3/2/1s 每秒一声加速的 tick（1.0→1.3），紧张感"听见"；
5. 答对：升调 correct 音（连击越高越亮）+ 聊天 `✔ 正确! +15`；连击达 3/5/7/10：ActionBar 闪 `⚡ N COMBO` + 铃音一声；
6. 超时：一声下行 timeout 音 + 聊天 1 条正确答案；断连击音高复位。

**对决紧张感**：开赛即有黄色倒计时条挂在屏幕上方（BossBar）随时间排空；最后 5s 变红 + 每秒 tick；双方同时听见"同一只钟在走"，超时双方同响 timeout + 答案揭示（对称无惩罚语义不变）。答对抢到分时条立刻重置满格进入下一题。
**连击爽感**：正确音一路爬坡，里程碑铃音+大字是高光时刻，断连击后音高回落形成"重新爬梯"的暗爽。

### 2.2 设置开关（推荐：per-player，非仅 config）

- **推荐**：`gui.sounds.enabled`（服主总闸，已有）保持；新增 **per-player 开关** `sounds-enabled`，默认开，落库 `player_profiles.sounds_enabled`，UI 挂在**隐私设置 GUI（现为"设置"语义）槽 16**（空闲槽，见现状盘点）。
- 理由：① 音效是 accessibility 偏好，玩家自主权应高于服主代决；② 现成基础设施（ProfileSettings + join/quit 生命周期 + 设置 GUI）边际成本仅为 1 列 1 开关；③ decline 资源包/静音党的兜底需求（3B 前置件）。
- 语义：per-player=false → 所有 SoundManager 播放静音（含菜单、连击、tick、TTS cue），**ActionBar/BossBar 视觉保留**（视觉与音频开关解耦）；服主总闸=false 时一切照旧全关（现状不变）。
- 落地：`SoundManager.playSound` 内查 `getProfileSettingsReadOnly(uuid).isSoundsEnabled()`（ConcurrentHashMap.get，热路径零开销；缓存 miss 默认 true，fail-open）。

### 2.3 ActionBar 与 GUI 关闭答题模式的配合

现有流程"关 GUI → 聊天答题"与 ActionBar 天然互补：**GUI 打开期无计时器无 ActionBar；关 GUI 起表 ActionBar 常驻 HUD**（HUD 在无 GUI 时可见）。判分后 `cancelAnswerTimer` 停更，ActionBar 约 2s 自动淡出，不会盖住下一题的 answer-prompt。死亡暂停/下线/停会话全部复用现有取消路径，无悬挂文本。

### 2.4 config.yml 新键草案

```yaml
gui:
  sounds:
    enabled: true                      # 服主总闸（已有）
    correct: "ENTITY_PLAYER_LEVELUP"
    incorrect: "ENTITY_VILLAGER_NO"
    complete: "UI_TOAST_CHALLENGE_COMPLETE"
    menu-open: "BLOCK_NOTE_BLOCK_PLING"
    menu-click: "BLOCK_NOTE_BLOCK_HAT"
    # --- Phase 3 新增（旧配置文件缺键自动用默认值注册，无需迁移）---
    combo-milestone: "BLOCK_NOTE_BLOCK_BELL"   # 连击里程碑铃声
    countdown-tick: "BLOCK_NOTE_BLOCK_HAT"     # 倒计时 tick（学习+对决共用）
    timeout: "ENTITY_VILLAGER_NO"              # 超时专用（学习+对决）；与 incorrect 缺省同音
    # 以上任一配 "none" 或空串 "" 表示该事件静音，不记 WARNING（新增语义）

# ============================================================
#  反馈层设置 (Feedback Settings)
# ============================================================
feedback:
  countdown:
    # 剩余秒数 ≤ 该值切"紧急"样式（红色 + tick 音）；学习与对决共用
    urgent-seconds: 5
    # 最后 N 秒每秒播 countdown-tick；0 = 不播
    tick-sound-seconds: 3
    # 对决每题是否显示 BossBar 倒计时条
    duel-bossbar: true
  combo:
    # 答对音随连击升高 pitch
    pitch-enabled: true
    pitch-step: 0.10
    pitch-max: 1.6
    # 连击里程碑（3/5/7/10，与倍率表同源）ActionBar + 音效提示
    milestone-enabled: true
```

（`config-version` 保持 2；`word-system.answer-timeout` 不动。reload 已接线 SoundManager 刷新。）

### 2.5 i18n 草案（zh.yml / en.yml 双写，MESSAGE_CONFIG_VERSION 11→12）

```yaml
word-learning-gui:
  # countdown-normal / countdown-urgent 键名与文案保留（改由 ActionBar 渲染，无新键）
  combo-milestone: "&6&l⚡ {count} COMBO! &e({multiplier}x)"     # 新增
duel:
  round-bossbar: "&e⚔ 本题剩余 {seconds}s"                        # 新增
  round-bossbar-urgent: "&c&l⚔ {seconds}s！"                      # 新增
profile:
  settings-sounds: "音效反馈"                                      # 新增
  settings-sounds-on: "&a当前: 开启"                                # 新增（en: "&aCurrent: On"）
  settings-sounds-off: "&7当前: 关闭"                               # 新增（en: "&7Current: Off"）
```

（en.yml 对应：`combo-milestone: "&6&l⚡ {count} COMBO! &e({multiplier}x)"`、`round-bossbar: "&e⚔ {seconds}s left"`、`round-bossbar-urgent: "&c&l⚔ {seconds}s!"`、`settings-sounds: "Sound Effects"`。）

---

## 3. 实现要点

### 3.1 触及文件清单

| 文件 | 改动 |
|------|------|
| `core/SoundManager.java` | 注册 3 新键；`playSound` 加 pitch/volume 重载 + per-player 门 + "none" 静音语义 |
| `util/VersionAdapter.java` | 新增 `sendActionBar(Player, String)`（Adventure 封装） |
| `core/feedback/CountdownTier.java`（新） | tier 枚举 + `tierOf(remaining, urgentSeconds)` 纯函数 |
| `core/word/WordLearningManager.java` | 倒计时 timer 改 ActionBar + tick 音 + 阈值读 config；`getComboPitch(int)` 纯函数；countdown 两行裸 getString 顺带收敛 `MessageUtil.getGuiMsg`（P2-13 在案，不扩范围） |
| `listener/ChatAnswerListener.java` | 答对分支接 combo pitch；里程碑 ActionBar + 音 |
| `core/duel/DuelManager.java` | `scheduleWordTimeout` 重构为 runTaskTimer + 自清理；BossBar 创建/更新/清理（cleanup + shutdown + reconnect）；timeout 音换新键（默认同音） |
| `resources/config.yml` | gui.sounds 3 新键 + feedback 节 |
| `resources/messages/zh.yml` / `en.yml` | 6 新键 + `MESSAGE_CONFIG_VERSION` → 12（编排者统一收口） |
| `data/PlayerDataStore.java` | ProfileSettings 加 `soundsEnabled`；load SELECT / save UPSERT 同步加列 |
| `data/DatabaseManager.java` | `addColumnIfMissing(player_profiles, sounds_enabled, "BOOLEAN DEFAULT 1")` |
| `resources/schema.sql` | player_profiles 列定义同步 |
| `gui/ProfileGUI.java` + `listener/MiscGUIListener.java` | 设置 GUI 槽 16 开关 + 点击分支 |
| `CHANGELOG.md` | 编排者收口 |
| （3B 专属）`resourcepack/`（仓库根，不进 JAR）+ `core/ResourcePackManager.java` 等 | 见 3.5 |

### 3.2 SoundManager 扩展方式（静态签名演进）

```java
public static void playSound(Player player, String configKey)                       // 现存，~60 调用点不动
public static void playSound(Player player, String configKey, float pitch)          // 新增
public static void playSound(Player player, String configKey, float volume, float pitch) // 新增（前两者委托）
private static float clampPitch(float p)   // [0.5, 2.0]
```

- 新键在 `reloadSoundCache()` 追加 `cacheSound(config, "combo-milestone", "BLOCK_NOTE_BLOCK_BELL")` 等三行；`timeout` 的默认值取 `gui.sounds.incorrect` 的解析结果（缺省同音，旧行为零变化）。
- **"none/空串 = 故意静音"**：`cacheSound` 检测到 `gui.sounds.<key>` 为 `""` 或 `none`（大小写不敏感）→ 不注册、不记 WARNING（区别于"拼写错"的 WARNING 语义）。
- per-player 门加在 `playSound(Player, String, float, float)` 最前端（在 enabled 检查旁）：`if (!manager.isPlayerSoundEnabled(player)) return;`，内部 `plugin.getPlayerDataStore().getProfileSettingsReadOnly(uuid).isSoundsEnabled()`（store 为 null 时 fail-open 放行，兼容纯逻辑单测）。
- 热重载：新键随 `reloadSoundCache` 自动生效，HotReloadManager 零改动。

### 3.3 ActionBar 的 VersionAdapter 兼容（Paper API 现状）

- 已 javap 核实：paper-api 1.21.4 中 `Player.sendActionBar(String)` 已 @Deprecated，`Player.sendActionBar(char, String)` 同；Adventure `Audience.sendActionBar(Component)` 为接口 default 方法且可用（adventure-api 经 paper-api compile scope 传递依赖已就位，4.20.0）。
- 推荐实现（版本差异集中在 VersionAdapter，符合项目"版本隔离"原则）：

```java
public void sendActionBar(Player player, String legacyText) {
    player.sendActionBar(net.kyori.adventure.text.serializer.legacy
            .LegacyComponentSerializer.legacySection().deserialize(legacyText));
}
```

- 调用方先 `ChatColor.translateAlternateColorCodes('&', msg)` 再传入（与现有文案管线一致），`legacySection()` 吃 `§` 码。
- 兼容性结论：插件事实上已是 Paper-only（`getMinecraftVersion()` 为 Paper 专有 API，plugin.yml `api-version: "1.21"`），Adventure 不引入新的平台约束；但 README 的 "Paper / Spigot 1.20+" 表述应同步校正（见发现 7）。

### 3.4 BossBar 的调度与清理（防泄漏，参考对决记分板 cleanup 模式）

- 创建：`acceptDuel` 内 `Bukkit.createBossBar(title, BarColor.YELLOW, BarStyle.SOLID)`（主线程），`addPlayer` 双方；**不用已废弃的 `show()/hide()`**（javap 核实已 @Deprecated），用 `addPlayer/removeAll` 控制显隐。存储 `Map<UUID, BossBar> duelBars`（key=room.getPlayer1()，与 wordTimeoutTasks 同键风格）。
- 更新：每题 `setTitle`（含 `{seconds}` 的 i18n 文案）+ `setProgress(remaining/total)` + urgent 时 `setColor(BarColor.RED)`；无新增定时器以外成本。
- 清理矩阵（对照现有记分板清理点逐一挂钩，防泄漏）：

| 路径 | 现有动作 | 新增 |
|------|---------|------|
| `cleanup(room)`（所有拆解路径必经） | 删 map/cancel 任务 | `duelBars.remove(p1)` → `bar.removeAll()` |
| `shutdown()` | cancelAllTasks + 记分板复位 | 遍历 `duelBars` removeAll + clear（幂等） |
| `handleReconnect` | setupScoreboard 重设 | `addPlayer(player)` 回挂 |
| `handleDisconnect` | 记账宽限 | 不动作（宽限期内对手仍看条） |
| `handleWordTimeout`/换题 | — | 不重建对象，仅重置 title/progress |

- 对决宽限倒计时维持记分板 title（本期不动）。BossBar 在"玩家开着 GUI"时的可见性待测试确认（人工用例 D3 覆盖，见发现 10 与风险 3）。

### 3.5 3B 资源包目录结构（不随 JAR 发布的部分）

```
resource-pack/                      ← 仓库根（Maven 只打包 src/main/resources，本目录天然不入 JAR）
├─ pack.mcmeta                      { "pack": { "pack_format": 46, "supported_formats": [34, 46], "description": ... } }
│                                   （pack_format 按目标版本查表：1.21–1.21.1=34 / 1.21.2–1.21.3=42 / 1.21.4=46，上线前复核）
├─ README.md                        部署说明：HTTPS 托管、SHA-1 生成、更新时 hash 必换、多服/多插件冲突说明
└─ assets/lleep/
   ├─ sounds.json                   （草案见 1.A）
   └─ sounds/ui/
      ├─ correct.ogg  incorrect.ogg  complete.ogg
      └─ combo_milestone.ogg  countdown_tick.ogg  timeout.ogg
```

配套代码：`ResourcePackManager`（下发 + `PlayerResourcePackStatusEvent` 状态跟踪 + quit 清理）+ SoundManager 第二条字符串自定义音通道（`Map<String,String> customCache` + `playSound(loc, String, vol, pitch)`）+ `custom-sounds.*` config 节（默认 `enabled: false`）。

---

## 4. 依赖与风险

1. **TTS 与音效并发**：现语音提示音 = NOTE_BLOCK_PLING@1.5，与倒计时 tick（HAT）、连击（LEVELUP 升调）、里程碑（BELL）属不同音源，Minecraft 客户端天然混音不打架；本方案刻意避免了"同一键同 tick 双播"（里程碑 tick 用铃音替代升调 correct）。唯一重叠窗口是"答题中狂点发音按钮"，现有 5s 冷却（`tts.pronounce-cooldown-seconds`）已兜底。
2. **性能（每秒 ActionBar 的压力评估）**：倒计时改造**不新增任何定时器**（沿用现有每玩家每秒 runTaskTimer），包量持平（1 SystemChat → 1 ActionBar，均 ~1 包/秒/玩家）；主线程 CPU 不变。诚实的收益口径：本插件规模下"主线程压力"本非瓶颈，改造的核心收益是**聊天栏不再刷屏**（30s 题 = 30 行 × 10 题/会话 ≈ 300 行）与阅读干扰。BossBar 新增成本 = 每房间 1 个对象 + 1 更新/秒/玩家，可忽略；代价是必须挂牢清理（矩阵见 3.4）。
3. **BossBar 可见性盲区**：玩家开着 GUI 时 BossBar 是否可见未经实测（ActionBar 在 GUI 打开时不渲染，但对决倒计时场景玩家通常正在打字=GUI 已关，风险低）；列为人工用例 D3，若不可见则降级为 ActionBar 方案（对决改走与学习同通道，成既定 fallback）。
4. **对决倒计时改造与任务容器配合**：`wordTimeoutTasks`（key=player1）类型不变；风险点：① 旧 `handleWordTimeout` 开头的 `remove` 与新自清理重复（幂等无害但要理顺）；② stale 判定必须用 `wordRoundId` 快照（换题即自清理，不能只查 isActive）；③ `cleanup` 的 cancel 与 tick 内自 cancel 并发窗口——同主线程串行，安全。改造触类集中在 DuelManager 单文件。
5. **per-player 开关的 DB 面**：`player_profiles` 加列需三处同步（schema.sql / addColumnIfMissing / load SELECT / save UPSERT，共 4 处），漏一处 = 静默失效；老库靠 addColumnIfMissing 平滑升级（既有模式，已在 player_data 验证过）。
6. **资源包路线（若 3B 上马）**：带宽/体验——包 ~200-400KB，客户端按 hash 缓存，百人日活量级为数十 MB/天（小服可忽略）；真实痛点不在带宽而在：join 弹窗骚扰、手机端 decline、与其它插件资源包的重复键覆盖（加载顺序不确定）、hash 管理错漏导致全服静默。故 3B 必须"默认关 + 文档完备 + 发布检查单"。
7. **Adventure 引入**：编译面经 paper-api 传递依赖即可，但调用点集中在 VersionAdapter，避免全库扩散；README 平台表述需同步"Paper-only"。

---

## 5. 验收标准（人工用例）

前置：`mvn clean package -DskipTests` BUILD SUCCESS；服务器实测（单机 + 双人各跑）。

- **A1 倒计时不再刷聊天栏**：开始一题 30s，聊天栏仅"题面 1 条 + 判分 1 条"；ActionBar 每秒递减可见；剩余 ≤5s 变红。
- **A2 紧急音效弧线**：剩 3/2/1s 每秒一声 tick 且音高可听出递增；到点一声 timeout（或 incorrect）音 + 聊天答案。
- **A3 题目切换同步**：答对后 ActionBar 停更（≤2s 淡出），下一题关 GUI 后重新出现；GUI 打开期间无 ActionBar 残影干扰。
- **B1 连击音高递增可听**：连对 2/3/4…题，correct 音逐级升高至封顶 1.6；答错后下一题音高复位 1.0。
- **B2 里程碑**：第 3/5/7/10 连击出现 ActionBar `⚡ N COMBO` + 铃音（且该 tick 无升调 correct 叠音）；聊天栏 combo-message 照旧。
- **C1 对决超时音效清晰**：开赛后双方可见 BossBar 倒计时条随时间排空；最后 5s 变红 + tick 音；无人作答到点：双方同时听到 timeout 音 + 聊天揭示答案，下一题条重置满格。
- **C2 对决清理无泄漏**：分别用 答完一局 / /le duel quit / GM 解散 / 一方掉线 30s 超时 四条路径结束对局 → 双方屏幕顶部无残留 BossBar；`/lea reload` 与重启后再对一局无异常。
- **D1 玩家开关**：设置 GUI 关闭音效后，菜单/答对/tick 音全静音但 ActionBar 计数仍在；退出重进保持；开启后恢复。服主把 `gui.sounds.enabled` 设 false 后全员全静音（现状回归）。
- **D2 配置热更新**：`/lea reload` 改 `feedback.countdown.urgent-seconds`/`tick-sound-seconds`/音效键（含 "none" 静音值）立即生效，无 WARNING 误报。
- **D3 BossBar 可见性**：对决中玩家开着 DuelGUI 时观察倒计时条可见性（决定是否启用 fallback，见风险 3）。
- **E1（仅 3B）**：`custom-sounds.enabled=true` + 有效包 → 自定义音生效；decline 包 → 原版音完整回退，无静默空洞。

---

## 6. 规划期发现（一律未修，由编排者归档排期）

1. **学习倒计时刷屏量化**（改造动机佐证）：30s 题 = 25 条 normal + 5 条 urgent = 30 行聊天；10 题会话 ≈ 300 行。`WordLearningManager.java` L294-332。
2. **urgent 阈值硬编码**：`remaining[0] <= 5`（`WordLearningManager.java` L320）无 config 键，本规划将提为 `feedback.countdown.urgent-seconds`（非缺陷，随 3A-1 处理）。
3. **对决每题超时全程零提示**：`DuelManager.scheduleWordTimeout` L415-424 单次 runTaskLater，30s 静默后突然判超时——与学习侧体验不对称（设计缺口，3A-3 治）。
4. **TTS .wav 与资源包 .ogg 格式不兼容**：`TtsManager.java` L27 注释自述"cached for potential future resource-pack integration"，但 VoiceRSS 下载（`f=44khz_16bit_stereo`，L94）为 WAV，Minecraft 资源包只支持 OGG Vorbis——动态 TTS 进包需服务端转码，**否决了 3B"顺带盘活 TTS 资产"的想象**，3B 必须明确排除动态 TTS。
5. **SoundManager 无法承载自定义命名空间音**：`resolveSound`（L87-103）走 `Sound.valueOf`，`lleep:ui.correct` 这类键永远解析失败；3B 需并开字符串缓存通道（enum 缓存只能放原版音）。
6. **`gui.custom-textures: false` 死配置**：`config.yml` L261 全库零读取（grep 唯一命中即定义行），与资源包主题相关——3B 决策时一并处理（接线为 pack 开关或删除）。
7. **README 平台表述与代码现实不符**：README.md L104 "Paper / Spigot 1.20+"，但 `VersionAdapter` 构造即调 Paper 专有 `Server.getMinecraftVersion()`（L26）、plugin.yml `api-version: "1.21"`——引入 Adventure 后应显式声明 Paper-only 并更新 README（文档校正项）。
8. **countdown 两行裸 `getString`**：`WordLearningManager.java` L322/L326（P2-13 名单内）——3A-1 重写这两行时顺带收敛 `MessageUtil.getGuiMsg`（不扩大白名单范围）。
9. **P2-24 音效盲点待收**：`ScenarioSelectListener` L116-121/L192（AI 未配置/无权限分支）+ `WrongBookListener.handlePractice`（Shift+点练习无点击音）——本领域顺手收口（3A-5），dev-findings 已在案。
10. **对决宽限双通道冗余**：`DuelManager.handleDisconnect` L608-621 同时写记分板 title（每秒）与条件聊天（≤5s 或 %10==0）——本期不动，记录为"反馈层统一"后续候选。（连带：BossBar 在 GUI 打开时的可见性未实测，见风险 3。）
11. **paper-api 弃用面核实（javap）**：`Player.sendActionBar(String)`/`sendActionBar(char,String)` @Deprecated → 必须走 Adventure Component；`BossBar.show()/hide()` @Deprecated → 用 addPlayer/removeAll；`Player.playSound(Location, String, float, float)` 与 `Bukkit.createBossBar` 未废弃——三条结论直接决定 3A/3B 的 API 选型。

---

## 7. 优先级拆分与规模（哪个先做）

**结论：反馈层（B）先做，资源包（A）后置为可选闸门。** 两个理由：资源包本身依赖原版编排层做回退（roadmap 原文即如此）；且 B 的收益/成本比碾压 A。

| 批次 | 内容 | story 数 | 规模 |
|------|------|---------|------|
| 3A-1（先） | 学习倒计时 ActionBar 化（sendActionBar 适配 + timer 改造 + feedback.countdown 键 + tick 音 + SoundManager 新键/none 语义） | 2 | S |
| 3A-2 | 连击升级（getComboPitch 纯函数+单测+接线；里程碑 ActionBar+音） | 2 | S |
| 3A-3 | 对决倒计时（timer 重构 + BossBar 创建/更新 + cleanup/shutdown/重连清理） | 3 | M |
| 3A-4 | per-player 音效开关（schema+迁移；设置 GUI；SoundManager 门） | 3 | M |
| 3A-5 | 音效盲点收口（P2-24 三处） | 1 | S |
| 3A 合计 | | **11** | **M** |
| 3B（闸门后） | 资源包骨架（ResourcePackManager / 自定义音通道 / 包目录+sounds.json+占位 ogg / 文档与检查单） | 4 | L |

执行顺序建议：3A-1 → 3A-2 → 3A-3 → 3A-4 → 3A-5 →（用户裁决）3B。3A-1/3A-2 与 3A-4 文件面不重叠可并行（编排者按 CLAUDE.md 多智能体调度扇出 2-3 路）；3A-3 单文件集中串行。纯函数（getComboPitch / CountdownTier.tierOf）按 Phase 0 约定配单测。跨切面（i18n 双文件 + MESSAGE_CONFIG_VERSION 11→12 + CHANGELOG + schema.sql）由编排者 fan-in 统一收口。
