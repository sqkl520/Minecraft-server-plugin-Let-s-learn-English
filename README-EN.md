[简体中文](README.md) | English

> ⚠️ **This plugin is made by a junior high school student using AI. Feel free to submit Issues for any problems or suggestions. If you like this project, please star and follow!**

# Let's Learn English! —— Minecraft English Learning Plugin

A Minecraft server plugin designed for the unmotivated — it forces you to learn English while you play.

## Features

- **Word Learning & Answer System** —— GUI shows Chinese meaning → close GUI → type English spelling in chat with countdown timer
- **Combo Streak System** —— Consecutive correct answers earn increasing multipliers (1.0x → 3.0x), reset on wrong answer or timeout
- **Ebbinghaus Forgetting Curve** —— Smart spaced-repetition review scheduling
- **Dialogue Practice** —— Simulated real-life conversations (restaurant, airport, shopping, etc.)
- **Progress Tracking** —— Visual learning statistics, leaderboard, and achievement system
- **GUI Menu** —— Intuitive graphical interface with sound effects
- **Multi-language Support** —— Two-step confirmation to switch between Chinese and English UI, with customizable message prefix
- **Update Checker** —— Automatically checks GitHub Releases on startup, notifies OP players on join
- **Daily Study Reminders** —— Scheduled push notifications + welcome encouragement on player join (both configurable)
- **Database Auto-backup** —— Timed backups with the latest 7 copies retained
- **Admin Commands** —— Hot-reload config, reset player data, add/remove words from the bank

## Commands

| Command | Description |
|---------|-------------|
| `/le menu` | Open main menu |
| `/le word` | Word learning |
| `/le dialogue` | Dialogue practice |
| `/le progress` | View learning progress |
| `/le review` | Start review |
| `/le help` | View help |
| `/lea reload` | Hot-reload configuration |
| `/lea reset <player>` | Reset player's learning data |
| `/lea addword <word/meaning/difficulty>` | Add a word |
| `/lea removeword <word>` | Remove a word |

## Installation

1. Download the latest JAR file
2. Place it in your server's `plugins/` directory
3. Restart the server or use `/reload confirm`
4. Edit `plugins/LetsLearnEnglish/config.yml` to configure
5. Use `/lea reload` to hot-reload without restarting

## Configuration

Key configuration options (`config.yml`):

```yaml
general:
  language: "en"          # Default language (zh/en)
  check-updates: true     # Check for updates on startup

prefix:
  zh: "&8[&b英语学习&8] &f"
  en: "&8[&bEnglish&8] &f"

word-system:
  words-per-session: 10   # Words per session
  answer-timeout: 30      # Answer timeout in seconds

daily-reminder:
  enabled: true
  on-join: true           # Send reminder on player join
  reminder-hours: [9, 14, 20]
```

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