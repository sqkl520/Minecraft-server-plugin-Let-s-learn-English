[简体中文](CONTRIBUTING.md) | English

# Contributing guide

## 1. What this project is, stated plainly

This is a **personal project**: an English-learning plugin for Minecraft servers (Paper family,
Java 21, GPL-3.0-only), built by a high-school first-year student with AI assistance (vibe coding)
and ground down bit by bit on a real server.

Three honest facts up front, so your time is not wasted:

- **No response-time promise.** School, exams and being stuck on a bug can all mean weeks without looking at Issues.
- **No promise that "I'll build this feature".** Direction is set by the author; a genuinely good idea in an Issue may still not get built.
- **But PRs are read carefully.** A PR with tests and a clear motivation has a much higher chance than a "one-line change".

If you can accept those three points, read on.

## 2. Reporting a problem

**Search the [Issues](https://github.com/sqkl520/Minecraft-server-plugin-Let-s-learn-English/issues) first
(closed ones included)** — a question like "`/le` does nothing" or "AI never replies" has likely been asked before.

Please include all five of these in a bug report; missing one usually costs an extra round trip:

1. **Server version and build number** (e.g. `Paper 1.21.11 build 132`, `Purpur 1.21.4 build 2321`)
2. **Plugin version** (first line of `/lea info`, or the jar filename; formatted like `1.0.7`)
3. **Which build you installed** — this project ships two jars from the same source, with different compile targets:
   - MC 1.21.4 – 1.21.11 → `Let.s.Learn.English.-<version>.jar` (Java 21+)
   - MC 26.1+ → `Let.s.Learn.English.-<version>-mc26.jar` (Java 25+)

   The standard build on 26.x gives `Unsupported class file major version`, and vice versa. See the JAR table in the README.
4. **Steps to reproduce** — which command, in what order, after how long
5. **Relevant logs.** The fastest way is in-game:

   ```
   /lea diagnose save
   ```

   It writes a self-check (config, database, dependencies, permissions) to
   `plugins/LetsLearnEnglish/exports/diagnose-<timestamp>.txt` and echoes the path in chat, ready
   to paste. If there is a stack trace, paste it in full.

If a *number* looks wrong, also say "I expected X, it shows Y" — the value you expected is often more useful than the log.

**Before pasting, remove these**: `ai_config.yml`'s `api-key`, `tts.voicerss-api-key`,
`proxy.inbound-secret`, `statistics-server.auth.password`, and any player names.

## 3. Submitting a Pull Request

1. Fork this repository, create a branch off `main` (e.g. `fix/wrongbook-holder-guard`), and keep the change focused — one PR, one thing.
2. Make sure the build passes locally (see below).
3. Open a PR against `main` and fill in the description and testing notes per the PR template.
4. CI (`.github/workflows/build.yml`) runs `mvn verify` plus static gates automatically; PRs with failing CI will not be merged.

## 4. Build requirements

You need **JDK 21** and **Maven 3.9+** (no Maven Wrapper in this repo — use your installed `mvn`).
Run from the repository root (where `pom.xml` lives):

```bash
mvn verify                     # full validation: compile + tests + JaCoCo coverage gate (the standard command)
mvn clean package              # package into target/
mvn clean package -DskipTests  # quick package skipping tests — do not use this to validate a change
mvn test                       # run tests only
```

**Building the 26.x jar additionally needs JDK 25**:

```bash
mvn clean package -Pmc26       # produces target/Let's Learn English!-<version>-mc26.jar
```

- The target platform is **Paper 1.21.4+**; Spigot / Purpur / Folia are not guaranteed (the plugin
  uses Paper-exclusive APIs: Adventure `net.kyori` components, `Server#getMinecraftVersion`).
  Changes must be consistent with this fact.
- `mvn verify` is not the same as `mvn package`: `jacoco:check` is bound to the `verify` phase, so
  packaging with `-DskipTests` **also bypasses the coverage gate**.
- After any code change, `mvn clean package` or `mvn verify` must compile successfully.
- If you get `invalid target release: 21`, your `JAVA_HOME` points at an older JDK. Set it explicitly:

  ```bash
  export JAVA_HOME=/path/to/jdk-21
  ```

- When adding or changing commands, features, or permissions, update `README.md` and
  `README-EN.md` accordingly, and append a one-line entry under `[Unreleased]` in `CHANGELOG.md`.

### Self-checks before you push (important)

The repo ships several gates. CI runs them and you can run them locally — **please run at least
these after a change**:

```bash
node scripts/audit-i18n-keys.js                          # i18n keys referenced in code must exist in both zh.yml and en.yml
node scripts/gen-words.js check src/main/resources/words/beginner.yml      # word-bank structure check
node scripts/gen-words.js check src/main/resources/words/intermediate.yml
node scripts/gen-words.js check src/main/resources/words/advanced.yml
node scripts/check-word-collisions.js                    # definition-collision scan (advisory, not blocking yet)
```

> `gen-words.js check` accepts **one** file argument; extra arguments are silently ignored, so loop over the files.

**Tests matter more than the code change itself.** Every fix in this project is required to come
with an assertion that goes red *before* the fix — otherwise it is not fixed, just edited. If you
can show that your test actually catches the bug, your PR is very likely to land.

This rule is not fussiness: several tests in this repo once passed both before and after a change,
and only mutation checking revealed they were asserting on a framework stub instead of their own
logic. The usual false-green shapes are self-proving stubs, untouched gates, a mismatched exception
type standing in for the real one, half coverage, and a count satisfied by a different code path.
"I added a test" is not evidence; "I broke this line and the test went red" is.

## 5. What gets merged

- **Bug fixes with a test.**
- **Features that fit the plugin's scope**: server-side implementation, zero client installation, no new mandatory dependencies.
- **Translation improvements** — all user-facing text lives in
  `src/main/resources/messages/{zh,en}.yml`, and the two files must keep an identical key set
  (checked by `audit-i18n-keys.js`).
- **Word bank corrections.** The banks are plain YAML. Wrong definitions, phonetics, or example
  sentences are real bugs and easy to fix. Run `gen-words.js check` after editing.
- **Documentation improvements** — including keeping both READMEs in sync.

## 6. What probably won't get merged

- **Rewrites of the storage layer, the AI integration, or the scheduler abstraction without a prior
  issue discussing it.** These carry specific correctness constraints (single connection with no
  pool, never blocking the main thread on a DB read, async back-fill needing a holder guard…), and
  they are easy to break from the outside.
- **Adding dependencies.** The JAR is deliberately self-contained — the MySQL driver is already
  shaded in, and every new transitive dependency is a reason for someone's server not to start.
- **Special-casing for one private server fork.**
- **"Temporary" fixes that route around the tests.**

## 7. Commit messages

`type(scope): what changed and why`. Keep the "why" — it is the only part that survives three months.

```
fix(wrongbook): async back-fill reopens the GUI after the player closes it with ESC

The back-fill only had isOnline + token guards; it was missing "the player closed the
inventory but is still online". When data arrived, the openInventory call at the end of
render reopened the inventory the player had just closed. The guard now compares
PluginGuiHolder by reference instead of comparing titles.
```

## 8. Behavior expectations

- Be kind and stay on topic; Chinese or English are both fine.
- Before suggesting features, check the README's "What it is" section and section 6 above — some things are deliberately out of scope.
- The maintainer does not promise response times and does not guarantee that any PR will be merged.
- Security issues follow [SECURITY.md](SECURITY.md) — **do not open a public issue**.

## 9. Licensing

This project is released under **GPL-3.0-only** (see [`LICENSE`](LICENSE)). By submitting a pull
request you agree to license your contribution under the same terms. If you contribute on behalf of
someone else (e.g. your employer), make sure you are authorized to do so.

There is **no CLA**: your contribution remains your copyright, licensed under GPL-3.0-only.
