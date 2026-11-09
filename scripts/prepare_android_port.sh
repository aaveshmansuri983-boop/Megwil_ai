#!/usr/bin/env bash
set -euo pipefail

ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
WORK="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/megwil-jarvis-android"
ARCHIVE="$ROOT/MEGWIL-additive-features.zip"
PROJECT="$WORK/open-jarvis-main"

if [[ ! -s "$ARCHIVE" ]]; then
  echo "Required source archive not found: $ARCHIVE" >&2
  exit 2
fi
rm -rf "$WORK"
mkdir -p "$WORK"
unzip -q "$ARCHIVE" -d "$WORK"
[[ -d "$PROJECT/app/src/main/java/com/openjarvis" ]] || { echo "Unexpected source archive layout" >&2; exit 3; }

# Keep the Android port isolated from the legacy app at repository root.
find "$PROJECT" -type f \( -name '*.kt' -o -name '*.xml' -o -name '*.gradle' -o -name '*.pro' \) \
  -exec sed -i 's/com\.openjarvis/com.megwil.ai/g' {} +

mkdir -p "$PROJECT/app/src/main/java/com/megwil/ai"
cp -a "$PROJECT/app/src/main/java/com/openjarvis/." "$PROJECT/app/src/main/java/com/megwil/ai/"
rm -rf "$PROJECT/app/src/main/java/com/openjarvis"

if [[ -d "$PROJECT/app/src/test/java/com/openjarvis" ]]; then
  mkdir -p "$PROJECT/app/src/test/java/com/megwil/ai"
  cp -a "$PROJECT/app/src/test/java/com/openjarvis/." "$PROJECT/app/src/test/java/com/megwil/ai/"
  rm -rf "$PROJECT/app/src/test/java/com/openjarvis"
fi

sed -i "s/namespace 'com\.openjarvis'/namespace 'com.megwil.ai'/; s/applicationId \"com\.megwil\"/applicationId \"com.megwil.ai\"/; s/versionName \"1\.0\.1\"/versionName \"1.2.0\"/; s/versionCode 2/versionCode 3/" "$PROJECT/app/build.gradle"
sed -i 's/\.MainActivity/\.ui.MainActivity/' "$PROJECT/app/src/main/AndroidManifest.xml"
sed -i 's/Theme\.OpenJarvis/Theme.Megwil/g' "$PROJECT/app/src/main/AndroidManifest.xml"
sed -i 's/Theme\.OpenJarvis/Theme.Megwil/g; s/Theme\.Material3\.Light\.NoActionBar/Theme.AppCompat.DayNight.NoActionBar/' "$PROJECT/app/src/main/res/values/themes.xml"

cat > "$PROJECT/gradle.properties" <<'PROPS'
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
kotlin.code.style=official
PROPS

mkdir -p "$PROJECT/app/src/main/java/com/megwil/ai/ui/theme"
cat > "$PROJECT/app/src/main/java/com/megwil/ai/ui/theme/Theme.kt" <<'KOTLIN'
package com.megwil.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object VoidColor {
    val Violet = Color(0xFF9B7BFF)
    val VioletDim = Color(0xFF6245B5)
    val Cyan = Color(0xFF4DEBFF)
    val Amber = Color(0xFFFFC857)
    val Green = Color(0xFF55D68B)
    val Red = Color(0xFFFF647C)
    val BorderGlow = Color(0x669B7BFF)
    val BorderSubtle = Color(0x334DEBFF)
    val TextPrimary = Color(0xFFF4F2FF)
    val TextSecondary = Color(0xFFAAA6C5)
    val TextDisabled = Color(0xFF65617A)
    val Void600 = Color(0xFF38334F)
    val Void700 = Color(0xFF29253D)
    val Void800 = Color(0xFF1D1A2D)
    val Void900 = Color(0xFF141222)
    val Void950 = Color(0xFF0A0912)
}

private val MegwilDarkColors = darkColorScheme(
    primary = VoidColor.Violet,
    secondary = VoidColor.Cyan,
    background = VoidColor.Void950,
    surface = VoidColor.Void900,
    onPrimary = Color.White,
    onSecondary = VoidColor.Void950,
    onBackground = VoidColor.TextPrimary,
    onSurface = VoidColor.TextPrimary,
    error = VoidColor.Red
)

@Composable
fun MegwilTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MegwilDarkColors, content = content)
}
KOTLIN

grep -q "namespace 'com.megwil.ai'" "$PROJECT/app/build.gradle"
grep -q 'applicationId "com.megwil.ai"' "$PROJECT/app/build.gradle"
grep -q 'android:name=".ui.MainActivity"' "$PROJECT/app/src/main/AndroidManifest.xml"
grep -q 'fun MegwilTheme' "$PROJECT/app/src/main/java/com/megwil/ai/ui/theme/Theme.kt"
if grep -R -n 'com\.openjarvis' "$PROJECT/app/src/main"; then
  echo "Unmigrated com.openjarvis reference found" >&2
  exit 4
fi

echo "$PROJECT"
