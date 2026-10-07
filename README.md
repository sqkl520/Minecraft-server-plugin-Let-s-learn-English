[English](README-EN.md) | 简体中文

> ⚠️ **这是一个初中生用AI做的插件，有任何问题建议欢迎提交Issues，如果您喜欢这个项目，就收藏关注一下吧！**

# Let's Learn English! —— Minecraft 英语学习插件

一个面向懒人的 Minecraft 服务器插件，让你在游戏中强制学习英语。

## 功能特性

- **单词学习答题系统** —— GUI 展示中文词义 → 关闭 GUI → 聊天栏倒计时拼写英文单词
- **回合制 Combo 连胜系统** —— 连续答对产生递增倍率（1.0x → 3.0x），答错/超时重置
- **基于艾宾浩斯遗忘曲线** —— 智能间隔复习机制，自动安排复习计划
- **AI 情景对话系统** —— 基于 DeepSeek API 的智能英语情景对话，支持 6 个场景
- **错题本系统** —— 答错 3 次自动收录，支持独立复习和移除
- **每日单词挑战** —— 每日随机从词库选词挑战，完成后获得奖励
- **每日签到系统** —— 连续签到递增金币奖励，断签重置
- **PvP 单词对决** —— 双人实时抢答对决，先得 5 分者胜，支持掉线重连
- **排行榜系统** —— 金币榜 / 单词榜 / 连击榜，数据库持久化
- **学习统计 HTTP 仪表盘** —— 基于 ECharts 的 Web 管理面板，IP 白名单 + Basic Auth 防护
- **AI 对话历史回顾** —— 持久化对话记录，分页浏览历史会话
- **词库导入导出** —— 管理员可导入/导出 YAML 词库，含校验和去重
- **单词发音辅助** —— 音标显示 + 发音提示音（VoiceRSS API 音频已下载缓存，客户端播放需资源包支持）
- **自定义 AI 场景分享** —— Base64 编码分享自定义场景，即时热加载
- **学习进度追踪** —— 可视化学习统计、排行榜与成就系统
- **图形化菜单** —— 直观的 GUI 操作界面，配合操作音效
- **多语言支持** —— 主菜单内二次确认切换中英文界面，可自定义消息前缀
- **版本更新检测** —— 启动时自动检查 GitHub Releases，OP 进服通知
- **每日学习提醒** —— 定时推送 + 玩家进服鼓励消息（均可配置）
- **数据库自动备份** —— 定时备份，保留最近 7 份
- **管理员命令** —— 热重载配置、重置玩家数据、添加/删除单词库

## 命令

### 玩家命令 `/le`（别名 `/learn`、`/english`）

| 命令 | 说明 |
|------|------|
| `/le menu` | 打开主菜单 |
| `/le word [难度] [模式]` | 开始单词学习（难度：beginner / intermediate / advanced；模式：spelling / choice / fill_blank） |
| `/le review` | 复习待复习单词 |
| `/le dialogue [场景]` | 开始 AI 情景对话 |
| `/le progress` | 查看个人主页 |
| `/le profile [玩家名]` | 查看个人资料 |
| `/le stats` | 查看学习统计 |
| `/le square` | 进入英语广场 |
| `/le leaderboard` | 查看排行榜（金币榜 / 单词榜 / 连击榜） |
| `/le challenge` | 查看今日挑战进度 |
| `/le history` | 查看 AI 对话历史 |
| `/le duel <玩家名>` | 发起单词对决（`/le duel accept` 接受，`/le duel quit` 退出） |
| `/le wrong` | 查看错题本 |
| `/le checkin` | 每日签到获取金币 |
| `/le save_scenario <名称>` | 导出当前 AI 场景为分享码 |
| `/le load_scenario <编码>` | 导入分享的场景码 |
| `/le help` | 查看帮助 |

### 管理员命令 `/lea`（别名 `/learnadmin`，权限 `letslearnenglish.admin`）

| 命令 | 说明 |
|------|------|
| `/lea reload` | 热重载所有配置、词库、音效缓存 |
| `/lea reset <玩家名>` | 重置玩家学习数据 |
| `/lea info` | 查看插件信息 |
| `/lea export` | 导出玩家数据 |
| `/lea exportwordbank <难度>` | 导出单词库至 YAML 文件 |
| `/lea importwordbank <文件名.yml>` | 从 YAML 文件导入单词库（文件需放在 `plugins/LetsLearnEnglish/exports/`） |
| `/lea broadcast <消息>` | 通过代理向所有子服广播（需 Velocity / BungeeCord） |
| `/lea ignore-update` | 忽略本次更新提醒 |
| `/lea help` | 查看管理帮助 |

## 安装

1. 下载最新版本的 JAR 文件
2. 放入服务器的 `plugins/` 目录
3. 重启服务器或使用 `/reload confirm`
4. 编辑 `plugins/LetsLearnEnglish/config.yml` 进行配置
5. 使用 `/lea reload` 热重载，无需重启

## 配置

主要配置项（`config.yml`）：

```yaml
general:
  language: "zh"          # 默认语言 (zh/en)
  check-updates: true     # 启动时检查更新

prefix:
  zh: "&8[&b英语学习&8] &f"
  en: "&8[&bEnglish&8] &f"

word-system:
  words-per-session: 10   # 每轮单词数
  answer-timeout: 30      # 答题超时秒数

daily-reminder:
  enabled: true
  on-join: true           # 进服时发送提醒
  reminder-hours: [9, 14, 20]
```

## 依赖

- Paper / Spigot 1.20+
- Java 17+

## 构建

```bash
mvn package -DskipTests
```

JAR 文件将生成在 `target/` 目录。

## 许可证

MIT License

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Let-s-learn-English-&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English-&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English-&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English-&type=date&legend=top-left" />
 </picture>
</a>