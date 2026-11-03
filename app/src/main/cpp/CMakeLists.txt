# Resona: whisper.cpp for AI sync (round 11 D1). Modelled on whisper.cpp's
# own Android example (examples/whisper.android/lib/src/main/jni/whisper).
cmake_minimum_required(VERSION 3.22.1)
project(resona_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../native/whisper.cpp)

set(SOURCE_FILES
    ${WHISPER_LIB_DIR}/src/whisper.cpp
    ${CMAKE_SOURCE_DIR}/whisper_jni.cpp)
if (EXISTS ${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp)
    list(APPEND SOURCE_FILES ${WHISPER_LIB_DIR}/src/aneforge/whisper-aneforge.cpp)
endif ()

find_library(LOG_LIB log)
include(FetchContent)

add_library(resona_whisper SHARED ${SOURCE_FILES})
target_include_directories(resona_whisper PRIVATE ${WHISPER_LIB_DIR}/include ${WHISPER_LIB_DIR}/src)
target_compile_definitions(resona_whisper PUBLIC GGML_USE_CPU)
target_compile_definitions(resona_whisper PRIVATE WHISPER_VERSION="resona")

set(GGML_COMPILE_OPTIONS "")
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    target_compile_options(resona_whisper PRIVATE -march=armv8.2-a+fp16)
    set(GGML_COMPILE_OPTIONS -march=armv8.2-a+fp16)
elseif (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(resona_whisper PRIVATE -mfpu=neon-vfpv4)
    set(GGML_COMPILE_OPTIONS -mfpu=neon-vfpv4)
endif ()

target_compile_options(resona_whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
target_link_options(resona_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)
target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
target_link_libraries(resona_whisper ${LOG_LIB} android ggml)
