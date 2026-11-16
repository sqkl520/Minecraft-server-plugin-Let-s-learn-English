# Contributing to Let's Learn English!

Thanks for your interest. This is a single-maintainer hobby project, but pull requests
and detailed bug reports are genuinely welcome.

## Reporting a bug

Open an issue using the **Bug report** template. Please include:

- The **exact** plugin version (`/lea info` prints it in-game)
- Your server software and version (e.g. Paper 1.21.11 build 132)
- Your Java version (`java -version`)
- Steps to reproduce — what you clicked / typed, in order
- Expected vs actual behaviour
- Any relevant console output (`/lea diagnose save` is the fastest way to get this)

**Which JAR were you running?** There are two builds covering different server ranges.
If you are on MC 26.x you must use the `-mc26` build. See the README table.

## Security issues

Do **not** open a public issue for security problems. See [SECURITY.md](SECURITY.md).

## Building from source

Requirements:

- **JDK 21** for the standard build (MC 1.21.4 – 1.21.11)
- **JDK 25** only if you also want to build the 26.x variant

```bash
# Standard build → target/Let's Learn English!-<version>.jar
mvn clean package

# 26.x build (requires JDK 25) → target/Let's Learn English!-<version>-mc26.jar
mvn clean package -Pmc26
```

If `mvn` reports `invalid target release: 21`, your `JAVA_HOME` points at an older JDK.
Set it explicitly:

```bash
export JAVA_HOME=/path/to/jdk-21
```

## Running the tests

```bash
mvn test
```

The suite is around 1300 tests and takes a few minutes. It includes tests that use a
**real SQLite file database** and tests that assert on **source structure** (they read
`src/main/java/...` as text). Those structural tests exist because some defects — index
definitions, emoji glyph coverage, pause/ordering guarantees — are invisible to
behavioural tests.

If you add a fix, please add a test that **fails before your change**. A test that passes
both before and after is not proving anything.

## What gets merged

- Bug fixes with a test.
- Features that fit the plugin's scope: server-side, zero client installation required.
- Translation improvements — all user-facing text lives in
  `src/main/resources/messages/{zh,en}.yml` and the two files must keep an identical key set.
- Word bank corrections. The banks are plain YAML; wrong translations, pinyin, or example
  sentences are real bugs and easy to fix.

## What probably won't get merged

- Rewrites of the storage layer, the AI integration, or the scheduler abstraction without
  a prior issue discussing it. These have specific correctness constraints documented in
  the code and are easy to break from the outside.
- Adding dependencies. The JAR tries to stay self-contained; every new transitive
  dependency is a reason for someone's server not to start.

## Commit messages

`type(scope): what changed and why`. Keep the "why" — it is the part that survives.

## Licensing

This project is GPL-3.0-only. By submitting a pull request you agree to license your
contribution under the same terms. If you are contributing on behalf of an employer,
please make sure you have the right to do so.
