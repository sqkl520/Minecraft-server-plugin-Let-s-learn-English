# Phase 3 体验层规划 · 总纲（plan-master）

- 日期： 2026-10-13
- 基线： commit 17b0c02（P2 清理批次已提交）+ 规划期即修 7 项实伤（未提交，见 CHANGELOG）
- 输入： 8 路只读规划 agent 产出的域计划（tasks/phase3/plan-*.md，合计约 4060 行）
- 性质： 合流总纲——优先级、批次排期、域间接口、待用户决策清单。各域细节一律以域计划文件为准。

---

## 一、八域一览

| 域 | 文件 | 规模 | Story | 核心方案一句话 | 新表/新列 |
|----|------|------|-------|---------------|-----------|
| 1 新手引导 | plan-onboarding.md | M | 4 | player_data.onboarding_step 四态 + 迷你会话（答错原地重试）+ WRITTEN_BOOK 引导书（PDC+三重补发） | player_data.onboarding_step |
| 2 AI 反馈 | plan-ai-feedback.md | M+ | 6 | 本地小结卡秒出 + 达标会话异步 AI 点评（JSON+salvage 降级）；前提=会话状态收敛 ChatSessionState | 新表 ai_chat_feedback |
| 3 成就重构 | plan-achievements.md | M+ | 5 | 6 个质量型新成就（精确判据）+ 刷量前置 + 墙分组 + 进度条；F1-03 并入修 | player_sessions.review_words、player_data.wrong_book_cleared |
| 4 广场社交 | plan-social.md | M | 5 | 动态流 + 鼓励 + 回应闭环；写全异步、广场零查库；隐私四层延伸 | 新表 social_feed、social_cheers |
| 5 赛季跨服 | plan-season.md | L | 3+2 | 批次 A：跨服榜+赛季（时间窗口视图零重置）；批次 B：班级（老师管理型） | 新表 seasons、season_rewards |
| 6 家长报告 | plan-reports.md | M | 4 | /lea report（新权限节点）+ TXT 强制中文 + 全异步零主线程查库 | 无（读现有表） |
| 7 音频反馈 | plan-audio-feedback.md | M+L | 11+4 | 3A 反馈层（倒计时 ActionBar、对决 BossBar、连击 pitch、per-player 开关）；3B 资源包（可选，默认关） | player_profiles.sounds_enabled |
| 8 菜单收口 | plan-menu-config.md | L | 8 | 主菜单 7→9；9 模块开关两层门；modes.* 接线；个人场景管理 GUI；死键大扫除 | 无 |

---

## 二、域间冻结接口（协作约定，施工时不可违反）

1. **主菜单 slot 19 预留给域 1**（新手引导入口按钮），域 8 主菜单改版不得占用。
2. **config-version 共同目标 12**：域 2 与域 5 都已规划 11→12 的新键——施工时合流为一次升级（谁先落地谁不动版本，最终统一 12）。
3. **同文件串行**：SocialListener（域 4×8）、ChatScenarioListener（域 2×8）、AchievementManager（域 3×8）、AdminCommand（域 6×8）、PlayerDataStore/DatabaseManager（域 3×4×5×6 多方）——涉及同文件的两域不得并行批次。
4. **域 4 → 域 3 单向接口**：`SocialManager.getCheerStats()`（helpful 成就依赖）；SocialManager 不反依赖成就域。
5. **域 8 → 域 2 依赖兑现**：`PersonalScenarioStore` 从 AIChatManager 抽取（域 8 的 MC-5 交付；域 2 的会话状态重构可复用该形态）。
6. **域 6 → 域 8 权限改造**：AdminCommand 顶部统一 admin 门改造（域 6 新增 letslearnenglish.report 依赖它）。
7. **ExportResult record 协议化**（域 8 的 MC-8 冻结签名，域 6 复用）。

---

## 三、建议施工顺序（4 波）

### 波 1：独立高价值槽（可 3-4 流并行，文件零重叠）

- **7-3A 音频反馈层**（倒计时 ActionBar / 对决 BossBar / 连击 pitch / per-player 开关）——纯体验提升、各 story 小（S/M）、玩家可感最强
- **1 新手引导**（域 1 全部 4 story）——新玩家留存，独立文件面
- **8-MC-2 模块开关**（9 个 enabled 接线，独占批）——奠定"开关语义"，后续所有域按规范加自己的门
- **3 成就重构**（域 3 全部 5 story）——独立性强（AchievementManager 本波独占）

### 波 2：依赖波 1 的社交与菜单主体

- **4 广场社交**（SocialListener 本波独占）
- **8-MC-1/3/5**（主菜单改版 / modes 接线 / 个人场景管理 GUI——个人场景抽取是域 2 前置）
- **6 家长报告**（AdminCommand 本波独占；读波 1 成就/签到数据更完整）

### 波 3：重工程与跨服

- **2 AI 反馈闭环**（ChatSessionState 重构是最大单体工程；依赖 8-MC-5 场景抽取）
- **5-批次A 跨服榜+赛季**（依赖 PF-SEA-01 跨服通道修复——已由编排者完成，待双服实测确认）
- **8-MC-4/6**（challenges-per-day/rewards 接线 / P2-13 二期裸 getString）

