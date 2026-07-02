cmake_minimum_required(VERSION 3.22)

project(whisper_jni CXX C)

set(CMAKE_CXX_STANDARD 17)

# Whisper is far too slow unoptimised, so force -O3 even for the app's debug
# build type (this affects only this native module, including ggml below).
set(CMAKE_C_FLAGS_DEBUG "-O3")
set(CMAKE_CXX_FLAGS_DEBUG "-O3")

# WHISPER_DIR is passed from build.gradle.kts (from local.properties
# `whisper.cpp.path`). It points at a checkout of ggerganov/whisper.cpp.
if (NOT DEFINED WHISPER_DIR)
    message(FATAL_ERROR "WHISPER_DIR not set (configure whisper.cpp.path in local.properties)")
endif ()

message(STATUS "whisper.cpp source: ${WHISPER_DIR}")

# Build ggml (the CPU math backend) from the whisper.cpp checkout.
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

find_library(LOG_LIB log)

add_library(
    whisper_jni
    SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/whisper_jni.c
)

target_compile_definitions(whisper_jni PUBLIC GGML_USE_CPU)
# whisper.cpp's own CMake normally injects this; we compile the source directly.
target_compile_definitions(whisper_jni PRIVATE WHISPER_VERSION="1.8.6")

include_directories(
    ${WHISPER_DIR}
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

# Per-ABI tuning: arm64 gets armv8.2 + fp16 SIMD, which the q5/q4 NEON kernels
# use heavily; armv7 gets neon-vfpv4.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(whisper_jni PRIVATE -march=armv8.2-a+fp16)
    target_compile_options(ggml PRIVATE -march=armv8.2-a+fp16)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(whisper_jni PRIVATE -mfpu=neon-vfpv4)
    target_compile_options(ggml PRIVATE -mfpu=neon-vfpv4)
endif ()

# Release size/speed flags (mirrors the whisper.android sample).
if (NOT ${CMAKE_BUILD_TYPE} STREQUAL "Debug")
    target_compile_options(whisper_jni PRIVATE -O3 -ffunction-sections -fdata-sections)
    target_link_options(whisper_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif ()

target_link_libraries(whisper_jni ${LOG_LIB} android ggml)
