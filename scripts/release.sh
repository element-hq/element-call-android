#!/usr/bin/env bash

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

# Prepares a release: validates the version, asks GitHub for the release notes the pull request labels
# imply, prepends them to CHANGES.md, stamps VERSION_NAME, then builds, tests, publishes locally and lays
# out the release assets in build/release-assets. It stops there.
#
# This script never touches the remote. It does not commit, tag, push or create a release, so running it
# locally is a real rehearsal rather than an approximation of one: the worst it can do is leave a modified
# CHANGES.md and gradle.properties in the working tree, and build output under build/ and ~/.m2. The
# workflow does the publishing, from exactly these outputs. Same shape as element-call-ios. See RELEASING.md.

set -euo pipefail

usage() {
    cat >&2 <<'USAGE'
Usage: scripts/release.sh <version> [--notes-out <path>] [--dry-run] [--skip-build]

  <version>       The version to release, bare semver: 0.1.0, or 0.2.0-rc.1 for a prerelease. The tag is v<version>.
  --notes-out     Where to write the generated release notes (default: release-notes.md, gitignored).
  --dry-run       The caller will not publish: says so in the output, changes nothing else.
  --skip-build    Stop after the changelog and the version stamp, without the Gradle build and the
                  release assets. For a quick local look at the notes; never what release.yml runs.

Gradle arguments come from the CI_GRADLE_ARG_PROPERTIES environment variable, as in every workflow.
USAGE
    exit 64
}

VERSION=""
NOTES_OUT="release-notes.md"
DRY_RUN="no"
SKIP_BUILD="no"

while [ $# -gt 0 ]; do
    case "$1" in
        --notes-out)
            [ $# -ge 2 ] || usage
            NOTES_OUT="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN="yes"
            shift
            ;;
        --skip-build)
            SKIP_BUILD="yes"
            shift
            ;;
        -h | --help)
            usage
            ;;
        -*)
            echo "error: unknown option $1" >&2
            usage
            ;;
        *)
            [ -z "$VERSION" ] || usage
            VERSION="$1"
            shift
            ;;
    esac
done

[ -n "$VERSION" ] || usage

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

CHANGELOG="CHANGES.md"
PROPERTIES="gradle.properties"
ASSETS="build/release-assets"
DIST="build/dist"

# The changelog is rewritten through a temporary file, so a failure mid-write leaves the original intact,
# but it would leave the temporary behind, untracked and not ignored, ready to be swept into an unrelated
# commit by a later `git add -A`.
trap 'rm -f "$CHANGELOG.tmp" "$PROPERTIES.tmp" "$NOTES_OUT.clean"' EXIT

fail() {
    # ::error:: makes the message the annotation GitHub shows against the step, so a refusal names its own
    # cause on the run summary rather than only in the log.
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        echo "::error::$1"
    else
        echo "error: $1" >&2
    fi
    exit 1
}

# --- 1. The version is bare semver; the tag carries a v ---------------------------------------------------
#
# The tag is v<version>, as matrix-rust-rtc's and as the Ivy pattern a host declares expects
# (`v[revision]/[artifact]-[revision]...`, README "Consuming a release"). The input is the bare version,
# which is also what VERSION_NAME holds and what a host writes in its catalog.
case "$VERSION" in
v[0-9]*) fail "Give the bare version, ${VERSION#v}: the script adds the v to the tag itself." ;;
esac

if ! [[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$ ]]; then
    fail "'$VERSION' is not a semantic version. Expected MAJOR.MINOR.PATCH, optionally followed by a prerelease such as -rc.1."
fi

TAG="v$VERSION"
PRERELEASE="no"
case "$VERSION" in
*-*) PRERELEASE="yes" ;;
esac

# --- 2. The tag does not exist, and sorts above the newest one in this line of history --------------------
if git rev-parse -q --verify "refs/tags/$TAG" > /dev/null 2>&1; then
    fail "Tag $TAG already exists."
fi

# `--merged HEAD` restricts this to releases in *this* line of history, so a branch cut from the v0.2.0 tag
# to fix the 0.2 line neither sees main's v0.3.0 and refuses 0.2.1 as going backwards, nor gets notes
# generated against a release the fix does not contain. `versionsort.suffix=-` stops git ranking a
# prerelease above its own release (`--sort=-v:refname` otherwise gives `v0.2.0-rc.1, v0.2.0`).
LATEST_TAG="$(git -c versionsort.suffix=- tag --list --merged HEAD --sort=-v:refname \
    | grep -E '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$' \
    | head -n 1 || true)"

