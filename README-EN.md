[简体中文](README.md) | English

# Let's Learn English!

**English learning for Minecraft servers — players install nothing, admins install one plugin.**
Vocabulary drills → spaced repetition → AI conversation practice → printable teacher reports,
all inside the game.

[![Build & Verify](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/actions/workflows/build.yml/badge.svg)](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/sqkl520/Minecraft-server-plugin-Let-s-learn-English?display_name=tag&sort=semver)](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)

---

## Quick start

### 1. Pick the JAR that matches your server

**The wrong one will not load.** Two builds ship from the same source; only the compile
target differs:

| Your server | Download | Needs |
|---|---|---|
| **MC 1.21.4 – 1.21.11** | `Let.s.Learn.English.-<version>.jar` | **Java 21+** |
| **MC 26.1+** (26.2 / 26.3 / 26.4…) | `Let.s.Learn.English.-<version>-mc26.jar` | **Java 25+** |

### 2. Install

```bash
cp Let.s.Learn.English.-1.0.7.jar /path/to/server/plugins/
```

Restart the server. Do not use `/reload`.

### 3. Confirm it worked

In-game:

```
/lea info
```

Prints the plugin version, whether your server version is supported, and the database
backend. If the compatibility line says `26.x detected`, you installed the standard build
on a 26.x server — switch to the `-mc26` one.

Players run `/le` to open the main menu.

### 4. (Optional) Enable AI conversation

Edit `plugins/LetsLearnEnglish/ai_config.yml`:

```yaml
api-key: "sk-your-key"
```

Then `/lea reload` — no restart needed.

> ⚠️ **If you run multiple backend servers behind BungeeCord / Velocity**
>
> Every backend sharing the same database **must run in the exact same JVM timezone**.
> The plugin books things by plain `yyyy-MM-dd` dates. Two servers with different day
> boundaries will let a player **claim the daily check-in twice on the same day** (double
> coins), and daily answer limits, coin caps, AI turn gates and daily challenges will each
> count separately.
>
> There is no `timezone` config key — it cannot be normalized inside the plugin. Pin it
> with a JVM flag, and it **must come before `-jar`**:
>
> ```bash
> java -Duser.timezone=Asia/Shanghai -jar paper.jar
> ```

---

## What it looks like

<!--
  Screenshots/GIFs pending: main menu, quiz flow, AI conversation, teacher report.
  This is currently the biggest gap in this README — readers cannot judge
  interaction quality from prose alone.
-->

*Screenshots pending.*

---

## What problem this solves

Most "learn English" plugins on Minecraft servers stop at **flashcards**: show a word,
pick a meaning, award points. Students memorize, but never learn to *use* the words — and
after a week nobody opens the menu again.

This plugin chains four steps into one loop:

1. **Vocabulary drills** — GUI shows the Chinese meaning, closes, and you type the English
   word into chat against a countdown. Pressure, and a combo multiplier.
2. **Spaced repetition** — Ebbinghaus-curve scheduling; words you get wrong come back.
3. **AI conversation** — role-play a scene using the words you just learned, then get
   grammar corrections and suggested next words.
4. **Teacher reports** — `/lea report` produces a printable progress report with the
   words a student gets wrong most.

The difference is **steps 3 and 4**. Most MC English plugins only have step 1.


---

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
- **Word Pronunciation Helper** —— Phonetic display + a cue sound (no real speech is played in the current version: clicking the pronunciation button plays a note-block cue; VoiceRSS `.wav` files are only cached under `audio/` for a possible future resource pack, and the plugin never plays them)
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
| `/lea reload` | Hot-reload configs, the AI section (`ai_config.yml` / `ai-chat.*` / scenarios), word banks, language files, sound cache and the stats server (a few startup-snapshot keys still need a restart — see Configuration) |
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

### Permission nodes

The plugin registers 4 permission nodes, all defaulting to OP only.

**Almost all player-facing features require no permission** — `/le menu`, `/le word`, `/le review`, `/le square`, `/le profile`, `/le wrong`, `/le checkin`, `/le duel`, `/le history`, `/le season` and friends are gated by the module switches in `config.yml`, not by permissions.
**The one exception is scenario import**: `/le load_scenario` and the personal-scenario management page require `letslearnenglish.scenario.import`, because they let outside content into a server's scenario list and therefore deserve separate authorization from ordinary play.

| Node | Default | Purpose |
|------|:---:|------|
| `letslearnenglish.admin` | op | All `/lea` subcommands (`reload` / `reset` / `info` / `export` / `exportwordbank` / `importwordbank` / `broadcast` / `duel cancel` / `class` / `diagnose` / `ignore-update` / `help`), plus the delete action on the scenario management page |
| `letslearnenglish.report` | op | **Only** `/lea report`. Lets you grant "generate parent reports" to a teacher or parent representative without handing out full admin |
| `letslearnenglish.ai.reload` | op | **Only** reloads the AI config (`ai_config.yml` + `ai_scenarios.yml`), equivalent to the "refresh API key" button in the GUI. Unlike `/lea reload`, it does not reload other modules, so it can be granted to a content maintainer on its own |
| `letslearnenglish.scenario.import` | op | **The only player-facing feature needing explicit authorization**: `/le load_scenario` share-code import, plus viewing/deleting on the personal scenario management page |

> Note 1: `letslearnenglish.ai.reload` is **not** equivalent to `/lea reload` — the former only refreshes the AI section. If you want to authorize someone to change the API key, this node is enough; `admin` is not required.
> Note 2: the scenario management page requires `admin` for **deletion** but `scenario.import` for **viewing/importing** — deliberately separate (see the permission-gate comment in `ScenarioSelectListener`).

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

