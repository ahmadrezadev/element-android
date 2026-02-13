#!/usr/bin/env bash

# Copyright 2020-2024 New Vector Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
# Please see LICENSE files in the repository root for full details.

# Template prevent from upgrading Android Studio, so this script de configure the template
echo "Un-configure Mana Template..."
if [ -z ${ANDROID_STUDIO+x} ]; then ANDROID_STUDIO="/Applications/Android Studio.app/Contents"; fi

rm "${ANDROID_STUDIO%/}/plugins/android/lib/templates/other/ManaFeature"
rm -r "${ANDROID_STUDIO%/}/plugins/android/lib/templates"
