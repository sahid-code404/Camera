cmake_minimum_required(VERSION 3.22.1)
project(camera_raw_native LANGUAGES CXX)

add_library(
    camera_raw_native
    SHARED
    camera_raw_native.cpp
)

target_compile_features(camera_raw_native PRIVATE cxx_std_20)

target_compile_options(
    camera_raw_native
    PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

target_link_options(
    camera_raw_native
    PRIVATE
    -Wl,--gc-sections
)

target_link_libraries(
    camera_raw_native
    android
    log
)
