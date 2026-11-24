#!/usr/bin/env bash
# Builds the subset of Open CASCADE Technology (OCCT) needed to read STEP files
# and mesh them, as static libraries.
#
#   scripts/build-occt.sh <abi> [ndk-dir]
#
# <abi> is an Android ABI (arm64-v8a, x86_64, armeabi-v7a, x86) or "host" for a
# desktop build used by the native tests. Output goes to third_party/occt/<abi>.
# The OCCT source is cloned into third_party/occt-src on first use.
set -euo pipefail

OCCT_TAG="${OCCT_TAG:-V7_7_2}"
ABI="${1:?usage: build-occt.sh <abi|host> [ndk-dir]}"
NDK="${2:-${ANDROID_NDK_HOME:-}}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${OCCT_SRC:-$ROOT/third_party/occt-src}"
OUT="$ROOT/third_party/occt/$ABI"
BUILD="$ROOT/third_party/occt-build/$ABI"

if [ ! -f "$SRC/CMakeLists.txt" ]; then
  git clone --depth 1 --branch "$OCCT_TAG" https://github.com/Open-Cascade-SAS/OCCT.git "$SRC"
fi

# Only the toolkits for STEP reading and meshing; OCCT resolves their dependencies.
TOOLKITS="TKSTEP;TKMesh"

ARGS=(
  -G Ninja
  -DCMAKE_BUILD_TYPE=Release
  -DBUILD_LIBRARY_TYPE=Static
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DINSTALL_DIR="$OUT"
  -DBUILD_MODULE_ApplicationFramework=OFF
  -DBUILD_MODULE_DataExchange=OFF
  -DBUILD_MODULE_DETools=OFF
  -DBUILD_MODULE_Draw=OFF
  -DBUILD_MODULE_FoundationClasses=OFF
  -DBUILD_MODULE_ModelingAlgorithms=OFF
  -DBUILD_MODULE_ModelingData=OFF
  -DBUILD_MODULE_Visualization=OFF
  -DBUILD_ADDITIONAL_TOOLKITS="$TOOLKITS"
  -DBUILD_DOC_Overview=OFF
  -DBUILD_SAMPLES_QT=OFF
  -DUSE_FREETYPE=OFF
  -DUSE_FREEIMAGE=OFF
  -DUSE_OPENGL=OFF
  -DUSE_GLES2=OFF
  -DUSE_RAPIDJSON=OFF
  -DUSE_DRACO=OFF
  -DUSE_TK=OFF
  -DUSE_TBB=OFF
  -DUSE_VTK=OFF
  -DUSE_XLIB=OFF
  -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
  -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections"
)

if [ "$ABI" != "host" ]; then
  [ -n "$NDK" ] || { echo "Set ANDROID_NDK_HOME or pass the NDK directory" >&2; exit 1; }
  ARGS+=(
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM=android-24
    -DANDROID_STL=c++_static
    -DANDROID_CPP_FEATURES="rtti exceptions"
  )
fi

cmake -S "$SRC" -B "$BUILD" "${ARGS[@]}"
cmake --build "$BUILD" --parallel "$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
cmake --install "$BUILD" >/dev/null
echo "OCCT for $ABI installed in $OUT"
