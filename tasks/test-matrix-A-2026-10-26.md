# 方向 A 测试矩阵设计 · 2026-10-26

> 规划 Agent 输出 + 编排者逐条实测复核
> 目标：给刚完成的三项重构（R5 GUI holder / R7 dirty 体系）补上自动化回归护栏

## 0. 侦察结论（会改变实现方式，必读）

| # | 事实 | 证据 |
|---|---|---|
| F1 | 21 个 `GuiViewKey` 常量 ↔ 21 处 `PluginGuiHolder.of(GuiViewKey.X)`，当前精确双射 | 剥离注释后统计 |
| F2 | **裸 grep 数出 24 处 `createInventory`，不是 21** —— identity 三文件 Javadoc 各含 1 处字面量 | `grep -c` 在 `GuiIdentity`/`GuiViewKey`/`PluginGuiHolder` 各命中 1（编排者实测确认） |
| F3 | 21 处调用中 2 处同行内联（`DuelGUI:63`、`WordBankSelectGUI:44`），其余跨行 | 逐文件读取 |
| F4 | `Inventory`/`InventoryView`/`InventoryHolder`/`Player` 全是 interface，可直接 mock | javap |
| F5 | `resolve` 全链路 mock 可用 | 已验证 |
| F6 | **`new ItemStack(Material.X)` 在纯 JVM 必炸**：`ExceptionInInitializerError` ← `No RegistryAccess implementation found` | 已验证 |
| F7 | 但 `mockStatic(Bukkit)` + `mockConstruction(ItemStack)` 都能工作 → `GUI.open()` 端到端可测 | 已验证 |
| F8 | 事件层可测：mock `InventoryClickEvent`/`InventoryDragEvent` | 已验证 |
| F9 | **drag 测试必须 stub `getWhoClicked()`**，否则 `DuelGUIListener` 假阴性 | 已验证 |
| F10 | dirty 逻辑不碰真 DB 也能测：mock `DatabaseManager` + `Connection` + `PreparedStatement` | 已验证 |
| F11 | `PlayerStats` 是 `public static class`（:1504），`setDirtyHook` 包私有（:1525）→ 测试类放同包可直接调 | 编排者实测确认 |
| F12 | `GuiViewKey.size` 访问器已删（R5-12）→ **不该为它写测试** | 编排者实测确认 |
| F13 | `getPlayerStats()` 冷缓存走 `loadPlayerData()`，会调 `databaseManager.getDifficultWords()` → fixture 必须 stub 返回空 List | 已验证 |
| F14 | `incrementWrongCount`/`incrementTodayAiRound` 会调 `plugin.getServer().getScheduler()` → mock plugin 下 NPE。**只走 `recordReview`/`seedReview`/`resetReview`/`updateWordProgress`/`cachePlayerName`** | 读代码 |
| F15 | 现有 49 用例 / 8 类；首个类因 JVM+Mockito 预热占 13.65s | surefire 报告 |
| F16 | jacoco 行覆盖门 1%，绑 verify | pom.xml |

## 1. 测试文件规划

### 阶段 1（必做，零生产代码改动）

| 路径 | 职责 | 用例数 |
|---|---|---|
| `gui/identity/GuiIdentityTest.java` | `resolve`/`payloadOf`/`closeIfPluginGui` 的 holder 判定语义 | 13 |
| `gui/identity/PluginGuiHolderTest.java` | holder 值语义 + payload record 构造契约 | 12 |
| `gui/identity/GuiViewKeyCoverageTest.java` | 21 处调用点 ↔ 21 key 的源码级双射 + `markDirty` 顺序 lint | 6 |
| `data/PlayerDataStoreDirtyTest.java` | dirty 标记 / 增量保存 / 阈值兜底 / commit 窗口 | 16 |
| `data/PlayerStatsDirtyHookTest.java` | `PlayerStats` 变更 → 标脏（R7 核心不变量） | 6 |

### 阶段 2（需要 mockStatic + mockConstruction）

