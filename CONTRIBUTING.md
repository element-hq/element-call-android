# Contributing to Element Call Android

<!--- TOC -->

* [Before you start](#before-you-start)
* [Etiquette](#etiquette)
* [Submitting a pull request](#submitting-a-pull-request)
* [Strings](#strings)
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
  observe, which keeps it out of the release notes. CI checks it.
- Add the `Record-Screenshots` label when a Composable preview is added or changed, so that CI records the screenshots.
- Screenshots or a video for visual changes.

## Strings

The call screen's text comes from the [element-call Localazy project](https://localazy.com/p/element-call), from the
file `mobile.json`, which Element Call iOS downloads too. Nothing here uploads to it.

1. Add the string in Localazy's dashboard, to `mobile.json`. The key starts with `element_call_` and ends in `_android`
   if only Android uses it. Placeholders are numbered: `%1$s`, `%1$d`.
2. Run `./tools/localazy/download-strings.sh` (needs the [Localazy CLI](https://localazy.com/docs/cli/installation)).
   It writes each module's `values/localazy.xml`.
3. Use the `R.string` in code, never a literal.

Never edit `localazy.xml` or `translations.xml` by hand. Translations arrive in a pull request the Translations workflow
opens every Monday.

## Code quality

Run `./tools/quality/check.sh` before opening a pull request. It runs what the CI runs: Konsist, lint, detekt,
ktlint, the no-Compose check, the docs TOC check, then the build and the tests with warnings as errors.

- `./gradlew ktlintFormat` fixes most formatting findings.
- `./gradlew generateDocsToc` regenerates the tables of contents of the Markdown files.

## Tests

Every behaviour change comes with a unit test; every new composable state comes with a preview, which is what the
screenshot tests record. See the "Tests" and "Previews and screenshots" sections of [AGENTS.md](AGENTS.md).
