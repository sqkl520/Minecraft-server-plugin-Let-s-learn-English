## What changed

<!-- One or two sentences. Link the issue if there is one. -->

## Why

<!-- The part that survives six months from now. What breaks or is wrong without this? -->

## How it was verified

<!--
  Check what applies and say how. A fix without a test that fails before it
  is not proven — please include one where the defect is testable.
-->

- [ ] `mvn test` passes locally (state the number of tests)
- [ ] Added a test that **fails before** this change
- [ ] Manually tested in-game on: <!-- e.g. Paper 1.21.11 / Java 21 -->

## Checklist

- [ ] I built with the correct JDK (`mvn package` needs 21; `-Pmc26` needs 25)
- [ ] If this changes behaviour, config keys, or commands, I updated the docs in the same PR
- [ ] No secrets, tokens, or real player data are included
- [ ] My contribution is licensed under GPL-3.0-only, same as the project
