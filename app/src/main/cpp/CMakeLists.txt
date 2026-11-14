# Resona: whisper.cpp for AI sync (round 11 D1). Modelled on whisper.cpp's
# own Android example (examples/whisper.android/lib/src/main/jni/whisper).
cmake_minimum_required(VERSION 3.22.1)
project(resona_whisper C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../native/whisper.cpp)

# The decoder runs one small graph per token. Without OpenMP ggml starts a new
# set of threads for every graph; on a phone they begin cold on slow cores, and a
# token took 150-650 ms instead of 10 ms (Pixel, whisper base). whisper.cpp has
# one thread count for encoder and decoder, so its two decoding calls get their
# own (resona_decode_threads in whisper_jni.cpp); the encoder keeps all threads.
file(READ ${WHISPER_LIB_DIR}/src/whisper.cpp RESONA_WHISPER_SRC)
set(RESONA_DECODE_CALL "state->batch, params.n_threads, false, params.abort_callback")
string(FIND "${RESONA_WHISPER_SRC}" "${RESONA_DECODE_CALL}" RESONA_DECODE_CALL_AT)
if (RESONA_DECODE_CALL_AT EQUAL -1)
    message(FATAL_ERROR "whisper.cpp changed: decoding call not found, update the decoder thread patch")
endif ()
string(REPLACE "${RESONA_DECODE_CALL}"
    "state->batch, resona_decode_threads(params.n_threads), false, params.abort_callback"
    RESONA_WHISPER_SRC "${RESONA_WHISPER_SRC}")
file(WRITE ${CMAKE_BINARY_DIR}/resona_whisper_src.tmp "int resona_decode_threads(int n_threads);\n${RESONA_WHISPER_SRC}")
configure_file(${CMAKE_BINARY_DIR}/resona_whisper_src.tmp ${CMAKE_BINARY_DIR}/resona_whisper_src.cpp COPYONLY)

set(SOURCE_FILES
    ${CMAKE_BINARY_DIR}/resona_whisper_src.cpp
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
# The CPU code lives in ggml-cpu, which only follows this variable: without it
# it is built for plain armv8-a, without dot product and half-float instructions.
# WhisperNative checks that the device has them before loading the library.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif ()
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)
target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
if (TARGET ggml-cpu)
    target_compile_options(ggml-cpu PRIVATE -O3)
endif ()
target_link_libraries(resona_whisper ${LOG_LIB} android ggml)
