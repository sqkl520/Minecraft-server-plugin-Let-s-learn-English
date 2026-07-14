# 架构报告 BUG-005-006
### 判定：PASS

## 审查范围
- `gui/LearningCalendarGUI.java`
- `data/PlayerDataStore.java`（`getRankedPlayers` / `getRankedPlayersForPeriod`）

---

## 维度 1 — 分层正确性

| 文件 | 实际包 | 期望包 | 结论 |
|------|--------|--------|------|
| `LearningCalendarGUI.java` | `gui/` | `gui/` (配置 GUI) | PASS |
| `PlayerDataStore.java` | `data/` | `data/` (数据层) | PASS |

`LearningCalendarGUI` 归属 `gui/` 包，符合「配置 GUI → `config/menu/` 或 `gui/`」分层约定。
`getRankedPlayers` / `getRankedPlayersForPeriod` 均在 `data/` 层执行纯 SQL 查询 + 结果组装，
不含业务决策逻辑，符合数据层职责。

---

## 维度 2 — 单一职责

| 文件 | 行数 | 职责说明 | 结论 |
|------|------|----------|------|
| `LearningCalendarGUI.java` | 214 | 仅负责构建日历 Inventory 并打开给玩家，无事件处理 | PASS |
| `PlayerDataStore.java` | 834 | 多方法大类（现存文件），新增方法 `getRankedPlayers` / `getRankedPlayersForPeriod` 职责单一 | PASS（警告见下） |

> 警告：`PlayerDataStore.java` 整体 834 行，超出 300 行警告线，属已知存量问题，不因本次 STORY 新增。

---

## 维度 3 — DI 注入完整性

### LearningCalendarGUI
- 构造函数 `LearningCalendarGUI(LetsLearnEnglish plugin)` — 通过构造注入 ✓
- 使用 `plugin.getPlayerLanguage(player)` / `plugin.getConfigManager()` / `plugin.getPlayerDataStore()` — 均通过注入的 plugin 访问，无静态获取 ✓
- `LearningCalendarGUI` 是纯 GUI 辅助类，不是 Listener，无需在 `onEnable()` 注册 ✓

### PlayerDataStore（新增方法）
- 依赖通过已有构造函数注入（`plugin`、`databaseManager`），新增方法无新依赖 ✓
- 返回类型 `public List<LeaderboardEntry>` 保持不变 ✓

---

## 维度 4 — 事件注册

| 检查项 | 结论 |
|--------|------|
| `LearningCalendarGUI` 不是 Listener，无需注册 | PASS |
| GUI 点击由 `MiscGUIListener`（已在 `onEnable()` 注册）处理日历槽位 | PASS |
| `LearningCalendarGUI` 由 `MainMenuListener` 按需实例化调用 `open()` | PASS |
| `getRankedPlayers` / `getRankedPlayersForPeriod` 由 `Leaderboard.java`（`core/` 层）调用，链路正确 | PASS |

---

## 小结

四维度零违规。`LearningCalendarGUI` 分层、注入、方法签名均合规；
`PlayerDataStore` 新增方法保持数据层纯净，返回类型与接口约定一致。
