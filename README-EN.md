[简体中文](README.md) | English

<div align="center">

# Let's Learn English!

**Learn English on a Minecraft server — players install nothing, admins install one plugin**

Vocabulary drills → spaced repetition → AI conversation practice → learning reports, all in-game

![MC Version](https://img.shields.io/badge/Minecraft-1.21.4%20~%201.21.11%20%7C%2026.1%2B-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21%2B%20%7C%2025%2B%20(mc26)-orange?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Paper-blue?style=flat-square)
![Words](https://img.shields.io/badge/word%20bank-1357%20words-success?style=flat-square)
![Tests](https://img.shields.io/badge/tests-1950%20passing-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/license-GPL--3.0--only-blue?style=flat-square)

**[⬇️ Download latest](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases)** ·
[Deployment docs](DEPLOYMENT_WIKI.md) ·
[Report an issue](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/issues)

</div>

> ⚠️ **A plugin built by a high-school first-year student with AI assistance (vibe coding), still in early development**
>
> Questions and ideas are welcome in [Issues](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/issues);
> if you find it useful, a star helps ⭐
>
> **The [Releases page](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases)
> is the single source of truth for downloadable builds.** This README does not track version
> numbers — when the two disagree, trust Releases.

---

## ✨ What it is

Most "English learning plugins" for Minecraft stop at **flashcards**: show a word, pick a meaning,
add a point. That helps students recognize words, but not *use* them — and it doesn't retain
players. A week later nobody opens it again.

This plugin chains four things together:

| Step | What happens | Why it matters |
|---|---|---|
| **1. Drill** | GUI shows the Chinese meaning → closes → you type the English word against a countdown | Real pressure and combos, not mindless clicking |
| **2. Review** | Ebbinghaus spacing schedules each word's return; misses come back | The forgetting curve, not "learn and forget" |
| **3. AI conversation** | Talk to an AI using the words you just learned, get grammar corrections after | From "recognize" to "use" |
| **4. Reports** | `/lea report` prints a teacher/parent report with weak words and progress | Teachers see results |

The differentiators are **steps 3 and 4** — most MC English plugins only have step 1.

---

## 🚀 Quick start

### 1. Pick the JAR that matches your server

**The wrong one will not load.** Two builds ship from the same source; only the compile
target differs:

| Your server | Download this | Requires |
|---|---|---|
| **Paper 1.21.4 – 1.21.11** | `Let.s.Learn.English.-<version>.jar` | **Java 21+** |
| **Paper 26.1+** (26.2 / 26.3 / 26.4…) | `Let.s.Learn.English.-<version>-mc26.jar` | **Java 25+** |

> Why two builds: Paper requires Java 25 from 26.x onward, and classes compiled for Java 21
> will not load there. The code is identical — only the compile target differs.
> Putting the standard build on 26.x, or `-mc26` on 1.21.x, will break.

### 2. Install it

```bash
# Drop the JAR into your server's plugin directory
cp Let.s.Learn.English.-1.0.7.jar /path/to/server/plugins/

# Restart the server (do not use /reload)
```

On first start the plugin generates its config, word banks, language files and database under
`plugins/LetsLearnEnglish/`.

### 3. Verify it loaded

In-game:

```
/lea info
```

This prints the version, server-version compatibility, and database type. If the version line
says `26.x detected`, you put the standard build on a 26.x server — switch to `-mc26`.

Players run `/le` to open the main menu.

### 4. (Optional) Enable AI conversation

`ai_config.yml` is **not** generated on first start; create it manually and add your DeepSeek
API key:

```bash
# Path: plugins/LetsLearnEnglish/ai_config.yml
```

```yaml
api-key: "sk-your-key"
```

Then `/lea reload` — no restart needed. Without it the console warns `ai_config.yml not found!`
and AI conversation is unavailable; **everything else keeps working**.

---

## 🔄 Upgrading

If your server has already run an older version, follow the steps below. **You do not need to run
any SQL by hand, and you do not need to edit any config** — the plugin migrates its own persistent
structures to this version on startup.

### How to upgrade

1. **Stop the server.** Do not swap the JAR via `/reload` or any plugin hot-swap mechanism.
2. **Back up** (see "Back up before you upgrade" below).
3. Drop the new JAR into `plugins/`, replacing the old one. Pick the right build: the standard JAR
   for Paper 1.21.x, the `-mc26` JAR for Paper 26.x.
4. **Start the server**, then check the log to confirm the migration (see "How to tell it worked").

### What happens automatically on startup

| Action | What you should know |
|---|---|
| **Message files are merged** | `messages/zh.yml` and `messages/en.yml` are each judged by their own `config-version`. When behind, **only missing keys are added**; values you changed (wording, colour codes, placeholders) are **always kept**; keys the plugin no longer uses are left in place, not deleted. |
| **Message files are backed up** | Before merging, the original is saved next to it as `.bak` (i.e. `zh.yml.bak` / `en.yml.bak`). This is a **single slot** — only the most recent pre-upgrade copy is kept, and the next upgrade overwrites it. |
| **Message file format changes** | The file is rewritten in Bukkit's standard YAML serialisation, so **your hand-written comments and key order are not preserved**. The original is in the `.bak`. |
| **Missing tables are created** | Created with `CREATE TABLE IF NOT EXISTS`. This version adds `daily_answer_counters` (the persisted counter behind the daily answering limit — see "A one-time change to expect" below). |
| **Missing columns are added** | The `seasons` table gains `claim_owner` and `ledger_pending` (season claim fencing and owed-ledger marking on multi-server networks). |
| **Missing indexes are added** | `ai_chat_history` gains `idx_chat_timestamp`, so purging old conversation logs no longer slows down linearly with player count. |
| **Coin cap table is created lazily** | `daily_coin_counters` is *not* created on startup — it is created the first time coins are actually paid out. If your server has never paid out coins, not seeing this table is normal. |
| **Built-in word banks are replaced wholesale** | When the bundled word banks have substantive changes (this version went from 1025 to 1357 words), `words/*.yml` is **replaced as a whole** on startup, with the old files backed up to `words-backup-<old version>-<timestamp>/`. **If you edited the word files by hand, your edits will be replaced** — copy them back from that backup directory into the new files. |

Creating tables, columns and indexes is idempotent: anything already present is skipped. If several
servers share one MySQL database and all start at once, one of them may report a duplicate for the
same column — that is an expected condition and is swallowed, not a failure.

Message-file merging and word-bank replacement run on startup and are re-run on `/lea reload`. By
then `config-version` is already current, so repeat calls leave the files untouched. **The database
table/column/index creation runs on startup only** — `/lea reload` does not recreate tables.

### Back up before you upgrade

Back up at least these two; archiving the whole `plugins/LetsLearnEnglish/` folder is the simplest
approach:

- `plugins/LetsLearnEnglish/playerdata.db` — all player learning data. The upgrade itself does not
  alter its structure, but **a backup is your only way back**.
- `plugins/LetsLearnEnglish/messages/` — every message you have customised.

SQLite runs in WAL mode, so **do not just `cp` the database file** for a hot backup. Take a snapshot
with `VACUUM INTO` (the command is in the collapsible section under [FAQ](#-faq)).

### A one-time change to expect

`daily_answer_counters` is new in this version. In older versions, "how many questions answered
today" existed only in memory and **was never written to disk**, so there is no source of truth to
backfill from. The consequence:

> On the day you upgrade, every existing player's daily answering counter starts from 0, which makes
> `word-system.daily-limit` (default 50) effectively **full for that day**.

This is **a one-day, one-time effect, not a bug**, and it clears itself at midnight. There is no
server-side workaround — the only way to make that day accurate is for players to genuinely use up
the quota by answering.

### How to tell it worked

**Check the log.** A successful merge prints one INFO line:

```
Merged messages/zh.yml (v26 -> v27): <n> user value(s) kept, <n> new key(s) added,
<n> obsolete key(s) kept, <n> type conflict(s) resolved (pre-upgrade snapshot: messages/zh.yml.bak)
```

`en.yml` gets an identical line. The `v26 -> v27` in parentheses is the **message file's
`config-version`**, not the plugin version; upgrading from an older release starts at a lower number.
The number that matters is `new key(s) added` — it must be greater than 0.

If the word banks were replaced, you also get a WARNING: `Built-in word banks upgraded to ...`.

**Then check the file.** Open `plugins/LetsLearnEnglish/messages/zh.yml` directly:

- its `config-version` is now `27`
- a `zh.yml.bak` sits next to it
- the new keys are searchable (try searching for keys starting with `answer-`)

`/lea info` only prints the plugin version, server compatibility and database type — it does **not**
reflect the migration result, so do not use it as your check.

### About `config.yml`

The main config has **no migration mechanism** — the `config-version` at the top is a human-readable
marker only, and changing it triggers nothing. New config keys fall back to their built-in defaults
when read: omitting them from `config.yml` is fine and no error is raised, the plugin just uses the
default. Add them only when you want to customise them.

---

## ❓ FAQ

| Symptom | Fix |
|---|---|
| Installed, but `/le` does nothing | Run `/lea info` to confirm it loaded, then check `general.enabled` in `config.yml` — on multi-server networks, survival/PvP servers are often set to `false`. See [WIKI 5.1](DEPLOYMENT_WIKI.md) |
| `Unsupported class file major version` | You put the standard build on a 26.x server. Switch to `-mc26` and run the server on Java 25 |
| AI never replies | Almost always a missing/invalid `ai_config.yml`. Check the console for `ai_config.yml not found!` or `Invalid API key`, fix it, then `/lea reload`. See [WIKI 5.8.1](DEPLOYMENT_WIKI.md) |
| On a multi-server network, check-in pays out twice / coins double | **JVM timezones differ.** The plugin books by plain `yyyy-MM-dd` dates, so two servers with different day boundaries each count their own. Pin it in the startup args (before `-jar`) — see the collapsible section below |
| Changed config, but `/lea reload` didn't apply it | Some keys are **startup snapshots** and won't be rescheduled. Full list in the collapsible section below |
| Where is player data? How do I back it up? | `plugins/LetsLearnEnglish/playerdata.db` (SQLite). The plugin backs up on a timer to `backups/`, keeping 7 by default. Manual backups must account for WAL — see below |
| The database file only grows | SQLite `DELETE` marks pages reusable without returning disk. You need a manual `VACUUM` — see below |
| Can I edit the word banks or add my own words? | Yes. Edit `plugins/LetsLearnEnglish/words/*.yml` then `/lea reload`, or import with `/lea importwordbank`. Template in [`words/README.md`](src/main/resources/words/README.md) |
| Does it support Spigot / Purpur / Folia? | **Paper only.** The plugin uses Paper-exclusive APIs (Adventure components, `Server#getMinecraftVersion`) that Spigot lacks |
| Do I need Vault / PlaceholderAPI? | Both optional. Vault for coin rewards, PlaceholderAPI for `%lle_*%` placeholders. Neither is required to play |

---

## 📦 Features

### Core learning

- **Vocabulary drills** — GUI shows the Chinese meaning → closes → you type the English word against a countdown; three modes (choice / spelling / fill_blank)
- **Combo multiplier** — consecutive correct answers scale rewards (1.0x → 3.0x); a miss or timeout resets it
- **Ebbinghaus spaced repetition** — review scheduling from 1 hour to 168 hours; missed words come back
- **Wrong-answer book** — three misses auto-collect a word; review and remove independently
- **Onboarding questline** — first join grants a handbook: pick a bank → 3-word mini-session → open your profile; `/le guide` re-issues it, veterans skip automatically
- **Three word banks, 1357 words** — beginner 487 / intermediate 355 / advanced 515, ordered by NGSL frequency (85% coverage of the standard Chinese middle-school curriculum)

### AI conversation

- **AI scenario chat** — DeepSeek API, 6 built-in scenarios (village trade / enchanting / nether trip / building / restaurant / free talk), 3 difficulty tiers
- **Session summaries and feedback** — local stats on session end; qualifying sessions get an async AI review with grammar corrections and recommended words; replayable via `/le history`
- **Custom scenario sharing** — Base64 share codes import as personal scenarios (30 per player max)
- **Personal scenario GUI** — graphical view/delete with inline confirmation
- **Thinking-mode controls** — adaptation switches for DeepSeek V4's default thinking mode (`thinking-mode` / `reasoning-effort`); default sends not one extra byte

### Social and competitive

- **PvP word duels** — real-time two-player race to 5 points, with reconnect support
- **English Square** — activity feed (sessions/achievements/check-ins auto-posted, same-type merged daily) + weekly/monthly/all-time boards + player list
- **Leaderboards** — coins / words / combo / season, persisted in the database
- **Season system** (off by default) — fixed-period seasons with rank-based coin payouts (top1 / top3 / top10 / participation), broadcast and offline catch-up
- **Class system** (off by default) — admins/teachers create classes, add members (one class per player); players view their class and class board
- **22 achievements** — enum-defined with bilingual names, individually toggleable in `achievements-config.yml`

### Operations

- **Daily challenge / daily check-in** — challenges pay out on completion; check-in streaks escalate, breaking resets
- **Teacher/parent reports** — `/lea report` produces a plain-text report (overview / daily detail / weak words / cumulative results / a note for parents), single-player and whole-server comparison
- **Statistics HTTP dashboard** — ECharts web panel with two-layer protection: IP whitelist (CIDR-aware) + HTTP Basic Auth
- **Nine module switches** — word-system / ai-chat / duel / checkin / daily-challenges / leaderboard / achievements / rewards plus the progress-system umbrella; all follow "hide entry + silence events + never delete data", effective on `/lea reload`
- **Bilingual UI** — Chinese/English toggle in the main menu; custom message prefixes
- **Fully-commented config** — 836 lines of `config.yml`, every key documenting its behavior and side effects

### Maintenance

- **Automatic database backup** — on a timer, keeping the last 7, using `VACUUM INTO` for snapshots
- **Hot reload** — `/lea reload` covers most config (a few startup-snapshot keys need a restart; see the collapsible section)
- **Proxy support** — Velocity / BungeeCord networks, with optional HMAC-SHA256 signing of inbound messages
- **Update checker** — checks GitHub Releases on startup, notifies OPs on join
- **Pronunciation aid** — IPA display plus note-block cue (no real speech in the current version; see below)

---

## 🎮 Commands

### Player commands `/le` (aliases `/learn`, `/english`)

| Command | Description |
|---|---|
| `/le menu` | Open the main menu |
| `/le word [difficulty] [mode]` | Start a drill (difficulty: beginner / intermediate / advanced; mode: choice / spelling / fill_blank) |
| `/le review` | Review words due now |
| `/le wrong` | Open the wrong-answer book |
| `/le guide` | View onboarding progress / re-issue the handbook |
| `/le dialogue [scenario]` | Start an AI scenario conversation |
| `/le history` | Browse AI conversation history (details include summaries and AI feedback) |
| `/le save_scenario <name>` | Export the current AI scenario as a share code |
| `/le load_scenario <code>` | Import a share code as a personal scenario |
| `/le stats` | View learning statistics |
| `/le progress` | Open your profile |
| `/le profile [player]` | View a player's profile |
| `/le leaderboard` | View leaderboards (coins / words / combo / season) |
| `/le challenge` | View today's challenge progress |
| `/le checkin` | Daily check-in for coins |
| `/le duel <player>` | Challenge a player (`accept` / `quit` / `gui`) |
| `/le square [mode]` | Open the English Square (feed / weekly / monthly / alltime / all) |
| `/le season` | View the current season, time left, and your rank |
| `/le class` | View your class and its board |
| `/le help` | Show help |

### Admin commands `/lea` (alias `/learnadmin`)

| Command | Description |
|---|---|
| `/lea reload` | Hot-reload config, AI settings, word banks, language files, sound cache, statistics service (a few startup-snapshot keys need a restart) |
| `/lea info` | Show plugin info (version / compatibility / database type) |
| `/lea diagnose` | Self-diagnostics (config, database, dependencies, permissions) |
| `/lea report <player\|all> [week\|lastweek\|<N>d]` | Generate a teacher/parent report (TXT under `exports/reports/`) |
| `/lea reset <player>` | Reset a player's data (two-step: prompt first, then `confirm`) |
| `/lea export` | Export player data |
| `/lea exportwordbank <difficulty>` | Export a word bank to YAML |
| `/lea importwordbank <file.yml>` | Import a word bank from `exports/` |
| `/lea class create\|delete\|add\|remove\|list` | Class management (requires the class system) |
| `/lea broadcast <message>` | Broadcast to all sub-servers via the proxy (needs Velocity / BungeeCord) |
| `/lea duel cancel <player>` | Force-dissolve a player's duel (no win/loss recorded) |
| `/lea ignore-update` | Ignore this update notice |
| `/lea help` | Show admin help |

### Permission nodes

The plugin registers exactly **4** permission nodes, all defaulting to OP.

**Player features mostly need no permission** — `/le menu`, `/le word`, `/le review`, `/le square`,
`/le profile`, `/le wrong`, `/le checkin`, `/le duel`, `/le history`, `/le season` and friends are
gated by **module switches** in `config.yml`, not by permissions.

**The one exception is scenario import** — `/le load_scenario` and the personal-scenario GUI need
`letslearnenglish.scenario.import`, because they write external content into the server's scenario
list and deserve separate authorization from ordinary play.

| Node | Default | Purpose |
|---|:---:|---|
| `letslearnenglish.admin` | op | All `/lea` subcommands plus the scenario GUI's delete action |
| `letslearnenglish.report` | op | **Only** `/lea report` — grant teachers/parent reps report access without full admin |
| `letslearnenglish.ai.reload` | op | **Only** reload AI config (`ai_config.yml` + `ai_scenarios.yml`), for content maintainers |
| `letslearnenglish.scenario.import` | op | Scenario import and personal-scenario GUI view/delete |

> Note: `letslearnenglish.ai.reload` is **not** equivalent to `/lea reload` — it only refreshes
> the AI section. To grant "can change the API key" without full admin, this is enough.

---

## ⚙️ Configuration

Key settings (`config.yml`, fully commented in Chinese):

```yaml
general:
  enabled: true           # Per-server master switch (survival/PvP servers often false)
  language: "zh"          # Default language (zh/en)
  check-updates: true     # Check for updates on startup

prefix:
  zh: "&8[&b英语学习&8] &f"
  en: "&8[&bEnglish&8] &f"

word-system:
  enabled: true           # Module switch (hide entry + silence events + keep data)
  words-per-session: 10   # Words per session
  answer-timeout: 30      # Answer timeout in seconds

daily-reminder:
  enabled: true
  on-join: true           # Send a reminder on join
  reminder-hours: [9, 14, 20]
```

Full section list: `general` / `prefix` / `database` (SQLite / MySQL) / `word-system` /
`onboarding` / `duel` / `ai-chat` / `scenario` / `money` / `progress-system` /
`gui` + `gui-layout` / `feedback` / `achievement-system` / `proxy` /
`daily-reminder` / `daily-challenges` / `checkin` / `tts` / `statistics-server` /
`report` / `social` + `english-square` / `season` / `class-system`

### Keys that require a server restart

These are snapshotted at startup or used to schedule periodic tasks; `/lea reload` **will not**
reschedule them:

`general.auto-save-interval` · `general.backup.*` · `general.check-updates` ·
`daily-reminder.reminder-hours` · `tts.enabled` / `tts.cache-days` / `tts.voicerss-api-key` ·
`progress-system.leaderboard.update-interval` · `word-system.review.intervals` ·
`mastered-review-interval-days` · `database.*`

Everything else (all module `enabled` switches, `ai-chat.*`, language files, word banks,
`gui-layout`, achievement toggles, the statistics dashboard) applies on `/lea reload`.
See [`DEPLOYMENT_WIKI.md` 5.7.1](DEPLOYMENT_WIKI.md#571-需重启服务器才生效的键启动快照--周期任务).

<details>
<summary><b>⚠️ Multi-server networks (BungeeCord / Velocity): timezones must match exactly</b></summary>

All sub-servers sharing one database must run the **same JVM timezone**. The plugin books by
plain `yyyy-MM-dd` dates, so two servers with different day boundaries will let
**check-in be claimed twice on the same day** (real double payouts), and daily answer limits,
coin caps, AI round limits and daily challenges will each count separately. Season axes are
derived by a pure function from `anchor-date` against the day boundary, so they are especially
sensitive.

There is **no** `timezone` config key — you cannot unify it from inside the plugin. Pin it with a
startup argument, **before `-jar`**:

```bash
java -Duser.timezone=Asia/Shanghai -jar paper.jar
```

This is a **deployment prerequisite, not a plugin defect**.
See [`DEPLOYMENT_WIKI.md` 5.9](DEPLOYMENT_WIKI.md#59-多子服时区一致性硬性前提).

</details>

<details>
<summary><b>🔒 Proxy network security (<code>proxy.inbound-secret</code>)</b></summary>

Bukkit dispatches "plugin messages forwarded by the proxy" and "custom payloads any player client
sends itself" to **the same callback**, and that callback gets no reliable origin information —
a player with a modified client can forge any of these frames:

- `GlobalMessage` → broadcast arbitrary text server-wide (disguised as an admin announcement)
- `AchievementUnlock` → attach any achievement to any player name and broadcast it
- `SeasonSettled` → trigger season payouts and the "season ended" broadcast (amounts are protected
  by ledger idempotency, but the attacker controls *when* money moves)

Set `proxy.inbound-secret` to a long random string **only you know**
(`openssl rand -hex 32` is a good source). Every inbound frame must then carry a valid
HMAC-SHA256 signature or it is dropped with a WARNING. **Every sub-server must use the same value.**

Empty (the default) = no signature checking, byte-for-byte identical to the behavior before
this feature existed.

> This is not transport encryption like HTTPS. Messages still cross the proxy in plaintext;
> this defends against "an ordinary player forging cross-server commands", not against
> "someone who can sniff traffic and read your secret".

</details>

<details>
<summary><b>💾 SQLite maintenance (long-running servers)</b></summary>

SQLite's `DELETE` / `UPDATE` only mark pages reusable — **they do not return disk space**.
Tables with constant churn (`ai_chat_history`, `social_feed`, `word_progress`) make
`playerdata.db` grow without shrinking, and fragmentation slows queries. Automatic backups use
`VACUUM INTO`, which merely **produces a snapshot copy**; the live database doesn't change a byte,
so it does not solve this.

The plugin already ships an index (`idx_chat_timestamp`) that turns `cleanupOldChatHistory` from a
full scan into a range scan (MariaDB, 500k rows: 1557 ms → 1 ms measured). To tidy manually:

1. Stop the server (or at least confirm no backup job is running)
2. **The database is not a single `.db` file — WAL is enabled.** You must checkpoint first and use
   SQLite's own backup command; a plain `cp playerdata.db` misses transactions still in the WAL:

   ```bash
   sqlite3 playerdata.db "PRAGMA wal_checkpoint(TRUNCATE);"
   sqlite3 playerdata.db ".backup 'playerdata.db.bak'"
   ```

3. Tidy and verify:

   ```bash
   sqlite3 playerdata.db "PRAGMA optimize; VACUUM;"
   sqlite3 playerdata.db "PRAGMA quick_check;"   # expect: ok
   ```

4. Restart the server

> `DatabaseManager.runMaintenance()` (`PRAGMA optimize` + `VACUUM`) exists but is **not yet
> exposed as an in-game command**. If you call it from code, **it must run off the main thread** —
> `VACUUM` holds a write lock and its duration scales with database size.

</details>

<details>
<summary><b>🔊 About pronunciation: no real speech in the current version</b></summary>

IPA display works fully. But **the pronunciation button plays a note-block cue**, not recorded
speech.

With the `tts` section configured, VoiceRSS downloads `.wav` files into `audio/` as a cache for a
future resource pack — **the plugin does not play them**. This is intentional, not a bug.

</details>

---

## 🏗️ How it works

```
Player (no client mod needed)
   └─ /le → main menu GUI → drill / review / AI chat / square
        │
        └─ Paper server plugin (this repo, Java 21 / 25)
             ├─ Drill state machine  WordLearningManager (countdown, combo scoring)
             ├─ Spaced repetition    ReviewScheduler (Ebbinghaus 1h ~ 168h)
             ├─ AI conversation      AIChatManager → HTTPS → DeepSeek API
             ├─ Data layer           PlayerDataStore (in-memory cache + persistence)
             │                         ├─ SQLite (default, single file, WAL)
             │                         └─ MySQL (shared across sub-servers)
             └─ Optional integration Vault (coins) · PlaceholderAPI (%lle_*%) · Web dashboard
```

### Stack

| Layer | What's used |
|---|---|
| Server | Paper 1.21.4 – 1.21.11 (Java 21) / Paper 26.1+ (Java 25) |
| Language | Java 21 (104 source files, ~57,500 lines) |
| Database | SQLite (default, bundled) / MySQL (`mysql-connector-j` shaded into the JAR) |
| AI | DeepSeek API via `java.net.http.HttpClient`, async with per-player rate limiting |
| Tests | JUnit 5 + Mockito, **1950** tests plus a JaCoCo coverage gate |
| Build | Maven with two profiles (default / `-Pmc26`) |

---

## 🔧 Building

**Requirements**: JDK 21 (JDK 25 for the `-Pmc26` profile), Maven 3.9+

```bash
# Standard build (Paper 1.21.4 – 1.21.11)
mvn verify

# 26.x build (Paper 26.1+)
mvn -Pmc26 verify
```

`mvn verify` compiles, runs the tests and enforces the JaCoCo coverage gate — it is the standard
command for validating a change.

> `mvn package -DskipTests` is for quick local packaging only — **do not use it to validate a
> change**, since it skips the coverage gate too.

The JAR lands in `target/`. The two profiles produce
`Let's Learn English!-<version>.jar` and `Let's Learn English!-<version>-mc26.jar`
(GitHub replaces `!` and spaces with `.` on upload, so Release assets appear as
`Let.s.Learn.English.-<version>.jar`).

---

## 🔗 Requirements

- **Required**: Paper 1.21.4+ (see the JAR selection table above)
- **Optional**: Vault (coin rewards), PlaceholderAPI (`%lle_*%` placeholders)
- **Not guaranteed**: Spigot / Purpur / Folia

> Why Paper only: the plugin uses Paper-exclusive APIs (Adventure `net.kyori` components,
> `Server#getMinecraftVersion`) that do not exist on Spigot.

> The MySQL driver is shaded into the JAR, so no extra driver jar is needed. If a self-built JAR
> reports `ClassNotFoundException: com.mysql.cj.jdbc.Driver`, confirm the `shade-mysql-driver`
> execution ran (`mvn package` output should be ~3 MB; `unzip -l` should list `com/mysql/`).

---

## 🤝 Development and contributing

The code isn't beautiful, but every fix is pinned by tests. If you want to make it better —
come play. Issues and PRs are welcome.

- **Contributing guide**: [`CONTRIBUTING.md`](CONTRIBUTING.md) (中文) / [`CONTRIBUTING-EN.md`](CONTRIBUTING-EN.md) — how to report, how to PR, gates and testing requirements
- **Security**: [`SECURITY.md`](SECURITY.md) (中文) / [`SECURITY-EN.md`](SECURITY-EN.md) — what the plugin sends, where credentials live, how to report a vulnerability
- **Build and test**: `mvn verify` (see "Building" above)
- **When reporting a bug**, include: plugin version (`/lea info`), server version, Java version, repro steps, and console logs

---

## 📚 Documentation

| Document | Contents |
|---|---|
| [`DEPLOYMENT_WIKI.md`](DEPLOYMENT_WIKI.md) | **Deployment handbook**: requirements, install steps, every config key, troubleshooting, command reference (Chinese) |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) / [`CONTRIBUTING-EN.md`](CONTRIBUTING-EN.md) | Contributing guide (中文 / English) |
| [`SECURITY.md`](SECURITY.md) / [`SECURITY-EN.md`](SECURITY-EN.md) | Security policy (中文 / English): data handling, credential locations, vulnerability reporting |
| [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) | Third-party components and word-bank sources |
| [`src/main/resources/words/README.md`](src/main/resources/words/README.md) | Word-bank field template, sources, licenses and known quality limits |
| [`CHANGELOG.md`](CHANGELOG.md) | Version history |
| [Releases](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/releases) | **JAR downloads** (no token required) |

---

## ⚖️ License

GNU General Public License v3.0 only (GPL-3.0-only). Full text in [`LICENSE`](LICENSE).

**Word-bank sources and licenses** (see [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and
[`words/README.md`](src/main/resources/words/README.md)):

- **NGSL 1.2** (New General Service List, Browne, Culligan & Phillips) — **CC BY-SA 4.0**,
  used only for word selection and ordering
- **ECDICT** (MIT) — Chinese definitions and part-of-speech reference
- **CMUdict** (BSD) — ARPAbet-to-IPA transcription for newly added words (American)
- **Tatoeba** (CC BY 2.0 FR) — example sentence source

Per-entry content (definitions, phonetics, examples, synonyms/antonyms) is original to this
project and released under the project license.

> **Known quality limits** (stated plainly): the phonetics of the 332 newly added words were
> converted from CMUdict American pronunciation and agree with the rest of the bank's British IPA
> only about 58%–68% of the time — **not manually verified word by word**. About 37% of Tatoeba
> example sentences contain 1–3 out-of-vocabulary content words and are **not manually verified
> sentence by sentence**. If something looks wrong, edit the YAML and `/lea reload`.

---

## ⭐ Star History

<a href="https://www.star-history.com/?repos=sqkl520%2FMinecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&theme=light&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=sqkl520/Minecraft-server-plugin-Let-s-learn-English&type=date&legend=top-left" />
 </picture>
</a>
