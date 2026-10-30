# 锐评 R12 · 内容供给管道

> 2026-10-20 战略锐评 · 教育内容生产负责人视角 · 只读 · agentId aa53736bd014f167c
> 编排者复核注记：抽测 17 个高频核心词（important/different/protect/build/achieve/experience/necessary/society/culture/science/future/remember/forget/travel/health/computer/phone）全库 0 命中——"高频词大面积缺失"**实锤**喵。字母分布实测 c:227/d:199 异常膨胀、j:3/k:4/q:3/u:1/v:3/z:1 枯竭，但 f:70/h:66/w:28/s:40 尚存——"e 之后拦腰砍断"表述略夸大，实况更像"按字母批量生成、c/d 段灌水、长尾字母与高频核心词双缺失"喵。结论方向成立。

## 1. 一句话判决

这个产品的词库不是"内容少"，而是**内容生成在字母表 e 附近被拦腰砍断、且没有任何质检与补尾管线**——995 词名不副实，实际是一份按字母序批量生成后中途夭折的半成品；死于没内容，且死得很难看喵。

## 2. 致命伤 Top 3

1. **词库是"字母序截断"的假词库**。字母分布暴露了生成器的死法：advanced.yml 329 词里 c 档 143 个、d 档 125 个，e 之后只剩零星，intermediate 同样在 i–l 后枯竭。所谓"从初中到四六级"是幻觉——初中课标约 1600 词、高考 3500、四级 4500，这 995 词连初中基础表的一半都不到，且高频核心词大面积缺失：实测 `important / different / protect / build / achieve / experience / necessary / society / culture / science / future / remember / forget / travel / health / computer / phone` **全部不在库中**（编排者已独立复核实锤）——这些词在任何 NGSL top-1000 里都该有，说明 README 宣称的"NGSL 高频选词依据"未兑现喵。
2. **i+1 是空的**。`difficulty_level`（1–9）在运行时零消费——`WordBank.java:158-175` 选题纯 `Collections.shuffle`，`ReviewScheduler.java:33` 用全局固定间隔 [1,6,24,72,168h]，不看难度、不看词频。CEFR 等级、词频排名、搭配、词形变形（复数/过去式/比较级）四类元数据全缺，自适应选题无从谈起喵。
3. **无内容纠错回路**。有代码 key 审计（`scripts/audit-i18n-keys.js`）和词库结构自检（`scripts/gen-words.js` check，README 记录"每档人工抽查 20 词"），但玩家答到一个错释义/错例句后**没有任何上报通道**，修错只能等项目方发新版（`config.yml:30` 只有版本更新检查，`feedback:` 段是音效反馈不是用户报错）喵。

## 3. 分条炮击（抽样证据）

**30 词逐条审读结论**：结构整齐（8 字段齐全、音标全有），例句大多自然（"Take a coat on rainy days." / "Courtesy costs nothing." 合格），但有硬伤与风格混乱喵：
- 释义/词性错配：beginner `clean` 给中文"干净的；打扫"（含动词义）但 `part_of_speech` 只写 adjective；advanced `casualty` 同义词给 `victim`——casualty 指伤亡（人数），不是受害者，这是会用出语错的错误搭配；`destitute`（赤贫的）同义词给 `poor`，语域跨了三个档喵。
- 超纲同义词漏网：低危批次清掉了 10 个越级同义词，但 beginner `car → synonyms: vehicle`、`cat → synonyms: kitten`（kitten 不是同义词是下义词）仍在喵。
- 音标风格精神分裂：README 承诺"Cambridge 英式"，实际 `/kɑːr/ /dɔːr/ /fɔːr/ /hɜːr/`（美音 rhotic）与 `/klɑːs/ /ˈɒl.tər/ /ˈdɒk.tər/`（英音元音+怪尾巴）混用——典型 AI 批量生成无风格规范的特征喵。
- 教学价值存疑：intermediate 放 `herald`（例句 "Swallows herald the spring." 文学腔），beginner 的 `afraid/afternoon/all` 与 advanced 的 `concede/confer/destitute` 之间没有词频梯度支撑，纯字母序邻居喵。

