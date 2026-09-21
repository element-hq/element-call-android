# Contributing to Element Call Android

<!--- TOC -->

* [Before you start](#before-you-start)
* [Etiquette](#etiquette)
* [Submitting a pull request](#submitting-a-pull-request)
* [Code quality](#code-quality)
* [Tests](#tests)

<!--- END -->

## Before you start

This library follows the conventions of [Element X Android](https://github.com/element-hq/element-x-android), on
purpose. Read [AGENTS.md](AGENTS.md) first: it lists the module boundaries, the commands and the rules the CI
enforces. The build needs a locally built `matrix-rust-rtc` AAR, see [docs/local_stack.md](docs/local_stack.md).

## Etiquette

- Significant changes are discussed in an issue before a pull request is opened.
- Keep the pull request template. Keep pull requests small: 500 production lines is the hard limit, and smaller is
  better. Tests do not count.
- Commits have a title and a description; no tiny commits, no massive ones. No history rewrites once a PR is open.
- AI-assisted contributions are welcome within reason: you are responsible for the quality of the change, and the
  description is written by you.

## Submitting a pull request

- Sentence-style title; it becomes the changelog entry, so it says what changes for a user of the library.
- Exactly one `pr-` label from [.github/release.yml](.github/release.yml); `pr-task` for a change no host could
  observe, which keeps it out of the release notes.
- Add the `Record-Screenshots` label when a Composable preview is added or changed, so that CI records the screenshots.
- Screenshots or a video for visual changes.

## Code quality

Run `./tools/quality/check.sh` before opening a pull request. It runs what the CI runs: Konsist, lint, detekt,
ktlint, the no-Compose check, the docs TOC check, then the build and the tests with warnings as errors.

- `./gradlew ktlintFormat` fixes most formatting findings.
- `./gradlew generateDocsToc` regenerates the tables of contents of the Markdown files.

## Tests

Every behaviour change comes with a unit test; every new composable state comes with a preview, which is what the
screenshot tests record. See the "Tests" and "Previews and screenshots" sections of [AGENTS.md](AGENTS.md).
