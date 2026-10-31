cmake_minimum_required(VERSION 3.22.1)

# Clean-room MSI Plessey decoder (no third-party barcode libs).
project(msi_decoder)

add_library(msi_decoder SHARED
    msi_decoder.cpp
    msi_frame.cpp
    msi_jni.cpp
    msi_bitmap_jni.cpp
)

# C++17, optimize for speed, all warnings. No -ffast-math: decode results
# depend on exact IEEE float behaviour (stretch table, template scores).
target_compile_features(msi_decoder PRIVATE cxx_std_17)
target_compile_options(msi_decoder PRIVATE
    -O3
    -Wall
    -Wextra
    $<$<CONFIG:Release>:-flto>
)
target_link_options(msi_decoder PRIVATE $<$<CONFIG:Release>:-flto>)

# Android log for diagnostics (optional; decoder itself doesn't log);
# jnigraphics for AndroidBitmap_* (native still conversion).
find_library(log-lib log)
target_link_libraries(msi_decoder ${log-lib} jnigraphics)
