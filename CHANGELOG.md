# Webtools releases

## [Unreleased]

## [1.2.4] - 2025-08-16
### Fixed
- Add the node installation dir onto `PATH` to try to fix a Heroku issue.

## [1.2.3] - 2025-08-07
### Fixed
- Fixup some requirements for Gradle 9.

## [1.2.2] - 2025-08-07
### Fixed
- The workaround worked on clean builds, but tried to copy-over-existing on not-clean-builds. Fixed now.

## [1.2.1] - 2025-08-07
### Fixed
- Workaround for a Windows issue afflicting modern node versions.
  - Cannot run program "...\build\node-install\node\npm.cmd" ...: CreateProcess error=2, The system cannot find the file specifie

## [1.2.0] - 2025-07-28
### Added
- Task like `npm run lint:fix` get turned into `npm_run_lint-fix` (so the colons don't screw up Gradle)
- When `npm run` commands fail, they dump their console output as a Gradle error.

## [1.1.0] - 2024-08-04
### Added
- Make it possible to set environment variables for npm run tasks. ([#2](https://github.com/diffplug/webtools/pull/2))

## [1.0.0] - 2024-07-05
### Fixed
- Fixed order of npm version detection.

## [0.1.0] - 2024-07-05

First release.
