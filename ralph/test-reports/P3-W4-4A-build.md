# 编译测试报告 P3-W4-4A（班级系统 US-CLS-1+2）

### 判定：PASS

结论先行：**GO**

## 逐项结果

| # | 检查项 | 结果 | 证据 |
|---|--------|------|------|
| 1 | `mvn clean verify` | PASS | BUILD SUCCESS，43.1s，89 主源 + 6 测试源编译通过，39 tests / 0 failures / 0 errors |
| 2 | 编译警告 | PASS | `[WARNING]` 计数 = 0（仅 3 条 JDK 动态 agent 加载提示，非代码警告；无新增 warning） |
| 3 | DDL 一致性 | PASS | schema.sql vs DatabaseManager.createTables 逐列逐字一致（见下） |
| 4 | 键对称 | PASS | SnakeYAML 真解析 `zh=716 en=716 onlyZh=[] onlyEn=[]`；audit 脚本仅剩已知误报 `achievement-wall.category-` |
| 5 | config 键匹配 | PASS | class-system.enabled/max-name-length/max-members 三键读取点与 config.yml 一致 |
| 6 | MESSAGE_CONFIG_VERSION | PASS | 三处均 14（ConfigManager:25 / zh.yml:6 / en.yml:6） |
| 7 | GUIUtils 白名单 | PASS | 两子串「班级榜」「Class Board」与 class.board-title 双语前缀一致 |

## DDL 一致性详情

**classes**（schema.sql:228-234 ↔ DatabaseManager.java:369-377）逐列一致：
```
class_id     INTEGER PRIMARY KEY <AUTOINCREMENT|AUTO_INCREMENT>
class_name   VARCHAR(32) NOT NULL UNIQUE
display_name VARCHAR(64)
created_by   VARCHAR(36) NOT NULL
created_at   BIGINT NOT NULL
```

**class_members**（schema.sql:236-241 ↔ DatabaseManager.java:378-385）逐列一致：
```
class_id   INT NOT NULL
uuid       VARCHAR(36) NOT NULL
joined_at  BIGINT NOT NULL
PRIMARY KEY (class_id, uuid)
```

AUTOINCREMENT 双后端分支正确：`isSQLite() ? "AUTOINCREMENT" : "AUTO_INCREMENT"`（SQLite 用 `INTEGER PRIMARY KEY AUTOINCREMENT`，MySQL 用 `AUTO_INCREMENT`）。

索引一致：两处均建 `idx_class_members_uuid ON class_members(uuid)`，SQLite 走原生 `CREATE INDEX IF NOT EXISTS`，MySQL 走 `createIndexIfMissing` 的 information_schema 查重分支（DatabaseManager.java:386-388 ↔ schema.sql:244）。

## 键对称与占位符详情

- 叶子键：zh=716 / en=716（SnakeYAML 递归真解析，与原 13 版基线 716 相符；node 手写解析器因不剥离注释内冒号多计 1 列，以 SnakeYAML 为准）
- `node scripts/audit-i18n-keys.js`：referenced dotted keys 528，唯一 MISSING 为 `achievement-wall.category-`（已知误报，动态前缀键）
- class.* 共 18 键，zh/en 双侧全部存在且键名一一对应
- help.class 存在 zh.yml:274 / en.yml:274

占位符与调用点匹配（AdminCommandHandler）：
| 键 | 占位符 | 调用点 |
|----|--------|--------|
| class.created | {class} | :651 |
| class.deleted | {class} | :668 |
| class.member-added | {player}{class} | :724, :745 |
| class.reassigned | {player}{class} | :742 |
| class.member-removed | {player} | :774 |
| class.member-not-in | {player} | :776 |
| class.member-full | {max} | :738 |
| class.not-found | {name} | :670, :692, :762, :800 |
| class.name-invalid | {max} | :641-642 |
| class.list-header | {class}{count} | :826-827 |
| class.list-entry | {rank}{player}{score} | :830-833 |
| class.player-not-found / class.usage / class.disabled / class.empty / class.not-in-class / class.name-exists | 无占位符 | 一致 |

## config 键匹配详情

- `class-system.enabled` (config.yml:754, 默认 false) → AdminCommandHandler.java:614、LearnCommand.java:764
- `class-system.max-name-length` (config.yml:756, 默认 32) → AdminCommandHandler.java:639
- `class-system.max-members` (config.yml:758, 默认 50) → AdminCommandHandler.java:687
- 三处 getBoolean/getInt 的硬编码默认值与 config.yml 注释值一致

## GUIUtils 白名单详情

GUIUtils.java:114-115 新增 `title.contains("班级榜")` / `title.contains("Class Board")`，与 class.board-title 的 zh `&6&l班级榜 - {class}` / en `&6&lClass Board - {class}` 前缀吻合。

## 问题清单

无阻断/高风险问题。

| # | 严重度 | 位置 | 说明 |
|---|--------|------|------|
| 1 | INFO | ConfigManager.java:33-50 | `class-system.*` 三键未加入 REQUIRED_CONFIG_KEYS 诊断名单。非缺陷：该名单仅启动审计告警，且 season.enabled/money.enabled 先例同样未列入；代码回落默认值行为正确。 |

## 不确定项

- 是否应将 class-system 模块门键纳入 REQUIRED_CONFIG_KEYS 启动诊断名单，属项目一致性口径问题，非构建维度缺陷，留待架构测试判定。
- 4B 若需玩家侧班级榜入口/自建班，当前仅 `/lea class` 只读查看 + `/lea class` 管理族，range 符合 4A 描述。

## 构建命令与产物

```
export JAVA_HOME="D:\Java\zulu21.48.17-ca-jdk21.0.10-win_x64"
"D:\maven\apache-maven-3.9.9\bin\mvn.cmd" clean verify
```
产物：`target\Let's Learn English!-1.0.7-SNAPSHOT.jar`（683568 bytes，未复制到 versions/，符合手动发布纪律）
