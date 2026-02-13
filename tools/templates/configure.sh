#!/usr/bin/env bash

# Copyright 2020-2024 New Vector Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
# Please see LICENSE files in the repository root for full details.

echo "Configure Mana Template..."
if [ -z ${ANDROID_STUDIO+x} ]; then ANDROID_STUDIO="/Applications/Android Studio.app/Contents"; fi
{
mkdir -p "${ANDROID_STUDIO%/}/plugins/android/lib/templates/other"
ln -s $(pwd)/ManaFeature "${ANDROID_STUDIO%/}/plugins/android/lib/templates/other"
} && {
  echo "Please restart Android Studio."
}
