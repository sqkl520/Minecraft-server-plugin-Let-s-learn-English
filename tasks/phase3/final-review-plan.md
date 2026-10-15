# Phase 3 最终验收 + 锐评 agent 派发计划（编排者预写，2026-10-16）

> 使用说明：Wave 4 提交后派发。用户原话："施工完成后需要再次派出一些agent 验收施工并锐评施工效果"。
> 与施工期 tester 的差别：**视角是"整体成品"而非"单批代码"**——跨波次一致性、玩家旅程贯通性、遗留账目诚实性。

---

## 锐评 1：玩家旅程贯穿验收（general-purpose，浏览器不可用→读代码模拟）

**任务**：以"一个新手玩家从安装到深玩"的完整旅程视角，验收 Phase 3 全部功能链。

旅程清单（每一步走真实代码路径，确认衔接无缝）：
1. 首次进服 → 欢迎语（join.welcome-1/2/3）→ 引导手册书发放 → 迷你会话（3 题全对）→ 首成就 → step3 → 引导完成
2. 第二次进服 → join 每日提醒（无 welcome 重复）→ /le word 学习 → 倒计时 ActionBar → 连击音效 → 会话结算
3. AI 对话 → /le dialogue restaurant → 与 AI 对话 → bye → 小结卡 → AI 点评 → /le history 回看反馈物品
4. 广场 → /le square → 动态流 → 鼓励他人 → 被鼓励通知 → helpful 成就进度
5. 榜单 → /le leaderboard → 4 tab（含赛季）→ 点头像看主页
6. 赛季（开 enabled）→ /le season → 赛季信息 → PAPI 占位符
7. 班级（开 class-system）→ /le class → 班级榜
8. 家长报告 → /lea report <玩家> week → TXT 导出
9. 决斗 → 主菜单按钮 → DuelChallengeGUI → 对决 → 成就
10. 签到 → 主菜单按钮 → 签到 → 金币
11. 模块开关：逐一关闭 9 个模块 → 验证"入口隐藏 + 事件静默 + 数据永不删 + 明确提示"

**输出**：每步 verdict（贯通/断裂）+ 断裂点文件:行号 + 玩家实际会看到什么。最后总评：**Phase 3 可否称为"完整体验层"**+ 三个最伤旅程的问题。

## 锐评 2：跨波次一致性审计（general-purpose）

**任务**：审计 4 个 Wave 间的接口一致性（单批 tester 看不到的跨批视角）。

审计项：
1. **i18n 键全量最终审计**：audit 脚本 + SnakeYAML 真解析 + 逐键占位符（重点：Wave 2/3 新增键的占位符与调用点是否全对齐——Wave 2 曾有 16 处不匹配）
2. **config.yml 全键审计**：每个键是否有读取点（无→死键清单）；每个读取点是否有键（有→缺键清单）；默认值双侧一致
3. **PAPI 占位符**：%lle_* 全清单 vs LLEPapiExpansion 实现全比对；gating 语义（关模块时返回空串）逐项
4. **DB schema 全审计**：schema.sql vs DatabaseManager 内嵌 DDL 全表逐字比对（不只新表）；deletePlayerData 全表覆盖（无遗漏表）
5. **线程纪律抽查**：随机抽 10 个 GUI 打开路径，验证"0 数据库 GUI 打开"纪律
6. **跨服协议**：ProxyMessenger 子通道全清单 vs 发送/接收处理（esp. 赛季/成就/广播）
7. **Wave 1 冻结接口兑现**：tracker "Wave 1 冻结接口一览"逐项 grep 消费点（无消费点的接口=遗留账）

**输出**：一致性 verdict + 不一致清单（文件:行号 + 证据）+ "配置面无设了没用的键"出口标准判定。

## 锐评 3：遗留账目诚实性 + 代码质量锐评（general-purpose，风格：毒舌但公正）

**任务**：审查这个项目"自称完成"与"实际状态"的差距，并给出诚实锐评。

1. **dev-findings 全量复核**：93 条待排期逐一抽检状态是否真实（抽查 20 条实读代码）；列出"仍真实的未关闭项"
2. **TODO/FIXME 扫描**：全仓库 TODO/FIXME/XXX/HACK 注释清点；每条判定（合理留账/应修未修/已过时）
3. **AIChatManager 1500 行类**：读全文，评价拆分候选（ChatSessionState 已收但类仍肥）；给出具体拆分建议（不做，只评）
4. **测试覆盖锐评**：39 个测试 vs 144 类——覆盖率报告（jacoco）实际数字；哪些新功能零测试（诚实列出）；哪些测试是"装饰性"的
5. **CHANGELOG 诚实性**：抽 10 条 CHANGELOG 声明 vs 代码实际（esp. "真接线""零 X""全量"类声明）
6. **文档一致性**：README/DEPLOYMENT_WIKI 抽 10 处 vs 代码

**输出**：遗留账册（真实未关闭项全表）+ 三个"最被高估的完成声明" + 三个"最被低估的工程质量" + 总锐评（300 字内，中文，可以毒但要有据）。

---

## 汇总与收尾

三路锐评回来后：
1. 编排者汇总 -> `tasks/phase3/final-review.md`（验收报告）
2. 依报告修最后一批（若发现实伤，再走一轮 fan-in 收口）
3. 文档最终更新（README/DEPLOYMENT_WIKI 已并行完成；tasks/ 收口；CHANGELOG 收尾条目）
4. 最终 `mvn clean verify` + 提交 + 报告用户
