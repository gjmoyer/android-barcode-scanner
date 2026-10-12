#!/bin/sh
# Builds libmsi_decoder for the host and runs JniSmoke against it on a real
# JVM. Needs: clang++, a JDK (JAVA_HOME, else Android Studio's bundled JBR)
# and an Android NDK for jni.h (the JNI ABI is standard; Android Studio's JBR
# ships no headers). Usage: ./run.sh   (exit code = failed checks)
set -e
cd "$(dirname "$0")"
SRC=../../../barcode-scanner-sdk/src/main/cpp
if [ -z "$JAVA_HOME" ]; then
    for jbr in "$HOME"/Applications/Android\ Studio*.app/Contents/jbr/Contents/Home \
               /Applications/Android\ Studio*.app/Contents/jbr/Contents/Home; do
        [ -x "$jbr/bin/java" ] && JAVA_HOME="$jbr"
    done
fi
[ -x "$JAVA_HOME/bin/java" ] || { echo "set JAVA_HOME to a JDK" >&2; exit 2; }
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JNI_H=$(ls -d "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h 2>/dev/null | tail -1)
[ -f "$JNI_H" ] || { echo "no NDK jni.h under $SDK/ndk" >&2; exit 2; }
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/inc" "$OUT/classes"
cp "$JNI_H" "$OUT/inc/"
case "$(uname)" in Darwin) LIB="$OUT/libmsi_decoder.dylib" ;; *) LIB="$OUT/libmsi_decoder.so" ;; esac
clang++ -std=c++17 -O2 -Wall -Wextra -shared -fPIC -I"$OUT/inc" -I"$SRC" \
    "$SRC/msi_decoder.cpp" "$SRC/msi_frame.cpp" "$SRC/msi_jni.cpp" -o "$LIB"
"$JAVA_HOME/bin/javac" -d "$OUT/classes" $(find src -name '*.java')
"$JAVA_HOME/bin/java" --enable-native-access=ALL-UNNAMED -cp "$OUT/classes" JniSmoke "$LIB"
