# Screenshot testing

<!--- TOC -->

* [Overview](#overview)
* [Setup](#setup)
* [Recording](#recording)
* [Verifying](#verifying)
* [Contributing](#contributing)

<!--- END -->

## Overview

- Screenshot tests record the content of a rendered composable and verify subsequent runs to check if it renders
  differently.
- This library uses [Paparazzi](https://github.com/cashapp/paparazzi) to render, record and verify composables, the
  way Element X Android does. Every `internal` composable annotated `@PreviewsDayNight` in `call/ui` is a
  screenshot test, thanks to [ComposablePreviewScanner](https://github.com/sergio-sastre/ComposablePreviewScanner):
  one day and one night PNG per preview, one per value of its `PreviewParameterProvider`.
- The tests live in `tests/uitests`, a module of its own so that Paparazzi and layoutlib stay off the published
  modules and the PNGs out of their source trees. They render with the default `ElementCallStyle`; a host's own
  style is its own screenshot suite's business.
- The screenshot verification occurs on every pull request as part of the `tests.yml` workflow.

## Setup

- Install Git LFS through your package manager of choice (`brew install git-lfs` | `yay -S git-lfs`).
- Install the Git LFS hooks into the project.

```shell
# with element-call-android as the current working directory
git lfs install --local
```

If installed correctly, `git push` and `git pull` will now include LFS content.

## Recording

Recording is done by the GitHub action "Record screenshots" (`.github/workflows/recordScreenshots.yml`), to avoid
differences in the generated PNGs between developers' machines. Either add the `Record-Screenshots` label to the
pull request, or run the workflow by hand on a branch. The action records, commits `Update screenshots` to the
branch and removes the label. The workflow needs the `MATRIX_RTC_AAR_URL` repository variable, like every other
one: the previews render `call/ui`, which links the RTC AAR for its video renderer.

You can still record locally to look at the result, but do not commit what it produces:

```shell
./gradlew recordPaparazziDebug
```

The task deletes `tests/uitests/src/test/snapshots` before recording (the `removeOldSnapshots` task in the root
build file). The images land in the same folder, and are committed to the repository through Git LFS.

## Verifying

```shell
./gradlew verifyPaparazziDebug
```

In the case of failure, Paparazzi writes images to `tests/uitests/build/paparazzi/failures`: the expected and
actual screenshots along with a delta of the two.

## Contributing

- Every main state of a public composable has a preview (`AGENTS.md`, "Previews and screenshots"), so a new state
  is a new screenshot for free.
- After adding or changing a preview, have the screenshots recorded (label or workflow) before asking for review.
- `./tools/git/validate_lfs.sh` checks that every PNG went through Git LFS; the CI runs it too.
