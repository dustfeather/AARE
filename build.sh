#!/bin/sh
# Builds the debug APK (app/build/outputs/apk/debug/app-debug.apk) or runs the given Gradle tasks:
#
#   ./build.sh                    assembleDebug
#   ./build.sh :aap:test          protocol tests
#
# Gradle and the Android Gradle plugin want a JDK 17-21: JAVA_HOME is used if it is one, otherwise
# the usual install locations are searched.
# Set AARE_BUILD_MEMORY (e.g. 5G) to run the build in a memory-capped systemd scope, so that on a
# machine without swap only the build dies if it grows too much.
set -e
cd "$(dirname "$0")"

jdk_major() { "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
ok() { [ -x "$1/bin/java" ] && v=$(jdk_major "$1") && [ "$v" -ge 17 ] 2>/dev/null && [ "$v" -le 21 ]; }

if [ -z "$JAVA_HOME" ] || ! ok "$JAVA_HOME"; then
    JAVA_HOME=""
    for d in "$HOME"/.gradle/jdks/jdk-21* "$HOME"/.gradle/jdks/*21* "$HOME"/.gradle/jdks/jdk-17* \
             /usr/lib/jvm/java-21-* /usr/lib/jvm/java-17-* /usr/lib/jvm/*21* /usr/lib/jvm/*17* \
             /Library/Java/JavaVirtualMachines/*/Contents/Home \
             /opt/android-studio/jbr "$HOME"/android-studio/jbr "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
        if ok "$d"; then JAVA_HOME="$d"; break; fi
    done
fi
if [ -z "$JAVA_HOME" ]; then
    echo "No JDK 17-21 found. Install one (e.g. Eclipse Temurin 21) or set JAVA_HOME." >&2
    exit 1
fi
export JAVA_HOME
[ $# -eq 0 ] && set -- assembleDebug

if [ -n "$AARE_BUILD_MEMORY" ] && command -v systemd-run >/dev/null 2>&1; then
    exec systemd-run --user --scope --quiet -p MemoryMax="$AARE_BUILD_MEMORY" -p MemorySwapMax=0 ./gradlew "$@"
fi
exec ./gradlew "$@"
