# 词库重建管线（不随插件分发）

这里的脚本与数据用于**重建 `src/main/resources/words/*.yml`**，产出的词库会被打进
插件 JAR。本目录本身**不会**进 JAR（`pom.xml` 只打包 `src/main/resources`）。

## 数据来源与许可

| 文件 | 内容 | 许可 | 上游 |
|---|---|---|---|
| `ngsl_stats.csv` | NGSL 1.2 词频表（2809 词 + SFI 排名 + 每百万频次） | CC BY-SA 4.0 | newgeneralservicelist.com |
| `ngsl_lemma.csv` | NGSL 词元→词形映射（8509 条） | CC BY-SA 4.0 | 同上 |

另外两个数据源**体积太大没入库**，需要时自行下载：

- **ECDICT**（英汉释义/词性，MIT，66 MB）：`https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv`
- **Tatoeba 英文句库**（例句，CC BY 2.0 FR，24 MB）：`https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2`

脚本里的路径常量指向本机临时目录，换机器时要改。

## 管线顺序

```
1. ecdict.js       → 从 ECDICT 取 332 个缺失词的释义/词性  → ecdict-missing.json
2. ipa.js          → CMUdict(ARPAbet) → IPA                  → 写回 ecdict-missing.json
3. pos.js          → 从释义里抽词性、清洗中文                → 写回 ecdict-missing.json
4. tatoeba.js      → 从 204 万句里为每词挑候选例句            → 写回 ecdict-missing.json
5. rebuild.js      → 算三档重排方案（含"基础词保护"）         → rebuild-final.json
6. align.js        → 词性/义项/例句三元组对齐                 → aligned.json
7. manual-fix.js   → 人工订正（每条写明理由）                 → 写回 aligned.json
8. emit.js         → 生成最终三份 yml（从 **git HEAD** 读原始词库）
```

**第 8 步必须从 `git HEAD` 读源词库**，不能从工作区读——工作区里可能已经是上一次的
产物，再跑一遍会把 332 个新词当成老词重复追加（实测踩过，词数从 1357 变 1689）。

## 校验脚本

| 脚本 | 作用 |
|---|---|
| `coverage.js` | 算当前词库对 NGSL 的覆盖率（重建前 66.16% → 重建后 85.37%） |
| `verify.js` | 字段完整性/词性封闭集/例句含目标词/`freq_rank` 唯一性 |
| `example-quality.js` | 统计每条例句的"超纲词"数量 |
| `find-names.js` | 找含 Tatoeba 通用占位人名（Tom/Mary/Sami…）的例句 |
| `bstats.js` | 从 bStats 拉真实服务器的 MC 版本分布（用于决定支持范围） |
| `validate-ipa.js` | 用现有 1025 词的音标作金标准，量 IPA 生成准确率 |
| `check-dup.js` | 检查新增词与原有词库是否重复 |
| `show-churn.js` | 演示"严格按排名切档"会把哪些基础词降档 |

## 已知质量限制（重要）

- **音标**：新增 332 词的 IPA 由 CMUdict（**美式**）转换而来，而词库其余部分是**英式**。
  转换器对元音做了英式映射，但 `æ/ɑː` 这类英美分歧词（`class`/`dance`/`body`）仍保留美式读法；
  与原有 1025 词的金标准对比，忽略重音符号后一致率约 **58%–68%**。**未经逐词人工核验。**
- **例句**：来自 Tatoeba，已过滤长度与专有名词，但约 37% 的例句仍含 1–3 个超纲实词
  （如 `sumo`/`poverty`/`inherent`）。语义与词性已做对齐，**但未经逐句人工核验**。
- **释义**：332 个新词的中文释义取自 ECDICT 对应词性段的第一个义项；16 个多义词
  （`still`/`stay`/`film`/`service` 等）已人工订正，订正理由见 `manual-fix.js`。

这些都写进了 `THIRD-PARTY-NOTICES.md` 与 `src/main/resources/words/README.md`。
发现不妥可直接改 YAML 后 `/lea reload`。
