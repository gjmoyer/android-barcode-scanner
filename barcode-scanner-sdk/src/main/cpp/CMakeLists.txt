cmake_minimum_required(VERSION 3.22.1)

# Clean-room MSI Plessey decoder (no third-party barcode libs).
project(msi_decoder)

add_library(msi_decoder SHARED
    msi_decoder.cpp
    msi_frame.cpp
    msi_jni.cpp
    msi_bitmap_jni.cpp
)

# C++17, optimize for speed, all warnings. -O3 is unconditional and
# deliberate: the host harness produces identical verdict lines at -O2 and
# -O3, so Debug pays nothing for slower decode. -flto gates on every
# non-Debug config: a $<$<CONFIG:Release>:...> gate is dead code because
# AGP passes -DCMAKE_BUILD_TYPE=RelWithDebInfo for release variants.
# No -ffast-math: decode results depend on exact IEEE float behaviour
# (stretch table, template scores, float-bit cache keys).
target_compile_features(msi_decoder PRIVATE cxx_std_17)
target_compile_options(msi_decoder PRIVATE
    -O3
    -Wall
    -Wextra
    $<$<NOT:$<CONFIG:Debug>>:-flto>
)
target_link_options(msi_decoder PRIVATE $<$<NOT:$<CONFIG:Debug>>:-flto>)

# Android log for diagnostics (optional; decoder itself doesn't log);
# jnigraphics for AndroidBitmap_* (native still conversion).
find_library(log-lib log)
target_link_libraries(msi_decoder ${log-lib} jnigraphics)
