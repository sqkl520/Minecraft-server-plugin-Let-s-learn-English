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

### NGSL 1.2（New General Service List）

- 组件：NGSL 1.2 词频表与词形还原表（`NGSL_12_stats.csv`、`NGSL_12_lemmatized_for_teaching.csv`）
- 用途：为词库 1357 个词条标注 `freq_rank`（词频排名）与三档归属；**仅用于选词与排序**，
  未把 NGSL 词表本身作为数据分发给玩家
- 许可证：**CC BY-SA 4.0**（<https://creativecommons.org/licenses/by-sa/4.0/>）
- 作者：Browne, C., Culligan, B., & Phillips, J.
- 上游：<https://www.newgeneralservicelist.com/new-general-service-list>

> 本插件对 NGSL 的使用方式是「参考其排名来决定自己词库里教哪些词、按什么顺序教」，
> 并未复制或再分发 NGSL 词表本体。

### ECDICT（英汉词典数据）

- 组件：ECDICT 的英汉释义与词性标注（`ecdict.csv`）
- 用途：为新增的 332 个高频词提供中文释义与词性初值
- 许可证：**MIT**（Copyright 2025 Linwei）
- 上游：<https://github.com/skywind3000/ECDICT>

### CMU Pronouncing Dictionary

- 组件：CMUdict 发音词典（ARPAbet 音素序列）
- 用途：为新增的 332 个词生成 IPA 音标初值
- 许可证：**BSD 式开源许可**（Carnegie Mellon University）
- 上游：<http://www.speech.cs.cmu.edu/cgi-bin/cmudict>

> **已知偏差**：CMUdict 是**美式**发音，本插件的音标体系是**英式**。转换器对元音做了
> 英式映射（`OW→əʊ`、`AA→ɑː`、`ER→ə`），但 `æ/ɑː` 这类英美分歧词（如 `class`、
> `dance`）仍会保留美式读法。新增 332 词的音标**未经逐词人工核验**。

### Tatoeba（例句语料）

- 组件：Tatoeba 英文句库（`eng_sentences.tsv`，2026-10-31 导出，约 204 万句）
- 用途：为新增的 332 个词筛选例句
- 许可证：**CC BY 2.0 FR**（<https://creativecommons.org/licenses/by/2.0/fr/>），
  句子版权归各贡献者所有；Tatoeba 亦声明部分句子为 CC0
- 上游：<https://tatoeba.org/>

> 例句筛选方式：优先选「含目标词、5-11 词、不含专有名词」的陈述句，再用
> `docs/vocab/align.js` 做词性对齐。**例句未经逐句人工核验**，如发现不当可修改
> `words/*.yml` 后 `/lea reload`。
