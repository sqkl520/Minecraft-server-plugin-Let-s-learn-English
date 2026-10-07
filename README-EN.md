[简体中文](README.md) | English

> ⚠️ **This plugin is made by a junior high school student using AI. Feel free to submit Issues for any problems or suggestions. If you like this project, please star and follow!**

# Let's Learn English! —— Minecraft English Learning Plugin

A Minecraft server plugin designed for the unmotivated — it forces you to learn English while you play.

## Features

- **Word Learning & Answer System** —— GUI shows Chinese meaning → close GUI → type English spelling in chat with countdown timer
- **Combo Streak System** —— Consecutive correct answers earn increasing multipliers (1.0x → 3.0x), reset on wrong answer or timeout
- **Ebbinghaus Forgetting Curve** —— Smart spaced-repetition review scheduling
- **AI Scenario Chat** —— Intelligent English conversation via DeepSeek API, 6 scenarios supported
- **Wrong Book System** —— Auto-collect words answered wrong 3+ times, with independent practice and removal
- **Daily Word Challenge** —— Random daily words from the bank, earn rewards on completion
- **Daily Check-in System** —— Consecutive check-ins with increasing coin rewards, streak resets on gap
- **PvP Word Duel** —— Real-time 1v1 word quiz battle, first to 5 points wins, with disconnect protection
- **Leaderboard System** —— Coins / Words / Combo leaderboards, database-persisted
- **Learning Stats HTTP Dashboard** —— ECharts-based web admin panel with IP whitelist + Basic Auth
- **AI Chat History** —— Persistent conversation records, paginated history browser
- **Word Bank Import/Export** —— Admin YAML import/export with validation and deduplication
- **Word Pronunciation Helper** —— Phonetic display + pronunciation cue (VoiceRSS audio is downloaded and cached; client-side playback requires a resource pack)
- **Custom AI Scene Sharing** —— Base64-encoded custom scene sharing, instant hot-reload
- **Progress Tracking** —— Visual learning statistics, leaderboard, and achievement system
- **GUI Menu** —— Intuitive graphical interface with sound effects
- **Multi-language Support** —— Two-step confirmation to switch between Chinese and English UI, with customizable message prefix
- **Update Checker** —— Automatically checks GitHub Releases on startup, notifies OP players on join
- **Daily Study Reminders** —— Scheduled push notifications + welcome encouragement on player join (both configurable)
- **Database Auto-backup** —— Timed backups with the latest 7 copies retained
- **Admin Commands** —— Hot-reload config, reset player data, add/remove words from the bank

## Commands

### Player commands `/le` (aliases `/learn`, `/english`)

| Command | Description |
|---------|-------------|
| `/le menu` | Open main menu |
| `/le word [difficulty] [mode]` | Start word learning (difficulty: beginner / intermediate / advanced; mode: spelling / choice / fill_blank) |
| `/le review` | Review due words |
| `/le dialogue [scenario]` | Start AI scenario dialogue |
| `/le progress` | View your profile |
| `/le profile [player]` | View a player's profile |
| `/le stats` | View learning statistics |
| `/le square` | Enter the English Square |
| `/le leaderboard` | View leaderboards (coins / words / combo) |
| `/le challenge` | View today's challenge progress |
| `/le history` | Browse AI chat history |
| `/le duel <player>` | Challenge a player to a word duel (`/le duel accept` to accept, `/le duel quit` to quit) |
| `/le wrong` | Open the wrong-answer book |
| `/le checkin` | Daily check-in for coins |
| `/le save_scenario <name>` | Export the current AI scenario as a share code |
| `/le load_scenario <code>` | Import a shared scenario code |
| `/le help` | View help |

### Admin commands `/lea` (alias `/learnadmin`, permission `letslearnenglish.admin`)

| Command | Description |
|---------|-------------|
| `/lea reload` | Hot-reload all configs, word banks and sound cache |
| `/lea reset <player>` | Reset a player's learning data |
| `/lea info` | View plugin information |
| `/lea export` | Export player data |
| `/lea exportwordbank <difficulty>` | Export a word bank to YAML |
| `/lea importwordbank <file.yml>` | Import a word bank from YAML (place the file in `plugins/LetsLearnEnglish/exports/`) |
| `/lea broadcast <message>` | Broadcast to all servers via proxy (requires Velocity / BungeeCord) |
| `/lea ignore-update` | Dismiss the update notice |
| `/lea help` | View admin help |

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