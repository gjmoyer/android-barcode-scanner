cmake_minimum_required(VERSION 3.22.1)
project(barcode-scanner-sdk)

# zxing-cpp provides DataBar (Omni/Stacked/Limited/Expanded), MSI-less 1D set,
# QR, Aztec, etc. We fetch it at build time so the SDK module stays self-contained.
# MSI Plessey is NOT in zxing-cpp -> decoded by our custom Kotlin fallback
# (see data/msi/MsiPlesseyDecoder.kt). This CMake only builds the bridge.
#
# v3.x notes: ZXING_WRITERS is a STRING (OFF/ON/OLD/NEW/BOTH); OFF (readers only)
# keeps the fetch dependency-free (no zint backend). Library itself is C++20.
include(FetchContent)
set(ZXING_READERS ON CACHE BOOL "" FORCE)
set(ZXING_WRITERS OFF CACHE STRING "" FORCE)
set(ZXING_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ZXING_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(ZXING_BLACKBOX_TESTS OFF CACHE BOOL "" FORCE)
set(ZXING_EXPERIMENTAL_API ON CACHE BOOL "" FORCE)

FetchContent_Declare(
  zxingcpp
  GIT_REPOSITORY https://github.com/zxing-cpp/zxing-cpp.git
  GIT_TAG v3.1.1
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(zxingcpp)

add_library(
  zxing_bridge
  SHARED
  src/main/cpp/zxing_bridge.cpp
  src/main/cpp/scan_image.cpp
)

target_include_directories(zxing_bridge PRIVATE src/main/cpp)
target_link_libraries(zxing_bridge log ZXing::ZXing)

target_compile_features(zxing_bridge PRIVATE cxx_std_20)
