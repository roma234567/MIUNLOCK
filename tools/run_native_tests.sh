#!/usr/bin/env bash
# Сборка и прогон хост-тестов нативного ядра (g++ или clang++), без Android SDK.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
OUT="$(mktemp -d)"
CXX="${CXX:-g++}"

"$CXX" -std=c++17 -O1 -Wall -Wextra -Wno-unused-parameter \
    -I"$CPP" \
    "$CPP/time_util.cpp" "$CPP/ntp_client.cpp" "$CPP/device_fingerprint.cpp" "$CPP/sniper.cpp" \
    "$ROOT/tools/native_selftest.cpp" \
    -o "$OUT/native_selftest" -lpthread

"$OUT/native_selftest"
