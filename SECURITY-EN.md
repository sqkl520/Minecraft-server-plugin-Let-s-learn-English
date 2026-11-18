[简体中文](SECURITY.md) | English

# Security Policy

Let's Learn English! is an **English-learning** plugin for Paper-family Minecraft servers. It reads
and writes player learning data, stores files and a SQLite database under `plugins/LetsLearnEnglish/`,
and only reaches external services once you enable them.

But it differs from a read-only monitoring plugin in one essential way: **it sends player chat
content to the AI provider you configure.** This document is therefore deliberately explicit about
**what it collects, what it sends, and what it stores.**

---

## Supported Versions

Security fixes are issued for the **latest release only**.

| Version | Supported | Notes |
|---------|-----------|-------|
| 1.0.7.x | ✅ Yes | Security fixes land here |
| Older versions | ❌ No | Please upgrade |

Two jars ship from the same source with different compile targets; **both are supported**:

| Artifact | Supported Minecraft | Requires |
|---|---|---|
| `Let.s.Learn.English.-<version>.jar` | 1.21.4 – 1.21.11 | Java 21+ |
| `Let.s.Learn.English.-<version>-mc26.jar` | 26.1+ | Java 25+ |

There is no backporting to older versions. This is a single-maintainer project, and maintaining
parallel branches would mean maintaining none of them well.

---

## Reporting a Vulnerability

**Do not open a public issue for a security problem.** A public issue is visible to everyone,
including anyone who would exploit it before a fix exists.

**Preferred — GitHub Security Advisories:**

> <https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/security/advisories/new>

This gives us a private thread, a place to develop the fix, and a way to publish an advisory that
credits you.

