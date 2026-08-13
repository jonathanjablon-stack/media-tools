#!/usr/bin/env bash
set -euo pipefail
mkdir -p CarStream/app
cat > CarStream/build.gradle <<'EOF'
plugins {
    id 'com.android.application' version '8.8.2' apply false
}
EOF
cat > CarStream/gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=false
android.nonTransitiveRClass=true
EOF
