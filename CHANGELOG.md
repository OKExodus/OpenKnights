# Changelog

Every OpenKnights release is listed here, newest first.

Versions have four parts, `major.milestone.fix.build`:

| Part | Goes up when |
| --- | --- |
| major | the game is complete, every mode included (`1.0.0.0`) |
| milestone | a group of systems lands, for example arena and towers |
| fix | a release only fixes problems |
| build | only the packaging changes |

## [Unreleased]

Nothing has been released yet. The first release will be **0.1.0.0**.

### Fixed

- Android compatibility for free top-up requests and Diamond-spending activity tracking.
- Embedded server library conflicts that prevented campaign battles from starting.
- Save-history reads on Android SQLite versions without JSON extension support.
- Android compatibility when claiming main-quest rewards and calculating hidden-training power.
