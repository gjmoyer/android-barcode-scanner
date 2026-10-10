cmake_minimum_required(VERSION 3.22.1)

# Clean-room MSI Plessey decoder (no third-party barcode libs).
project(msi_decoder)

add_library(msi_decoder SHARED
    msi_decoder.cpp
    msi_jni.cpp
)

# C++17, optimize for speed, all warnings.
target_compile_features(msi_decoder PRIVATE cxx_std_17)
target_compile_options(msi_decoder PRIVATE
    -O2
    -Wall
    -Wextra
)

# Android log for diagnostics (optional; decoder itself doesn't log).
find_library(log-lib log)
target_link_libraries(msi_decoder ${log-lib})