| 路径 | 职责 | 用例数 |
|---|---|---|
| `gui/GuiOpenIdentityTest.java` | 4 个最便宜的 GUI 的 `open()` 真的挂上了 key/payload | 8 |

### 明确不测
- `GuiViewKey` 枚举本身（零行为）
- `GUIUtils.isPluginGUI`（单行转发，在 GuiIdentityTest 顺手断言）
- 各 listener 的 `resolveOwnedView` 私有方法（方法体全是 `resolve + ==`）
- `GuiViewKey.size` 字段（F12）

## 2. 用例清单要点

### GuiIdentityTest（13）
最高优先级四条：
- `resolveReturnsNullForPlayerInventoryHolder` —— **R5 红线**：holder 是 `Player` 时必须返回 null，防止有人把 `instanceof` 改成 `!= null`
- `resolveReturnsNullForVanillaContainerHolder`
- `resolveReturnsKeyForPluginHolder`（存活探针）
- `resolveToleratesNullTopInventory`（唯一防线）
其余：null view / null holder / top vs clicked / `payloadOf` 返回 `NoPayload.INSTANCE`（引用相等）/ SquarePage 透传 / `closeIfPluginGui` 三态 / `closeIfPluginGuiIgnoresKeyValue`

### PluginGuiHolderTest（12，零 mock）
- `ofRejectsNullKey` / `ofWithNullPayloadFallsBackToNoPayload`
- `pageRejectsZero` / `pageRejectsNegative` / `pageAcceptsOne`
- `squarePageRejectsNullModeKey` / `squarePageRejectsEmptyModeKey` / `squarePageAcceptsAllDocumentedModeKeys`（5 个参数化）
- `targetRejectsNullUuid`
- `equalsAndHashCodeAreValueBased`
- **`holderEqualityDistinguishesSamePageDifferentKey`** —— R5 最核心：`SQUARE` vs `SQUARE_HIDDEN`
- `getInventoryReturnsNull` —— 写成「文档化违约」，引用 `PluginGuiHolder:22-30`

### GuiViewKeyCoverageTest（6，源码 lint）
实现铁律：
1. 从 `System.getProperty("user.dir")` 定位源码根，找不到 `pom.xml` 就 `fail()`，**不要 assumeTrue 跳过**
2. **必须先剥注释**再计数（否则 24 不是 21）
3. **不要用相邻行匹配**（2 处同行内联）

用例：
- `everyCreateInventoryCallCarriesAHolder`（逐文件两侧计数相等）—— **最高价值**
- `allHolderKeysAreDistinct`
- `allEnumConstantsAreUsed`
- `noUnknownKeysReferenced`
- `holderCallSiteCountMatchesEnumSize`
- `markDirtyAddsBeforeBumpingSequence`（源码顺序 lint：`dirtyPlayers.add` 下标 < `bumpWriteSeq` 下标；`markWordDirty` 同理）—— 标注为「结构断言，非行为断言」

### PlayerDataStoreDirtyTest（16）
共享 fixture 必须 stub：
- `plugin.getLogger()` → 真 Logger
- `databaseManager.getTablePrefix()` → `"lle_"`；`isSQLite()` → true；`getConnectionLock()` → `new Object()`；`getDifficultWords(any())` → `emptyList()`（F13）
- `Connection.prepareStatement(anyString())` → `PreparedStatement` mock
- `ps.executeBatch()` → `new int[0]`；`ps.executeUpdate()` → 1；`ps.executeQuery()` → `next()`=false
- **计数探针**：`doAnswer` 计 `addBatch()`（词行）与 `executeUpdate()`（player_data 行）