if [ -n "$LATEST_TAG" ]; then
    echo "Newest release tag in this history: $LATEST_TAG"
    LATEST_VERSION="${LATEST_TAG#v}"
    # `sort -V` orders a prerelease *after* its release, the same inversion git has, so compare the release
    # parts and let an equal pair through: 0.2.0-rc.2 after 0.2.0-rc.1 is legitimate, and so is 0.2.0 after
    # 0.2.0-rc.1.
    if [ "$(printf '%s\n%s\n' "${LATEST_VERSION%%-*}" "${VERSION%%-*}" | sort -V | head -n 1)" != "${LATEST_VERSION%%-*}" ]; then
        fail "$VERSION sorts below $LATEST_TAG, which is already released in this history. To fix an older line, branch from that line's tag rather than from main."
    fi
else
    echo "No release tag in this history; this is the first."
fi

# The one case the comparison above lets through: a prerelease for a version that already shipped stably.
if [ "$PRERELEASE" = "yes" ] && git rev-parse -q --verify "refs/tags/v${VERSION%%-*}" > /dev/null 2>&1; then
    fail "${VERSION%%-*} has already been released, so $VERSION is a prerelease of the past. Bump the patch or minor instead."
fi

# --- 3. Ask GitHub for the notes the labels imply --------------------------------------------------------
#
# generate-notes is the read-only half of the release API: it accepts a tag that does not exist yet and
# creates nothing, reading the categories from .github/release.yml on its own. That is what lets the tag be
# created last and still contain its own changelog entry.
SHA="$(git rev-parse HEAD)"
REPOSITORY="${GITHUB_REPOSITORY:-element-hq/element-call-android}"
BODY_ARGS=(-f "tag_name=$TAG" -f "target_commitish=$SHA")
if [ -n "$LATEST_TAG" ]; then
    BODY_ARGS+=(-f "previous_tag_name=$LATEST_TAG")
fi

gh api -X POST "repos/$REPOSITORY/releases/generate-notes" "${BODY_ARGS[@]}" --jq .body > "$NOTES_OUT" \
    || fail "Could not generate release notes for $TAG. Locally this needs \`gh auth login\`."

# Not `[ -s ]`: the body always ends with a **Full Changelog** compare link, so a release with nothing in it
# is one with no list items. Since .github/release.yml excludes pr-task, this also catches a release whose
# every merged pull request was so labelled.
if ! grep -qE '^\* ' "$NOTES_OUT"; then
    fail "GitHub generated no changelog entries for $TAG. Either nothing has merged since ${LATEST_TAG:-the start of the history}, or everything that has is labelled pr-task and so excluded from the notes."
fi

echo
echo "--- Release notes -------------------------------------------------------------"
cat "$NOTES_OUT"
echo "-------------------------------------------------------------------------------"
echo

# An unlabelled pull request lands under Others, per the "*" catch-all in .github/release.yml. Nothing
# enforces the label at merge time, so this is where it gets noticed; the notes are generated now rather
# than at merge, so relabelling the merged pull request and running again is the whole fix.
if grep -q '^### Others' "$NOTES_OUT"; then
    echo "note: there are entries under 'Others'. Those pull requests are missing a pr- label."
    echo "      Label them and run again; the notes are generated now, so a late label still lands."
    echo
fi

# --- 4. Close the Unreleased section and open a fresh one -------------------------------------------------
#
# Anything hand-written under Unreleased is carried into the released section and kept *above* the
# generated list: a change a host has to act on should not sit below thirty lines of dependency bumps. The
# generated part gets the same cleanup element-x-android applies, so the two changelogs read alike: the
# notes' own "## What's Changed" becomes a level-three heading under ours, and its level-three category
# headings become plain separators.
if ! grep -qE '^## Unreleased[[:space:]]*$' "$CHANGELOG"; then
    fail "$CHANGELOG has no '## Unreleased' heading, so there is nowhere to record $VERSION. Restore it before releasing."
fi

sed -e 's/<!--.*-->//' -e 's/^### /\n/' -e 's/^## /### /' "$NOTES_OUT" > "$NOTES_OUT.clean"

