#!/usr/bin/env bash

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

# List of tasks to run before creating a PR, to limit the risk of getting rejected by the CI.
# Can be used as a git hook if you want.

# exit when any command fails
set -e

# Refuse the two things a local-stack switch must never leave behind (see docs/local_stack.md).
if git ls-files | grep -q '\.aar$'; then
    echo "❌ A .aar file is tracked by git. The matrix-rust-rtc AAR is a local file, see docs/local_stack.md." >&2
    exit 1
fi
if git grep -n -E 'elementCallAndroidDir|elementCallLocalVersion' -- '*.properties' '*.gradle.kts' | grep -v -E '^(docs/|AGENTS.md|README.md|tools/quality/check.sh)' ; then
    echo "❌ A local-stack property is committed. Keep it in ~/.gradle/gradle.properties, see docs/local_stack.md." >&2
    exit 1
fi

# Every source file carries the one-line 2026 notice and nobody else's. Konsist checks the Kotlin files;
# this covers the XML resources, the scripts and the rules files too. `tools/quality/fix_headers.py` fixes a file.
if git grep -l "New Vector" -- . ':!tools/quality/check.sh' ':!tools/quality/fix_headers.py' ':!tests/konsist/**/KonsistLicenseTest.kt' >/dev/null; then
    echo "❌ A file names another copyright holder. Run tools/quality/fix_headers.py." >&2
    git grep -l "New Vector" -- . ':!tools/quality/check.sh' ':!tools/quality/fix_headers.py' ':!tests/konsist/**/KonsistLicenseTest.kt' >&2
    exit 1
fi
if git ls-files '*.kt' '*.kts' '*.xml' '*.sh' '*.py' '*.pro' | xargs grep -L "Copyright (c) 2026 Element Creations Ltd." | grep . ; then
    echo "❌ The files above lack the copyright notice. Run tools/quality/fix_headers.py." >&2
    exit 1
fi

# Check ktlint, detekt, Konsist and the boundaries first
./gradlew runQualityChecks

# Build, test and check the project, with warning as errors
./gradlew check -PallWarningsAsErrors=true