### 波 4：收尾与大扫除

- **5-批次B 班级**
- **8-MC-7 死键大扫除**（~28 个死 i18n 键 + ProgressGUI 死类 + achievements.yml 死链 + 死配置——必须是最后一波，避免删早了又被引用）
- **7-3B 资源包**（仅当用户拍板做）

---

## 四、待用户决策清单（有默认值，不回复按默认执行）

| # | 决策 | 默认 |
|---|------|------|
| D7 | 7-3B 资源包自定义音频做不做 | **不做**（3A 反馈层已覆盖 80-90% 听感收益；TTS .wav 与资源包 .ogg 冲突是硬约束） |
| D8 | 赛季奖励是否受 money.daily-cap 约束 | **不受**（一次性/排名门槛/台账幂等，与可重复收益语义不同） |
| D9 | modes.* 接线还是删键 | **接线**（7 子键逐一定义语义，默认值=现状行为，max-attempts 两键删除） |
| D10 | daily-round-limit 0/负数语义 | **不限**（禁用 AI 对话请走 ai-chat.enabled 开关） |
| D11 | 家长报告是否受玩家隐私开关约束 | **不受**（权限即同意边界；提供 report.respect-privacy=true 可收紧） |
| D12 | 成就"30 天"语义 | **累计** 30 个不同学习日（非连续） |
| D13 | 班级功能玩家自建 vs 老师管理 | **老师管理型**（玩家自建留给社交域后续） |
| D14 | 段位机制 | **不做**（无运营输入） |

---

## 五、规划期即修实伤（已由编排者本轮修复，含在待提交批次）

| # | 内容 | 提交状态 |
|---|------|---------|
| P3-01 | ProfileGUI 正确率恒 100%（分子分母同值） | 已修+verify |
| PF-ONB-01 | FIRST_WORD 要求"首场零失误"与描述不符 | 已修 |
| PF-ONB-03 | 词库选择路径标识符未本地化（F2-13 漏网） | 已修 |
| PF-SEA-01 | 跨服消息通道接收端缺帧长消费（通道大概率不可用） | 已修（双服实测待做） |
| PF-SEA-02/03 | MySQL 不支持 CREATE INDEX IF NOT EXISTS + player_sessions 缺索引 | 已修 |
| PF-MC-01 | 行为挑战领取绕过 money.enabled 门 | 已修 |
| PF-ACH-02 | bye 退出异步线程直调 Bukkit API | 已修+verify |

其余规划期发现（P3-02~20 除上表外）全部归档 tasks/dev-findings.md，按域在相应 story 施工时顺带收。

---

## 六、Phase 3 出口标准（合流后建议）

- 新玩家首轮完成率 ≥70%（引导线 3 步走通，人工抽测）
- 每域 story 全过三维 tester + mvn clean verify 全绿
- 配置面无"设了没用"的键（域 8 收口完成）
- 玩家入口面无"找不到的功能"（决斗/签到/个人场景管理全 GUI 可达）
- CHANGELOG 完整、config-version 收敛到统一版本号

---

## 七、施工进度回写（编排者维护）

| 波次 | 内容 | 状态 |
|------|------|------|
| 波 1 = Wave 1 | 域 7-3A 音频反馈层 / 域 1 新手引导 / 8-MC-2 模块开关 / 域 3 成就重构 | **已提交 `2d9037c`**（9 流；三维 GO/PASS/PASS；i18n 548=548） |
| 波 2 = Wave 2 | 域 4 广场社交 / 8-MC-1/3/5 / 域 6 家长报告 | **已提交 `13dc44a`**（5 流；三维 GO/PASS/PASS；i18n 638=638） |
| 波 3 = Wave 3A | 域 5 批次 A 赛季+跨服 / 8-MC-4 个人场景管理 / 8-MC-6 语义清二期 / 赛季展示 | **已提交 `2715ba5`**（4 流；三维 GO/PASS/PASS；i18n 668=668） |
| 波 3 = Wave 3B | 域 2 AI 反馈闭环 | **已提交 `f779964`**（三维 GO/PASS/GO；2中4低全收口；i18n 695=695、config-version 13） |
| 波 4 = Wave 4 | 域 5 批次 B 班级 / 8-MC-7 死键死类大扫除 | **已提交 `add4364`（4A 班级）+ `a05e692`（4B 死键）**（4A 三维 GO/PASS/GO；4B 三维 GO/GO/PASS；i18n 692=692） |
| 波 4 尾 | 7-3B 资源包 | **不做**（D7 默认，用户未拍板） |
| 收尾 | 验收 + 锐评 agent / 全部文档更新 | **全部完成**（三路锐评：旅程"完整体验层"+一致性 GO+诚实性 85/70；收口 `a6a0847`+`0755daf`；汇总 `tasks/phase3/final-review.md`；文档批全量） |

**规划期即修实伤**：7 项全部已随 Wave 1 提交（`2013b97`）。**D7-D14 决策**：全部按默认值执行，无用户异议。

