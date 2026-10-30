# 归档的插件 JAR

| 文件 | 说明 |
|---|---|
| `Let's Learn English!-1.0.7.jar` | **正式发行版，推荐使用**。含 shade 后的 MySQL 驱动（`com.mysql` 1111 个类），支持 SQLite 与 MySQL。 |
| `Let's Learn English!-1.0.7-SNAPSHOT.jar` | 正式版之前的最后一个快照（r2）。代码与 1.0.7 相同，仅版本号不同。 |
| `*-SNAPSHOT-noshade.jar` | 历史归档。**不含 MySQL 驱动** —— 只适用于纯 SQLite 服务器。在 MySQL 环境使用会抛 `No suitable driver`。 |

## 为什么有 `-noshade` 之分

`maven-shade-plugin` 是在 P0 批次（commit `0e5f96c`）才引入的。此前的构建只打包插件自身代码
（84K–672K），MySQL 驱动需服主自行放入服务端 `libraries/` —— 而本项目文档从未要求这么做，
所以那些包实际只能跑 SQLite。

1.0.7 起随包 shade 驱动（3.3M），服主无需额外操作。

## 取用建议

下载 **`Let's Learn English!-1.0.7.jar`** 即可。其余仅供回溯历史行为。
