[简体中文](README.md) | English

> ⚠️ **This plugin is made by a junior high school student using AI. Feel free to submit Issues for any problems or suggestions. If you like this project, please star and follow!**

# Let's Learn English! —— Minecraft English Learning Plugin

A Minecraft server plugin designed for the unmotivated — it forces you to learn English while you play.

## Features

- **Word Learning & Answer System** —— GUI shows Chinese meaning → close GUI → type English spelling in chat with countdown timer
- **Combo Streak System** —— Consecutive correct answers earn increasing multipliers (1.0x → 3.0x), reset on wrong answer or timeout
- **Ebbinghaus Forgetting Curve** —— Smart spaced-repetition review scheduling
- **Starter Guide Line** —— A "Starter Guide Book" on first join, three steps to get started: pick a word bank → 3-word mini session (multiple choice, retry on the spot when wrong) → open your profile; `/le guide` shows progress or hands the book back anytime, veteran players skip it automatically
- **AI Scenario Chat** —— Intelligent English conversation via DeepSeek API, 6 scenarios supported
- **AI Session Summary & Feedback** —— Each conversation ends with a local stats summary (messages / duration); qualifying sessions get an async AI review with an overall comment, grammar corrections and recommended words; view the full feedback on the `/le history` detail page
- **Wrong Book System** —— Auto-collect words answered wrong 3+ times, with independent practice and removal
- **Daily Word Challenge** —— Random daily words from the bank, earn rewards on completion
- **Daily Check-in System** —— Consecutive check-ins with increasing coin rewards, streak resets on gap
- **PvP Word Duel** —— Real-time 1v1 word quiz battle, first to 5 points wins, with disconnect protection
- **Leaderboard System** —— Coins / Words / Combo / Season (when seasons are enabled) leaderboards, database-persisted
- **Season System (disabled by default)** —— Fixed-cycle seasons (duration and first-season start date configurable): a 4th leaderboard tab, the `/le season` chat overview, and `%lle_season%` placeholders; when a season ends, coin rewards are settled by rank (top1 / top3 / top10 / participation), with settlement broadcasts and offline reward delivery on join
- **Class System (disabled by default)** —— Admin-managed classes: admins/teachers create classes and add members with `/lea class` (one class per player); players use `/le class` to view their class and class board (member study scores within the current season window, respecting members' privacy settings)
- **Learning Stats HTTP Dashboard** —— ECharts-based web admin panel with IP whitelist + Basic Auth
- **AI Chat History** —— Persistent conversation records, paginated history browser
- **Word Bank Import/Export** —— Admin YAML import/export with validation and deduplication
- **Word Pronunciation Helper** —— Phonetic display + pronunciation cue (VoiceRSS audio is downloaded and cached; client-side playback requires a resource pack)
- **Custom AI Scene Sharing** —— Base64-encoded custom scene sharing, imported as personal scenarios
- **Personal Scenario Manager GUI** —— A manage entry on the scenario select page to view/delete personal scenarios (in-line click-twice confirm, limit 30 per player)
- **English Square Activity Feed** —— The square gains a "Feed" mode: sessions / achievements / check-ins generate activity posts automatically (same-type posts merged per day, max 3 posts per player per day); left-click to cheer, right-click for profile — cheered players are notified instantly when online or via a join summary when offline
- **Progress Tracking** —— Visual learning statistics, leaderboard, and achievement system
- **GUI Menu** —— Intuitive graphical interface with sound effects
- **9-Button Main Menu** —— The main menu now offers 9 entries (new: Word Duel and Daily Check-in); slots/materials are configurable and buttons hide automatically when their module is disabled
- **Multi-language Support** —— Two-step confirmation to switch between Chinese and English UI, with customizable message prefix
- **Update Checker** —— Automatically checks GitHub Releases on startup, notifies OP players on join
- **Daily Study Reminders** —— Scheduled push notifications + welcome encouragement on player join (both configurable)
- **Database Auto-backup** —— Timed backups with the latest 7 copies retained
- **Parent Report** —— `/lea report` generates plain-text learning reports (period overview / daily breakdown / weak words / cumulative results / notes for parents), with per-player and whole-server batch comparison table, gated by the dedicated `letslearnenglish.report` permission
- **Module Switches (9)** —— word-system / ai-chat / duel / checkin / daily-challenges / leaderboard / achievements / rewards plus the progress-system umbrella switch; all follow the "hide entries + silence events + never delete data" semantics and take effect immediately on `/lea reload`
- **Admin Commands** —— Hot-reload config, reset player data, add/remove words from the bank

## Commands

### Player commands `/le` (aliases `/learn`, `/english`)

| Command | Description |
|---------|-------------|
| `/le menu` | Open main menu |
| `/le guide` | View starter guide progress / get the guide book |
| `/le word [difficulty] [mode]` | Start word learning (difficulty: beginner / intermediate / advanced; mode: choice (default) / spelling / fill_blank) |
| `/le review` | Review due words |
| `/le dialogue [scenario]` | Start AI scenario dialogue |
| `/le progress` | View your profile |
| `/le profile [player]` | View a player's profile |
| `/le stats` | View learning statistics |
| `/le square [mode]` | Enter the English Square (mode: feed / weekly / monthly / alltime / all) |
| `/le leaderboard` | View leaderboards (coins / words / combo / season) |
| `/le challenge` | View today's challenge progress |
| `/le history` | Browse AI chat history (detail page includes the session summary and AI feedback) |
| `/le duel <player>` | Challenge a player to a word duel (`/le duel accept` to accept, `/le duel quit` to quit, `/le duel gui` to reopen the interface) |
| `/le wrong` | Open the wrong-answer book |
| `/le checkin` | Daily check-in for coins |
| `/le season` | View the current season, time left and your rank |
| `/le class` | View your class and the class board |
| `/le save_scenario <name>` | Export the current AI scenario as a share code |
| `/le load_scenario <code>` | Import a shared scenario code (saved as a personal scenario) |
| `/le help` | View help |

### Admin commands `/lea` (alias `/learnadmin`; permission `letslearnenglish.admin`, the `report` subcommand also accepts `letslearnenglish.report`)

| Command | Description |
|---------|-------------|
| `/lea reload` | Hot-reload all configs, word banks and sound cache |
| `/lea reset <player>` | Reset a player's learning data (two-step confirm: run `/lea reset <player> confirm` to execute) |
| `/lea info` | View plugin information |
| `/lea export` | Export player data |
| `/lea exportwordbank <difficulty>` | Export a word bank to YAML |
| `/lea importwordbank <file.yml>` | Import a word bank from YAML (place the file in `plugins/LetsLearnEnglish/exports/`) |
| `/lea broadcast <message>` | Broadcast to all servers via proxy (requires Velocity / BungeeCord) |
| `/lea duel cancel <player>` | Force-dismiss that player's active word duel (no win/loss) |
| `/lea report <player\|all> [week\|lastweek\|<N>d]` | Generate a parent/teacher learning report (TXT, saved to `plugins/LetsLearnEnglish/exports/reports/`) |
| `/lea class create\|delete\|add\|remove\|list` | Class management (create / delete / add member / remove member / list members; requires the class system to be enabled) |
| `/lea ignore-update` | Dismiss the update notice |
| `/lea help` | View admin help |

## Installation

1. Download the latest JAR file
2. Place it in your server's `plugins/` directory
3. Restart the server or use `/reload confirm`
4. Edit `plugins/LetsLearnEnglish/config.yml` to configure
5. (Optional) For AI chat and session feedback, put your DeepSeek API key into `plugins/LetsLearnEnglish/ai_config.yml` (the `api-key` field)
6. Use `/lea reload` to hot-reload without restarting

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
  enabled: true           # Module switch (hide entries + silence events + keep data)
  words-per-session: 10   # Words per session
  answer-timeout: 30      # Answer timeout in seconds

daily-reminder:
  enabled: true
  on-join: true           # Send reminder on player join
  reminder-hours: [9, 14, 20]
```

The excerpt above is only a sample. The full configuration is documented with inline comments in `config.yml` (changes take effect immediately on `/lea reload`); see `DEPLOYMENT_WIKI.md` for more deployment details. Main config sections:

`general` / `prefix` / `database` (SQLite / MySQL) / `word-system` (incl. per-mode switches under `modes`) / `onboarding` (starter guide) / `duel` / `ai-chat` (incl. session feedback under `feedback`) / `scenario` / `money` (daily coin cap) / `progress-system` (with `leaderboard` / `achievements` / `rewards` sub-switches) / `gui` + `gui-layout` (interfaces and slots) / `feedback` (countdown and combo feedback) / `achievement-system` / `proxy` (cross-server broadcast) / `daily-reminder` / `daily-challenges` / `checkin` / `tts` (pronunciation)  / `statistics-server` (web dashboard) / `report` (parent report) / `social` + `english-square` (square feed) / `season` / `class-system`

## Requirements

- Paper 1.21+ (developed and tested on 1.21.4)
- Java 21
- Optional: Vault (coin rewards), PlaceholderAPI (`%lle_*%` placeholders)

## Build

```bash
mvn package -DskipTests
```

The JAR will be generated in the `target/` directory.

## License

MIT License

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
 </picture>
</a>