awk -v version="$VERSION" -v released="$(date -u +%Y-%m-%d)" -v notes="$NOTES_OUT.clean" '
function flush() {
    print "## Unreleased"
    print ""
    print "_Nothing yet._"
    print ""
    print "## " version " - " released
    print ""
    # Drop the placeholder wherever it sits: someone adding a note by hand is as likely to leave it above
    # as to replace it. Then trim blank lines from both ends of what is left.
    gsub(/(^|\n)_Nothing yet\._[[:space:]]*(\n|$)/, "\n", carried)
    gsub(/^\n+|\n+$/, "", carried)
    if (carried != "") {
        print carried
        print ""
    }
    while ((getline line < notes) > 0) print line
    close(notes)
    print ""
}
state == "" {
    if ($0 ~ /^## Unreleased[[:space:]]*$/) { state = "unreleased"; next }
    print
    next
}
state == "unreleased" {
    if ($0 ~ /^## /) { flush(); state = "done"; print; next }
    carried = carried $0 "\n"
    next
}
{ print }
END { if (state == "unreleased") flush() }
' "$CHANGELOG" > "$CHANGELOG.tmp"

mv "$CHANGELOG.tmp" "$CHANGELOG"
rm -f "$NOTES_OUT.clean"
echo "Recorded $VERSION in $CHANGELOG and opened a fresh Unreleased section."

# --- 5. Stamp VERSION_NAME --------------------------------------------------------------------------------
#
# The one place the build names its version, written here rather than by hand so that a pull request never
# carries a version. It lands in the commit the release tags, next to the changelog, so the tagged tree
# publishes as the version it is tagged with. Verified afterwards: a silent no-op would ship a tag whose
# artifacts claim to be its predecessor, which is worse than a failed release.
if ! grep -qE '^VERSION_NAME=' "$PROPERTIES"; then
    fail "$PROPERTIES has no VERSION_NAME line to stamp."
fi
sed -e "s/^VERSION_NAME=.*$/VERSION_NAME=$VERSION/" "$PROPERTIES" > "$PROPERTIES.tmp"
mv "$PROPERTIES.tmp" "$PROPERTIES"
grep -qxF "VERSION_NAME=$VERSION" "$PROPERTIES" || fail "Could not stamp $VERSION into $PROPERTIES."
echo "Stamped VERSION_NAME=$VERSION into $PROPERTIES."

# Consumed by the workflow: the tag to push, whether the release is a prerelease, where the notes are.
if [ -n "${GITHUB_OUTPUT:-}" ]; then
    {
        echo "version=$VERSION"
        echo "tag=$TAG"
        echo "prerelease=$PRERELEASE"
        echo "notes=$NOTES_OUT"
    } >> "$GITHUB_OUTPUT"
fi

if [ "$SKIP_BUILD" = "yes" ]; then
    echo "Prepared $VERSION (prerelease: $PRERELEASE) without building. Nothing has been pushed."
    exit 0
fi

# --- 6. Build, test, publish locally, lay out the assets --------------------------------------------------
#
# The stamped tree is what gets built, so the artifacts carry the version the tag will. Gradle arguments
# come from CI_GRADLE_ARG_PROPERTIES, unquoted on purpose: it is a list of flags, or nothing.
GRADLE_ARGS=${CI_GRADLE_ARG_PROPERTIES:-}

if [ ! -f rtc/local/matrixrtc-release.aar ]; then
    fail "No core AAR at rtc/local/matrixrtc-release.aar. Run ./tools/rtc/fetch-rust-rtc (docs/local_stack.md, layer 1)."
fi

# shellcheck disable=SC2086
echo "Building, testing and checking $VERSION"
./gradlew build runQualityChecks $GRADLE_ARGS

echo "Publishing $VERSION to the local Maven repository"
./gradlew publishToMavenLocal $GRADLE_ARGS

echo "Building the minified consumer against the published artifacts"
./gradlew -p tests/consumer :app:assembleRelease -PelementCallVersion="$VERSION" $GRADLE_ARGS

echo "Laying out the release assets"
# A Maven layout under build/dist, flattened into one directory: the GitHub release's assets, which a host
# resolves through an Ivy repository (README, "Consuming a release"). Maven's own sidecar checksums are
# dropped in favour of one SHA256SUMS. The core is not among them: the core's own release carries it.
rm -rf "$DIST" "$ASSETS"
./gradlew publishAllPublicationsToDistRepository $GRADLE_ARGS
mkdir -p "$ASSETS"
find "$DIST/io/element/android" -type f -path "*/$VERSION/*" \
    ! -name 'maven-metadata*' ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' \
    -exec cp {} "$ASSETS/" \;
if command -v sha256sum > /dev/null; then
    (cd "$ASSETS" && sha256sum -- * > SHA256SUMS)
else
    (cd "$ASSETS" && shasum -a 256 -- * > SHA256SUMS)
fi
ls "$ASSETS"

echo "Building the minified consumer against the release assets, laid out as a host resolves them"
./gradlew -p tests/consumer :app:assembleRelease -PelementCallVersion="$VERSION" -PelementCallDistDir="$PWD/$ASSETS" $GRADLE_ARGS

echo
if [ "$DRY_RUN" = "yes" ]; then
    echo "Dry run: prepared $VERSION (prerelease: $PRERELEASE), assets in $ASSETS. Nothing has been pushed."
else
    echo "Prepared $VERSION (prerelease: $PRERELEASE), assets in $ASSETS. Nothing has been pushed; release.yml does that."
fi
