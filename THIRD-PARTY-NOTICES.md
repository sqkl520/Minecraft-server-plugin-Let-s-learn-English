# 第三方组件声明（Third-Party Notices）

本插件以 GPL-3.0-only 发布（见仓库根 `LICENSE`）。以下第三方组件被**打包进**发布产物
`Let's Learn English!-<version>.jar`：

## MySQL Connector/J

- 组件：MySQL Connector/J（`com.mysql:mysql-connector-j`）
- 版本：8.3.0
- 用途：`database.type: mysql` 时的 JDBC 驱动（通过 `maven-shade-plugin` 打入 JAR，
  未做包名重定位，以保留 `META-INF/services/java.sql.Driver` 的服务发现）
- 许可证：GNU General Public License v2.0，附 **Universal FOSS Exception**
  （两种许可条款可在 <https://oss.oracle.com/licenses/universal-foss-exception/> 查阅）
- 上游：<https://dev.mysql.com/doc/connector-j/en/>

> 兼容性说明：Universal FOSS Exception 允许将本驱动与采用 OSI 认可许可证（含 GPLv3）
> 的开源项目组合分发。若本声明与上游许可文本存在出入，以上游原文为准。企业/闭源再分发
> 场景请自行完成法务确认。

## 构建期依赖（不进入产物）

以下依赖为 `provided` 作用域，仅在编译期使用，**不会**被打进 JAR：

| 组件 | 用途 | 许可证 |
|------|------|--------|
| Paper API (`io.papermc.paper:paper-api`) | Bukkit/Paper 服务端接口 | GPL-3.0（PaperMC） |
| VaultAPI | 可选经济系统桥接 | LGPL-3.0 |
| PlaceholderAPI | 可选占位符扩展 | GPL-3.0 |

## 内容资源

词库选词参考 NGSL / GSL / 中国课标词表，具体来源与许可声明见
`src/main/resources/words/README.md`（随插件释放到 `plugins/Let's Learn English!/words/README.md`）。
