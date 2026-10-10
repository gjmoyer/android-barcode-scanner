#!/bin/sh
# Builds the host MSI accuracy+speed harness against a decoder source dir.
# Usage: ./build.sh [path/to/cpp/dir]
# Defaults to the shipped SDK sources. Pass a pristine copy (see README) to
# A/B behavior before/after decoder changes.
set -e
cd "$(dirname "$0")"
SRC="${1:-../../barcode-scanner-sdk/src/main/cpp}"
OUT="${OUT:-msi_harness}"
clang++ -std=c++17 -O2 -Wall -Wextra \
    -I"$SRC" \
    msi_harness.cpp "$SRC/msi_decoder.cpp" \
    -lz \
    -o "$OUT"
echo "built ./$OUT (decoder: $SRC)"
