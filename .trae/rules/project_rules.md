# Project Rules - Let's Learn English!

## Compilation
After every code change task, always run the following commands to compile and preserve the build:
```
& "D:\maven\apache-maven-3.9.9\bin\mvn.cmd" clean package
```
Working directory: `D:\Server\MCServers\Server plugin\Let's learn English!\Minecraft-server-plugin-Let-s-learn-English--1`

Then copy the JAR to `versions/` folder to keep old builds:
```
& "D:\maven\apache-maven-3.9.9\bin\mvn.cmd" clean package; Copy-Item "target\Let's Learn English!-*.jar" "versions\" -Force
```
Working directory: `D:\Server\MCServers\Server plugin\Let's learn English!\Minecraft-server-plugin-Let-s-learn-English--1`

This ensures every build is preserved by version number and never lost.

## Version & Changelog
After every code change task, update the changelog immediately (before compilation):
1. Update `D:\Server\MCServers\Server plugin\Let's learn English!\Minecraft-server-plugin-Let-s-learn-English--1\CHANGELOG.md`
   - Lazy ref: Use `CHANGELOG.md` to update (in project root)
   - Append changes under the current version's `### Added` / `### Fixed` / `### Changed` / `### Refactored` section
   - Keep each entry concise (one line per change)
   - Follow the existing format strictly

Only bump the version number when the user explicitly says "更新版本号" (update version):
2. Bump the version number in `pom.xml` (line 9: `<version>X.X.X</version>`)
   - Patch version (1.0.X) for bug fixes
   - Minor version (1.X.0) for new features
3. Add a new `## [new-version] - YYYY-MM-DD` section at the top of `CHANGELOG.md`