关键用例：
- `markDirtyIsSetIdempotentButAdvancesSequence`（反射读私有字段）
- `dirtyFlagsSurviveFailedCommit`
- **`dirtyFlagsSurviveWriteDuringSaveWindow`**（commit hook 内回灌写入）—— R7-C P1-2 本体
- **`twelfthActiveRoundFallsBackToFullSave`**（词行序列 `[1,1,...,1,2]`，第 12 轮=2）
- **`thirtySixthIdleRoundFallsBackToFullSave`**（前 35 轮 0，第 36 轮 2）
- `statsOnlyRoundWritesNoWordRows`
- `dirtyWordWithoutMasteryIsSkippedNotRewritten`
- `loadPlayerDataFlushesDirtyBeforeOverwriting`
- `unloadPlayerDataAlwaysFullSaves`
- `clearCacheStopsAllFurtherWrites`

### PlayerStatsDirtyHookTest（6，`data` 包）
- `allSixMutatorsInvokeHook`（6 个变更方法各触发 1 次）
- `gettersNeverInvokeHook`
- `nullHookIsSafe`
- `setDirtyHookCanBeReplaced`
- `getTotalWordsEqualsTotalCorrect`
- `externalPlayerStatsMutationMarksStoreDirty`（端到端：`getPlayerStats(id).addScore(42)` → `saveDirty` 写出 player_data 行）

### GuiOpenIdentityTest（8，阶段 2）
`try (MockedStatic<Bukkit>; MockedConstruction<ItemStack>)` 内运行。
`Bukkit.createInventory(any, anyInt, anyString)` → `thenAnswer` 捕获 holder/size 并返回 mock Inventory。
`getGuiSlot(any,any,anyInt)` 用 `thenAnswer(i -> i.getArgument(2))` 一次糊住全部槽位读取。
用例：WordBank / WrongBook clamp×3 / Profile target / Profile 双 key / Square payload / Square_HIDDEN

## 3. 可测试性改造建议

**阶段 1、2 全部不需要改生产代码。**
- 加 `@VisibleForTesting` 访问器：**建议不做**（本仓对「只为测试存在的方法」敏感，R5-12 前车之鉴）
- listener 私有方法提升：**建议不做**
- `markDirty` 提升为包私有：**可选**（反射样板超 20 行时）

**明确反对**：给 `PluginGuiHolder` 回填 `WeakReference<Inventory>`；把 `createInventory` 抽成可注入工厂。

## 4. 不能测清单

- `Bukkit.createInventory` 真的挂 holder（CraftBukkit 实现）
- `closeInventory()` 是否同步派发 `InventoryCloseEvent`
- ClassLoader 换代下 holder 失效（需 PlugMan 真重载）
- `ItemStack`/`ItemMeta`/PDC 渲染结果
- 槽位 → 行为映射（stub 量爆炸）
- SQL 方言正确性（需真库；sqlite-jdbc 非 pom 依赖）
- 并发交错（用 commit hook 确定性覆盖）
- `SoundManager` 播声
- `GuiViewKey.size` 与 GUI 常量一致性（不参与逻辑）

## 5. 工期与优先级

| 阶段 | 文件 | 用例 | 工时 |
|---|---|---|---|
| 1 | 5 | 53 | 4–5 h |
| 2 | 1 | 8 | 2–3 h |
| 合计 | 6 | 61 | 6–8 h |

**只能做一半的排序**（13 用例覆盖 80% 风险，约 2h）：
1. `GuiViewKeyCoverageTest` 的 1、2、3、6 条
2. `GuiIdentityTest` 的 2、3、1、5 条
3. `PlayerDataStoreDirtyTest` 的 9、10、11 条
4. `PlayerStatsDirtyHookTest` 的 1、6 条
5. `PluginGuiHolderTest` 全部

**施工顺序**：PluginGuiHolderTest → GuiIdentityTest → GuiViewKeyCoverageTest → PlayerStatsDirtyHookTest → PlayerDataStoreDirtyTest → 阶段 2

## 6. 三个坑

1. `GuiViewKeyCoverageTest` 不剥注释必假失败（24 vs 21）
2. `ItemStack` 不能直接 new（F6），必须 `MockedConstruction`
3. drag 测试要 stub `getWhoClicked()`（否则 DuelGUIListener 假阴性）
