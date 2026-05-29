[English](README-EN.md) | 简体中文

> ⚠️ **这是一个初中生用AI做的插件，有任何问题建议欢迎提交Issues，如果您喜欢这个项目，就收藏关注一下吧！**

# Let's Learn English! —— Minecraft 英语学习插件

一个面向懒人的 Minecraft 服务器插件，让你在游戏中强制学习英语。

## 功能特性

- **单词学习答题系统** —— GUI 展示中文词义 → 关闭 GUI → 聊天栏倒计时拼写英文单词
- **回合制 Combo 连胜系统** —— 连续答对产生递增倍率（1.0x → 3.0x），答错/超时重置
- **基于艾宾浩斯遗忘曲线** —— 智能间隔复习机制，自动安排复习计划
- **情景对话练习** —— 模拟真实场景（餐厅、机场、购物等）的英语对话
- **学习进度追踪** —— 可视化学习统计、排行榜与成就系统
- **图形化菜单** —— 直观的 GUI 操作界面，配合操作音效
- **多语言支持** —— 主菜单内二次确认切换中英文界面，可自定义消息前缀
- **版本更新检测** —— 启动时自动检查 GitHub Releases，OP 进服通知
- **每日学习提醒** —— 定时推送 + 玩家进服鼓励消息（均可配置）
- **数据库自动备份** —— 定时备份，保留最近 7 份
- **管理员命令** —— 热重载配置、重置玩家数据、添加/删除单词库

## 命令

| 命令 | 说明 |
|------|------|
| `/le menu` | 打开主菜单 |
| `/le word` | 单词学习 |
| `/le dialogue` | 情景对话 |
| `/le progress` | 查看学习进度 |
| `/le review` | 开始复习 |
| `/le help` | 查看帮助 |
| `/lea reload` | 热重载配置文件 |
| `/lea reset <玩家>` | 重置玩家学习数据 |
| `/lea addword <单词/中文释义/难度>` | 添加单词 |
| `/lea removeword <单词>` | 删除单词 |

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