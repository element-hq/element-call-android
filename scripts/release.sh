#!/bin/bash

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

# Validates a release without touching any remote, so it can be rehearsed locally and is what
# release.yml runs before publishing. See RELEASING.md.
#
#   ./scripts/release.sh v0.1.0-rc.1

set -euo pipefail

TAG=${1:?usage: scripts/release.sh vX.Y.Z[-rc.N]}
VERSION=${TAG#v}
cd "$(dirname "$0")/.."

VERSION_NAME=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
if [[ "$VERSION" != "$VERSION_NAME" ]]; then
  echo >&2 "Tag $TAG does not match VERSION_NAME=$VERSION_NAME in gradle.properties."
  exit 1
fi
if [[ "$VERSION" == *-SNAPSHOT ]]; then
  echo >&2 "$VERSION is a snapshot, not a release."
  exit 1
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo >&2 "The working tree is not clean."
  exit 1
fi
if ! grep -q "^## $VERSION" CHANGES.md; then
  echo >&2 "CHANGES.md has no '## $VERSION' section."
  exit 1
fi
if [[ ! -f rtc/local/matrixrtc-release.aar ]]; then
  echo >&2 "No core AAR at rtc/local/matrixrtc-release.aar. Run ./tools/rtc/fetch-rust-rtc (docs/local_stack.md, layer 1)."
  exit 1
fi

echo "Building, testing and checking $VERSION"
./gradlew build runQualityChecks "$@"

echo "Publishing $VERSION to the local Maven repository"
./gradlew publishToMavenLocal

echo "Building the minified consumer against the published artifacts"
./gradlew -p tests/consumer :app:assembleRelease -PelementCallVersion="$VERSION"

echo
echo "Validated $TAG. Nothing has been published beyond ~/.m2."
echo "To release to Maven Central (see RELEASING.md for the prerequisites):"
echo "  ./gradlew publishAndReleaseToMavenCentral -PRELEASE_SIGNING_ENABLED=true --no-configuration-cache"
