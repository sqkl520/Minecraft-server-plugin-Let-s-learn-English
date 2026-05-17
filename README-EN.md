[简体中文](README.md) | English

> ⚠️ **This project is still under heavy development with many bugs — use at your own risk! If you like it, please star and follow!**

# Let's Learn English! —— Minecraft English Learning Plugin

A Minecraft server plugin designed for the unmotivated — it forces you to learn English while you play.

## Features

- **Word Learning System** —— Smart review mechanism based on the Ebbinghaus forgetting curve
- **Dialogue Practice** —— Simulated real-life conversations (restaurant, airport, shopping, etc.)
- **Progress Tracking** —— Visual learning statistics and achievement system
- **GUI Menu** —— Intuitive graphical interface
- **Multi-language Support** —— Two-step confirmation to switch between Chinese and English UI

## Commands

| Command | Description |
|---------|-------------|
| `/le menu` | Open main menu |
| `/le start` | Start learning session |
| `/le word` | Word learning |
| `/le dialogue` | Dialogue practice |
| `/le progress` | View learning progress |
| `/le review` | Start review |
| `/le help` | View help |

## Installation

1. Download the latest JAR file
2. Place it in your server's `plugins/` directory
3. Restart the server or use `/reload confirm`
4. Edit `plugins/Let's Learn English!/config.yml` to configure

## Requirements

- Paper / Spigot 1.20+
- Java 17+

## Build

```bash
mvn package -DskipTests
```

The JAR will be generated in the `target/` directory.

## License

MIT License

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Let-s-learn-English-&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English-&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English-&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English-&type=date&legend=top-left" />
 </picture>
</a>