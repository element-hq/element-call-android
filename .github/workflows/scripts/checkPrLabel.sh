#!/bin/bash

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

# Fails unless $LABELS, the pull request's labels as a JSON array, holds exactly one pr- label that
# .github/release.yml knows.

set -euo pipefail

ALLOWED=$(grep -oE '^[[:space:]]*- pr-[a-z]+' .github/release.yml | sed -E 's/^[[:space:]]*- //')
FOUND=$(jq -r '.[] | select(startswith("pr-"))' <<<"${LABELS:-[]}")
COUNT=$(grep -c . <<<"$FOUND" || true)

if [ "$COUNT" -eq 0 ]; then
    echo "::error::Add exactly one pr- label from .github/release.yml: $(echo $ALLOWED)"
    exit 1
fi
if [ "$COUNT" -gt 1 ]; then
    echo "::error::Keep exactly one pr- label, found: $(echo $FOUND)"
    exit 1
fi
if ! grep -qxF "$FOUND" <<<"$ALLOWED"; then
    echo "::error::$FOUND is not in .github/release.yml, use one of: $(echo $ALLOWED)"
    exit 1
fi
echo "Release notes label: $FOUND"
