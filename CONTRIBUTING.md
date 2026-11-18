[English](CONTRIBUTING-EN.md) | 简体中文

# 贡献指南

## 1. 先说清楚这是什么项目

这是一个**个人项目**：高一牲用 AI 辅助（vibe coding）写出来、在真服上一点点磨的
Minecraft 英语学习插件（Paper 系，Java 21，GPL-3.0-only）。

先说三件实话，免得你白等：

- **不承诺响应时效**。上课、考试、被 bug 卡住都可能导致几周不看 Issue。
- **不承诺「这个功能我要做」**。这里的方向由作者自己定，Issue 里说得很好的点子也可能不做。
- **但 PR 会被认真读**。带测试、说清动机的 PR，通过率比「只改一行」高得多。

如果你能接受这三点，往下看。

## 2. 报告问题

**先搜一遍 [Issues](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/issues)（含已关闭的）**——
「`/le` 没反应」「AI 不回复」这类问题很可能已经有人问过。

报 bug 请带上这五项，缺一项都可能让排查多绕几轮：

1. **服务端版本与构建号**（如 `Paper 1.21.11 build 132`、`Purpur 1.21.4 build 2321`）
2. **插件版本**（`/lea info` 第一行会打印，或看 jar 文件名；形如 `1.0.7`）
3. **你装的是哪个包**——本项目发布两个包，源码相同、编译目标不同：
   - MC 1.21.4 – 1.21.11 → `Let.s.Learn.English.-<版本>.jar`（需 Java 21+）
   - MC 26.1+ → `Let.s.Learn.English.-<版本>-mc26.jar`（需 Java 25+）

   在 26.x 上装普通包会报 `Unsupported class file major version`，反过来也一样。选包表见 README。
4. **复现步骤**——哪条命令、按什么顺序、多久出现
5. **相关日志**。最快的办法是游戏内执行：

   ```
   /lea diagnose save
   ```

   它会把配置、数据库、依赖、权限的自检结果落到 `plugins/LetsLearnEnglish/exports/diagnose-<时间戳>.txt`，
   并在聊天里回显路径，可直接粘贴。有异常的话也请贴完整堆栈。

如果是指标数值不对，请一并说明「你以为是多少、实际显示多少」——对照值比日志更有用。

**贴之前请删掉这几样**：`ai_config.yml` 的 `api-key`、`tts.voicerss-api-key`、
`proxy.inbound-secret`、`statistics-server.auth.password`，以及玩家名。

## 3. 提交 Pull Request

1. Fork 本仓库，从 `main` 新建分支（如 `fix/wrongbook-holder-guard`），改动聚焦——一个 PR 只做一件事。
2. 提交前确保本地构建通过（见下）。
3. 向 `main` 提交 PR，按 PR 模板填写描述与测试说明。
4. CI（`.github/workflows/build.yml`）会在 PR 上自动 `mvn verify` 并跑静态门禁；CI 不通过不会被合并。

## 4. 构建要求

需要 **JDK 21** 与 **Maven 3.9+**（本项目无 Maven Wrapper，请使用已安装的 `mvn`）。
在仓库根目录（`pom.xml` 所在处）执行：

```bash
mvn verify                     # 完整验证：编译 + 测试 + JaCoCo 覆盖率门禁（标准推荐）
mvn clean package              # 打包，产物在 target/
mvn clean package -DskipTests  # 跳过测试的快速打包，别用来验证改动
mvn test                       # 仅运行测试
```

**要出 26.x 那个包，还需 JDK 25**：

```bash
mvn clean package -Pmc26       # 产物 target/Let's Learn English!-<版本>-mc26.jar
```

- 目标平台为 **Paper 1.21.4+**；不保证 Spigot / Purpur / Folia 兼容（插件用了 Paper 专属
  API：Adventure `net.kyori` 组件、`Server#getMinecraftVersion`）。改动须与此事实一致。
- `mvn verify` 与 `mvn package` 不等价：`jacoco:check` 绑在 `verify` 阶段，
  用 `-DskipTests` 打包会**同时废掉覆盖率门禁**。
