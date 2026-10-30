# 归档的插件 JAR

| 文件 | 说明 |
|---|---|
| `Let's Learn English!-1.0.7-SNAPSHOT.jar` | **当前版本，唯一可直接使用**。含 shade 后的 MySQL 驱动（`com.mysql` 1111 个类），支持 SQLite 与 MySQL 两种后端。 |
| `*-noshade.jar` | 历史归档。**不含 MySQL 驱动** —— 只适用于纯 SQLite 服务器。若在 MySQL 环境使用会在运行时抛 `No suitable driver`。 |

## 为什么有此区分

`maven-shade-plugin` 是在 P0 批次（commit `0e5f96c`）才引入的。此前构建的 JAR 只打包插件自身代码
（84K–672K），MySQL 驱动需服主自行放入服务端 `libraries/` 目录 —— 而本项目文档从未要求这么做，
所以那些包实际只能跑 SQLite。

1.0.7 起改为随包 shade 驱动（3.2M），服主无需额外操作。

## 取用建议

下载 `Let's Learn English!-1.0.7-SNAPSHOT.jar` 即可。`-noshade` 系列仅供回溯历史行为，不建议使用。
