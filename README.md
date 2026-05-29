[English](README-EN.md) | 简体中文

> ⚠️ **开发尚未完成，存在大量 BUG，请谨慎尝鲜！如果喜欢这个项目，就收藏关注一下吧！**

# Let's Learn English! —— Minecraft 英语学习插件

一个面向懒人的 Minecraft 服务器插件，让你在游戏中强制学习英语。

## 功能特性

- **单词学习系统** —— 基于艾宾浩斯遗忘曲线的智能复习机制
- **情景对话练习** —— 模拟真实场景（餐厅、机场、购物等）的英语对话
- **学习进度追踪** —— 可视化学习统计与成就系统
- **图形化菜单** —— 直观的 GUI 操作界面
- **多语言支持** —— 主菜单内二次确认切换中英文界面

## 命令

| 命令 | 说明 |
|------|------|
| `/le menu` | 打开主菜单 |
| `/le start` | 开始学习会话 |
| `/le word` | 单词学习 |
| `/le dialogue` | 情景对话 |
| `/le progress` | 查看学习进度 |
| `/le review` | 开始复习 |
| `/le help` | 查看帮助 |

## 安装

1. 下载最新版本的 JAR 文件
2. 放入服务器的 `plugins/` 目录
3. 重启服务器或使用 `/reload confirm`
4. 编辑 `plugins/Let's Learn English!/config.yml` 进行配置

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