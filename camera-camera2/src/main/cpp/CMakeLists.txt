cmake_minimum_required(VERSION 3.22.1)
project(camera_ndk_bridge LANGUAGES CXX)

add_library(camera_ndk_bridge SHARED
    motioncam_camera_ndk.cpp
)

target_compile_features(camera_ndk_bridge PRIVATE cxx_std_17)
target_compile_options(camera_ndk_bridge PRIVATE -Wall -Wextra -Wno-unused-parameter)

find_library(log-lib log)
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)
find_library(android-lib android)

target_link_libraries(camera_ndk_bridge
    ${log-lib}
    ${camera2ndk-lib}
    ${mediandk-lib}
    ${android-lib}
)
