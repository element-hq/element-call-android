#!/usr/bin/env bash
#
# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.
#
# Downloads the call screen's strings from the element-call Localazy project into each module's
# values/localazy.xml, and with --all-languages its values-*/translations.xml. The strings are written
# in Localazy's dashboard, in the project's mobile.json, which Element Call iOS downloads too; nothing
# here uploads. See CONTRIBUTING.md, "Strings".

set -euo pipefail

usage() {
    cat >&2 <<'USAGE'
Usage: tools/localazy/download-strings.sh [--all-languages]

  --all-languages   Download every language, not only English. The weekly translations workflow
                    passes it; adding a string needs only English.
USAGE
    exit 64
}

ALL_LANGUAGES="no"

while [ $# -gt 0 ]; do
    case "$1" in
        --all-languages)
            ALL_LANGUAGES="yes"
            shift
            ;;
        *)
            usage
            ;;
    esac
done

cd "$(dirname "$0")/../.."

if [ "$ALL_LANGUAGES" = "yes" ]; then
    # Cleared first, so a language Localazy no longer has leaves the library rather than lingering
    # with whatever it last had.
    find call -path '*/src/main/res/values-*/translations.xml' -delete
    find call -path '*/src/main/res/values-*' -type d -empty -delete
    localazy download all
else
    localazy download
fi