The excerpt above is only a sample. The full configuration is documented with inline comments in `config.yml` (most keys take effect immediately on `/lea reload`; a few require a restart — see below); see `DEPLOYMENT_WIKI.md` for more deployment details. Main config sections:

`general` / `prefix` / `database` (SQLite / MySQL) / `word-system` (incl. per-mode switches under `modes`) / `onboarding` (starter guide) / `duel` / `ai-chat` (incl. session feedback under `feedback`) / `scenario` / `money` (daily coin cap) / `progress-system` (with `leaderboard` / `achievements` / `rewards` sub-switches) / `gui` + `gui-layout` (interfaces and slots) / `feedback` (countdown and combo feedback) / `achievement-system` / `proxy` (cross-server broadcast) / `daily-reminder` / `daily-challenges` / `checkin` / `tts` (pronunciation)  / `statistics-server` (web dashboard) / `report` (parent report) / `social` + `english-square` (square feed) / `season` / `class-system`

**Keys that require a server restart** (they are snapshotted at startup or used to schedule periodic tasks; `/lea reload` does not reschedule them):

- `general.auto-save-interval`
- `general.backup.enabled` / `general.backup.interval` / `general.backup.max-backups`
- `general.check-updates` (only decides at startup whether to run the online check)
- `daily-reminder.reminder-hours`
- `tts.enabled` / `tts.cache-days` / `tts.voicerss-api-key`
- `progress-system.leaderboard.update-interval`

Everything else (including all module `enabled` switches, `ai-chat.*`, language files, word banks, `gui-layout`, achievement switches and the stats dashboard) takes effect immediately on `/lea reload`.

> Exceptions: `word-system.review.intervals` and `mastered-review-interval-days` (the review intervals are snapshotted when `ReviewScheduler` is constructed) and `database.*` (initialised only in `onEnable`) also require a restart. See `DEPLOYMENT_WIKI.md` §5.7.1.

> One caveat: `daily-reminder.enabled` and `word-system.review.enabled` can only be turned **off** via reload if the task was scheduled at startup; if they were `false` at startup (task never scheduled), switching them back to `true` requires a restart. See `DEPLOYMENT_WIKI.md` §5.7.1.

### Cross-server network security (`proxy` section)

If you link several backend servers with **BungeeCord / Velocity**, the `proxy` section has two keys worth configuring:

| Key | Meaning |
|-----|---------|
| `proxy.server-name` | This backend's name in your proxy config. Used to suppress duplicate season start/settle broadcasts. Empty = standalone mode (at worst one duplicate broadcast; payouts stay protected by the ledger's idempotency). |
| `proxy.inbound-secret` | **Shared secret for inbound messages. Strongly recommended for any multi-server network.** |

**Why `inbound-secret` is a real security requirement, not a nice-to-have**: Bukkit dispatches both "plugin message forwarded by the proxy" and "custom payload sent by any player's client" to the **same callback**, and that callback has no reliable provenance. A player with a modified client can therefore forge any of these frames, and the effect is identical to a genuine backend server sending them:

- `GlobalMessage` → broadcast arbitrary text to the whole server (can impersonate an owner announcement)
- `AchievementUnlock` → attach any achievement to any player name and broadcast it
- `SeasonSettled` → trigger season reward delivery and a "season ended" broadcast (amounts cannot double-pay thanks to the ledger's conditional-claim, but the **timing** of payouts is attacker-controlled)

Once you set any **long random string only you know** (the output of `openssl rand -hex 32` is a good choice), every inbound frame must carry a valid HMAC-SHA256 signature or it is dropped with a WARNING. **Every backend in the network must use the same value**, otherwise they will drop each other's frames too.

Empty (the default) = signature checking disabled, byte-identical to the behaviour before this feature existed — fine for a single server or a pure LAN setup.

> Note: this is not transport encryption like HTTPS. Messages still cross the proxy in cleartext. This mechanism stops *ordinary players* from forging cross-server commands; it does not stop someone who can sniff traffic and read your secret.

## Requirements

- Paper 1.21+ (developed and tested on 1.21.4; Spigot compatibility is not guaranteed)
- Java 21
- Optional: Vault (coin rewards), PlaceholderAPI (`%lle_*%` placeholders)

> Why Paper only: the plugin uses Paper-exclusive APIs (Adventure `net.kyori` components, `Server#getMinecraftVersion`), which do not exist on Spigot.

> The MySQL driver (`mysql-connector-j`) is bundled into the JAR via shade, so no extra driver is needed for the MySQL backend. If you build your own JAR and see `ClassNotFoundException: com.mysql.cj.jdbc.Driver`, check that the `shade-mysql-driver` execution of `maven-shade-plugin` ran (the `mvn package` artifact should be ~3MB and `unzip -l` should list `com/mysql/`), or use the official release JAR.

## Build

```bash
mvn verify
```

`mvn verify` compiles, runs the tests and enforces the JaCoCo coverage gate — it is the standard command for validating a change.

> `mvn package -DskipTests` is for quick local packaging only; do not use it to validate changes.

The JAR will be generated in the `target/` directory.

## License

GNU General Public License v3.0 only (GPL-3.0-only); the full text is in the repository root `LICENSE`.

Attribution notes: third-party components bundled into the JAR (e.g. MySQL Connector/J) are listed in `THIRD-PARTY-NOTICES.md`; word-list sources are documented in `words/README.md` (source at `src/main/resources/words/README.md`; the NGSL is used under CC BY-SA 4.0 for word selection only).

## Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
 </picture>
</a>