**场景**：`ai_scenarios.yml` 仅 6 个场景（机场/餐厅/购物/酒店/医院/自由聊）×3 难度=18 组合，且 `dialogues/` 目录是**空的**——不存在预制脚本对话，全部台词由 DeepSeek 现场生成。这意味着"对话质量"外包给了模型与 prompt（`AIChatManager.buildSystemPrompt`，prompt 里不含玩家词库进度、不喂目标词——场景对话与单词学习**两条内容线互不相通**）。难度控制只有三行形容词式 prompt，无中文注释、无文化点、无生词表喵。勤奋学生按 `config.yml`（daily-limit 50、challenges-per-day 3、word-count 5）估算：新词供给约 **20 个/天时 50 天见底，50 个/天时 20 天见底**，而三档内部实际可学的完整字母段撑不满 30 天。这是单词机，不是课程喵。

**管线溯源**：README 与 CHANGELOG 自陈内容来自 `scripts/gen-words.js` 内硬编码词表批量生成（AI 辅助编写+结构自检+抽查 20 词），无词典比对（音标没有对齐任何在线发音词典）、无例句可读性打分、无二次校对角色。错误内容修正=改 yml 重新打包发版喵。

**扩展性**：唯一亮点。`WordManagerImpl.loadAllWordBanks()` 运行时自动发现 `words/*.yml`，`DEPLOYMENT_WIKI.md:543-550` 写了自定义词库流程，`WordBankValidator` 做类型校验、单档 1000 词上限、坏档跳过不炸服（R-06）——外挂骨架是真的喵。但没有 schema 版本号、没有跨档/跨包查重（`globalWordIndex` 后加载者静默覆盖前者）、没有词库包命名空间、没有分发仓库。场景包只有个人级导入（H7），无社区全局扩展。社区共创理论上可行、实践中无人会做，因为**没有任何一个"内容作者工具链"**（编辑器、校验 CLI 使用文档、示例包）喵。

**本地化抽查**：en.yml 不是机翻灾难，质量中上（"Reward delivery failed, please try claiming again later. ({reward} coins are safe)" 地道），但 en.yml 有 7 处中文残留：`155-164 行` 排行榜整段 `"排行榜 / Leaderboard" "金币榜 / Coins" "暂无数据 / No data"` 直接中英混排塞给英文用户；zh.yml 里大量"喵~"（`word.invalid-difficulty` 等）对海外服务器是怪胎文案喵。

**能力维度**：拼写有雏形（三模式之一）；听力只有可选 TTS 发音按钮，无听写题；阅读只有挖单句（fill_blank）；写作仅靠 AI 对话小结点评；**语法完全空白**——0 个语法点、0 道改错题喵。

## 4. 说句公道话

字段 schema 设计（8 字段模板 + loader 宽容降级）、`gen-words.js check` 的存在、README 里那套"同义词越级清理方法论"和跨档去重记录，说明作者**知道正确管线长什么样**，只是执行深度只到"结构正确"，没到"内容可信"。995 词零重复、例句全部包含目标词、音标覆盖率 100%——比大多数同类插件手抄词表干净喵。

## 5. 只改一件事

**把"补全词库+建词频/CEFR 元数据"做成一条流水线**：拉取 NGSL 子词表 + 牛津 3000/5000 + 中高考词表做权威选词清单，按字母序分片让 AI 生成、每片强制 `node scripts/gen-words.js check` + 音标比对 Cambridge Dictionary 在线接口自动纠 rhotic 混排 + 给每个词补 `cefr` 与 `freq_rank` 字段并**让 WordBank 选题真正消费它**（按学生近期正确率在 freq_rank 邻域内取 i+1）。先填到 3000 词，再谈社区词库包——护城河只有在这条管道被证明能低成本产出可信内容之后才存在喵。
