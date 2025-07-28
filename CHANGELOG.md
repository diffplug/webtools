# Webtools releases

## [Unreleased]
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