**Alternative**: find the contact address on the [author's GitHub profile](https://github.com/sqkl520)
and say you are reporting a security issue — **do not include exploit details in the first message**.

You do not need to encrypt, and there is no PGP key. Do **not** paste production credentials, API
keys, real player names, or player chat content into the report; redact them.

### What to include

- Plugin version (`/lea info` prints it, or read the jar filename)
- **Which build you installed** (standard or `-mc26`)
- Server software and build, e.g. `Paper 1.21.11 build 132`, or `Purpur` / `Folia` / `Leaf`
- Java version and OS
- What the issue is, and what an attacker gains from it — impact matters more than the mechanism
- Reproduction steps, or a proof of concept. A minimal repro is worth more than a long write-up
- Relevant log excerpts, **redacted** (server IPs, player names, API keys, `inbound-secret`, dashboard password)

### What to expect

| Stage | Target |
|-------|--------|
| Acknowledgement | **Within 7 days** |
| Initial assessment — accepted / needs more info / out of scope | **Within 14 days** |
| Fix, or a mitigation you can apply yourself | **Within 90 days**; faster for high severity |
| Advisory published with credit | Once the fixed version is available |

These are targets given by **one person**, not a contracted SLA. The project is free, maintained by
a student in their spare time, and offers no bug bounty. If a report needs expertise I do not have,
I will say so rather than let it sit. Unless you ask to stay anonymous, you will be credited in the
advisory and the CHANGELOG.

### Scope — what counts as a vulnerability

**In scope:**

- Remote code execution, authentication bypass, or privilege escalation achieved through this plugin
- Any path that lets a **non-OP player** read data they should not, or trigger a reset, export,
  config reload, or file write
- **Credential leakage** into logs, reports, error messages, or chat output.
  `ai_config.yml`'s `api-key`, `tts.voicerss-api-key`, `proxy.inbound-secret`, and
  `statistics-server.auth.password` all count as credentials
- **Cross-server message forgery**: on a multi-server network without `proxy.inbound-secret`,
  forging `GlobalMessage` / `AchievementUnlock` / `SeasonSettled` frames. This is a **documented
  configuration boundary** (see "Known non-issues" #3 below); bypassing the HMAC check when a secret
  *is* configured is in scope
- Path traversal or arbitrary file write via `importwordbank`, report export, or scenario import
- Denial of service reachable by an unprivileged player through normal gameplay
- Build-chain issues: the shaded MySQL driver, CI workflows, published artifacts

**Out of scope** — expected behaviour, not vulnerabilities:

- **Anything requiring OP or console access.** Admins can already run arbitrary commands and read
  server files. `/lea importwordbank` reading files under `exports/` is by design.
- **Anything requiring another malicious plugin** or a modified server jar. Plugins share one JVM,
  and modern Minecraft has no SecurityManager.
- **Any issue in a version outside the support list above.**
- **Vulnerabilities in Minecraft / Paper / Purpur / Folia / Bukkit itself.** Report those upstream.
- **A client the player controls.** This plugin ships no client mod and trusts nothing from the client.

### Known non-issues

These are frequently reported as bugs or security issues, but are intentional:

1. **"A non-OP player gets `You do not have permission`."** The plugin registers only 4 permission
   nodes, all defaulting to `op`. All other player features are gated by **module switches** in
   `config.yml`, not permissions — that is deliberate, not a misconfiguration. See the permission
   nodes section of the README.
2. **"The `statistics-server` dashboard uses the default password `changeme`."** It defaults to
   `enabled: false` bound to `127.0.0.1`. **When the password is still default/empty AND the bind
   address is not loopback, the plugin refuses to start that dashboard and logs SEVERE** rather than
   starting it and printing a warning nobody reads. See "Statistics dashboard" below.
3. **"A player can forge cross-server messages when `proxy.inbound-secret` is unset."** This is a
   **documented configuration boundary**. Bukkit dispatches proxy-forwarded plugin messages and
   player-sent custom payloads to the same callback, and that callback gets no reliable origin
   information. With a secret configured, every inbound frame must carry a valid HMAC-SHA256
   signature or it is dropped with a WARNING. **Leaving it empty is reasonable on a single server or
   a LAN**; on a multi-server network, set it.
4. **"The plugin writes files under `plugins/LetsLearnEnglish/`."** Config, word banks, language
   files, the database, backups, exports. Files existing is not itself an information leak; if
   unrelated local users on the host can read your server directory, that is a host-level problem.
5. **"AI chat sends player messages to a third party."** That is the **definition** of the feature,
   not a vulnerability. See "AI chat" below.
6. **"Reports generated by `/lea report` contain player names and learning data."** That is the
   tool's purpose. Reports land in `exports/reports/` and go only to the person who ran the command.
   Review them before sharing.

---

## Data handling — what is sent, what is not

**There is no backend, no account system, and no cloud service.** Everything is computed on your server.

### Outbound connections

Exactly **three** possible outbound connections, and only after you configure them:

| # | Connection | Default | Destination | Controlled by |
|---|---|---|---|---|
| 1 | AI chat | **Never happens by default** (`ai-chat.enabled` is true, but `ai_config.yml` is not generated on first start — no api-key means it does not work) | Your `ai-chat.api-url` (DeepSeek by default) | `ai_config.yml` `api-key` + `ai-chat.enabled` |
| 2 | TTS audio download | **Never happens by default** (`tts.enabled` is true, but `voicerss-api-key` is empty — no key means IPA display only, no network) | `api.voicerss.org` (only if you set a VoiceRSS key) | `tts.enabled` + `tts.voicerss-api-key` |
| 3 | Update check | **On** | `api.github.com` releases endpoint | `general.check-updates: false` |

**This plugin contains no bStats and no other telemetry.** You can verify it yourself:

```bash
grep -rn "HttpClient\|HttpRequest\|openConnection\|new URL(\|new Socket" src/main/java/
```

Every hit is in three files: `core/ai/AIChatManager.java` (connection 1), `core/ai/TtsManager.java`
(connection 2), and `core/UpdateChecker.java` (connection 3).
`core/manager/StatisticsHttpServer.java` is an **inbound** HTTP server and makes no outbound requests.

### What connection 3 says

It asks GitHub for this repository's latest release metadata. It sends **no** player data, server
identity, or usage statistics. The result is used to tell OPs about a new version when they join.

### AI chat in detail (connection 1 — the one that matters most)

**Player chat content is sent to the AI provider you configure.** Specifically:

- Every message the player types in AI conversation mode
- The plugin's system prompt (scenario setting, difficulty prompt, the target words being practised)
- **Recent conversation history**, bounded by two keys acting together:
  `ai-chat.max-context-pairs` (default 6 pairs) and `ai-chat.max-context-tokens` (default 2000,
  approximate estimate)
- The player's name is **not** sent explicitly, but if the player types their own name into a
  message, that is part of the text sent

**The same content is also stored locally** in the `ai_chat_history` table (`content TEXT` holds the
full text) so `/le history` can replay it. Retention is governed by `cleanupOldChatHistory`.

> **This is the single thing server owners most need to tell their players.** The plugin currently
> gives players **no** prompt or notice saying "your chat is sent to a third-party AI". Please state
> it in your server rules, or disable the feature with `ai-chat.enabled: false` — everything else
> keeps working unchanged.
>
> If your jurisdiction has requirements about minors' data leaving the country, note that this
> plugin is aimed at exactly that age group.

**Logging surface**: with `ai-chat.debug` enabled, the console prints AI request/response detail
**including conversation text**. Default is `false`. Turn it back off when you finish troubleshooting.

### Where credentials live

All under `plugins/LetsLearnEnglish/`, **never uploaded**, and **never to be committed**:

| Credential | File | Key | Notes |
|---|---|---|---|
| DeepSeek API key | `ai_config.yml` | `api-key` | Sent in an `Authorization: Bearer` header |
| VoiceRSS key | `config.yml` | `tts.voicerss-api-key` | **Placed in the URL query string** (VoiceRSS's API shape, not this plugin's choice) |
| Cross-server signing secret | `config.yml` | `proxy.inbound-secret` | HMAC-SHA256 shared secret; identical on every sub-server |
| Dashboard password | `config.yml` | `statistics-server.auth.password` | HTTP Basic Auth |

> ⚠️ **The VoiceRSS key travels in a URL.** If that request fails and the exception message contains
> the full URL, the key leaks into the log. The current implementation logs only the exception
> message (`Failed to download TTS audio for word '...'`) without concatenating the URL, but **this
> relies on JDK exception-type behaviour**. If that risk bothers you, do not configure TTS.

### Statistics dashboard (inbound HTTP, off by default)

| Setting | Default | Notes |
|---|---|---|
| `enabled` | `false` | Must be enabled explicitly |
| `bind-address` | `127.0.0.1` | Loopback only by default |
| `port` | `8080` | |
| `ip-whitelist` | `127.0.0.1` + `192.168.1.0/24` | IPv4 / IPv6 with CIDR support. **Empty = deny all** (fail-closed) |
| `auth.username` / `auth.password` | `admin` / `changeme` | Basic Auth |

**Two layers**: IP whitelist + HTTP Basic Auth.

**Hard block on default credentials**: when `auth.password` is still the default (`changeme`), empty,
or whitespace-only, **and** `bind-address` is not a loopback address, the plugin **refuses to start
the dashboard** and logs SEVERE; the rest of the plugin is unaffected. That combination has no
legitimate use — it would expose a dashboard containing every player's learning data to the network
with `admin/changeme`.

**What the dashboard shows**: player names, learning statistics, leaderboards, session data.
**It does not show AI conversation content.** If you expose it to the internet, treat it as an
internal admin panel.

### What this plugin stores on your disk

All under `plugins/LetsLearnEnglish/`, **never uploaded**.

| Path | Contents |
|------|----------|
| `config.yml` | Your config (including `proxy.inbound-secret`, dashboard password, VoiceRSS key) |
| `ai_config.yml` | **DeepSeek API key** (plaintext; not auto-generated on first start — you create it) |
| `ai_scenarios.yml` | AI scenarios and difficulty prompts |
| `achievements-config.yml` | Achievement toggles |
| `playerdata.db` | **SQLite database** with all player learning data |
| `playerdata.db-wal` / `-shm` | WAL sidecar files. **The database is not a single `.db` file** |
| `player_lang.yml` | Player language preferences |
| `messages/{zh,en}.yml` | Language files |
| `words/*.yml` | Word banks |
| `player_scenarios/<uuid>.yml` | Personal AI scenarios |
| `exports/reports/*.txt` | Reports from `/lea report` |
| `exports/diagnose-*.txt` | Diagnostics from `/lea diagnose save` |
| `audio/*.wav` | TTS cache (not played in the current version) |
| `backups/*.bak` | Automatic database backups, keeping 7 by default |

**What the database holds** (23 tables; `schema.sql` is authoritative):

| Data | Table | Sensitivity |
|---|---|---|
| Player UUID + **player name** | `player_data`, `player_profiles` | Personal information |
| **Full AI conversation text** | `ai_chat_history.content` | **High** — complete back-and-forth with the AI |
| Player messages (cheers) | `social_feed` and related | Medium — visible to other players |
| Progress, answers, achievements, coins, seasons, classes | Remaining tables | Low — aggregate learning data |

**No table stores player passwords or email addresses.** The plugin does not collect them.
Player names and UUIDs come from Minecraft itself.

> Reports and in-game output do contain player names and learning data — that is the tool's purpose.
> Review an exported report before pasting it into a public issue.

---

## Hardening recommendations

- **On a multi-server network, always set `proxy.inbound-secret`.** Generate it with
  `openssl rand -hex 32` and use the same value on every sub-server. Leaving it empty lets any player
  with a modified client forge cross-server broadcasts and achievement unlocks.
- **All sub-servers must share one JVM timezone.** This is not cosmetic: differing day boundaries
  let check-in be claimed twice on the same calendar day (real double payouts). Pin it with
  `-Duser.timezone=<IANA name>`, **before `-jar`**.
  See [`DEPLOYMENT_WIKI.md` 5.9](DEPLOYMENT_WIKI.md#59-多子服时区一致性硬性前提).
- **Treat `ai_config.yml` as a password.** It is not in version control; keep it out of any backup
  upload flow too.
- **Decide deliberately before enabling AI chat.** It sends player input to a third party and gives
  players no in-game notice. If you serve minors, say so in your server rules or turn the module off.
- **The TTS VoiceRSS key lives in a URL**, making it more exposed to log leakage. Skip TTS if you do
  not need pronunciation.
- **If the dashboard must be internet-facing**: use a strong password, narrow `ip-whitelist`, and
  ideally put it behind a reverse proxy with TLS.
- **`/lea export` and `/lea report` write player data to disk.** Grant them only to people who can
  already reach the server's files.
- **Run the server as an account that cannot read other users' files.** The plugin writes to
  `plugins/LetsLearnEnglish/` with ordinary plugin permissions and cannot protect files from a
  misconfigured host.
- **Back up regularly.** The plugin backs up automatically, but `playerdata.db` is in WAL mode —
  for a manual backup use `PRAGMA wal_checkpoint(TRUNCATE)` plus `.backup`; a plain `cp` misses
  unmerged transactions. See the SQLite maintenance section of the README.

---

## Known security debt in this project (stated plainly)

This section exists not to discourage reports but to avoid misleading security researchers:

1. **The `ai_config.yml` API key is stored in plaintext.** No encryption, no OS keyring integration.
   This matches most Minecraft plugins, but it means anyone who can read that file has the key.
2. **Players are not told that AI chat leaves the server.** See above. That is a product decision
   gap rather than an implementation bug, but it is the weakest point in this plugin's privacy posture.
3. **`ai_chat_history` stores full conversation text unencrypted.** Leaking the database file means
   leaking conversation content.