- 改完代码必须保证 `mvn clean package` 或 `mvn verify` 编译通过。
- 若报 `invalid target release: 21`，说明 `JAVA_HOME` 指向了更老的 JDK，显式设置：

  ```bash
  export JAVA_HOME=/path/to/jdk-21
  ```

- 新增/修改命令、功能或权限时，请同步更新 `README.md` 与 `README-EN.md`，并在
  `CHANGELOG.md` 的 `[Unreleased]` 段落下追加一行变更记录。

### 提交前的自检（重要）

仓库里有几道门禁，CI 会跑，本地也能跑，**改完请至少跑一遍**：

```bash
node scripts/audit-i18n-keys.js                          # 代码引用的 i18n 键必须在 zh.yml / en.yml 双侧存在
node scripts/gen-words.js check src/main/resources/words/beginner.yml      # 词库结构自检
node scripts/gen-words.js check src/main/resources/words/intermediate.yml
node scripts/gen-words.js check src/main/resources/words/advanced.yml
node scripts/check-word-collisions.js                    # 释义撞车扫描（建议级，暂不阻断）
```

> `gen-words.js check` 只接受**单个**文件参数，多传会被忽略，所以必须逐个循环。

**加测试比改代码更重要。** 这个项目的每一条修复都要求「有一条断言能在修复前变红」，
否则修了等于没修。只要你能证明测试真的能抓住那个 bug，PR 基本就过了。

这条规矩不是洁癖：本仓库有多条测试曾经「改前改后都绿」，靠变异验证才发现它们测的是
框架的桩而不是自己的逻辑。有几种假绿形态特别常见——自证桩、闸门未被触及、
异常类型冒充、半边覆盖、计数被别的路径满足。「我加了测试」不算凭证，
「我把这行代码改坏，这条测试会红」才算。

## 5. 什么会被合并

- **带测试的 bug 修复。**
- **符合本项目边界的功能**：服务端实现、玩家零客户端安装、不引入新的强制依赖。
- **翻译改进** —— 所有玩家可见文案在 `src/main/resources/messages/{zh,en}.yml`，
  两份文件的键集合必须完全一致（`audit-i18n-keys.js` 会检查）。
- **词库订正** —— 词库是纯 YAML。错误的释义、音标、例句都是真 bug，而且很容易修。
  改完用 `gen-words.js check` 自检。
- **文档改进** —— 包括中英两份 README 的同步。

## 6. 什么大概不会被合并

- **在没有事先 issue 讨论的情况下重写存储层、AI 集成、调度抽象。** 这几处有具体的正确性
  约束（单连接无池、主线程不得同步查库、异步回填需要 holder 守卫…），从外面改很容易踩坏。
- **引入新依赖。** 这个 JAR 刻意保持自包含——MySQL 驱动已经是 shade 进去的，每多一个
  传递依赖，就是某个服主的服务器起不来的一个理由。
- **只为某个私有服务端分支做的特化改动。**
- **绕过测试的「临时」修复。**

## 7. 提交信息

`type(scope): 改了什么、为什么`。请保留「为什么」——那是唯一能活过三个月的东西。

```
fix(wrongbook): ESC 关界面后异步回填会把界面弹回来

回填只有 isOnline + token 两道守卫，缺「玩家关掉界面但仍在线」这一道。
数据回来时 render 末尾的 openInventory 会把刚关掉的界面重新打开。
判据改用 PluginGuiHolder 引用相等，不比标题。
```

## 8. 行为期望

- 友善、就事论事；中文或英文交流均可。
- 提功能建议前，可先看看 README 的「它是什么」与本文第 6 节——有些事本插件有意不做。
- 维护者不承诺响应时限，也不保证合并任何 PR。
- 安全问题的处理方式见 [SECURITY.md](SECURITY.md)，**不要开公开 issue**。

## 9. 许可

本项目以 **GPL-3.0-only** 发布（见 [`LICENSE`](LICENSE)）。提交 PR 即表示你同意以相同条款
授权你的贡献。若你不代表自己（例如代表雇主）贡献，请确保已获得相应授权。

本项目**不使用 CLA**：你的贡献版权仍归你，只需按 GPL-3.0-only 授权即可。
