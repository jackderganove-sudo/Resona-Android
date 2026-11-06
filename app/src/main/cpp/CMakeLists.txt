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
# The CPU code lives in ggml-cpu, which only follows this variable: without it
# it is built for plain armv8-a, without dot product and half-float instructions.
# WhisperNative checks that the device has them before loading the library.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif ()
# GPU encoder and decoder through ggml's Vulkan backend (arm64 only). The NDK has
# glslc and the loader, but neither vulkan.hpp nor the SPIR-V headers.
if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    FetchContent_Declare(vulkan_headers GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git GIT_TAG vulkan-sdk-1.4.363.0 GIT_SHALLOW TRUE)
    FetchContent_Declare(spirv_headers GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git GIT_TAG vulkan-sdk-1.4.363.0 GIT_SHALLOW TRUE)
    FetchContent_GetProperties(vulkan_headers)
    if (NOT vulkan_headers_POPULATED)
        FetchContent_Populate(vulkan_headers)
    endif ()
    FetchContent_GetProperties(spirv_headers)
    if (NOT spirv_headers_POPULATED)
        FetchContent_Populate(spirv_headers)
    endif ()
    find_library(RESONA_VULKAN_LIB vulkan REQUIRED)
    if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(RESONA_NDK_HOST darwin-x86_64)
    else ()
        set(RESONA_NDK_HOST linux-x86_64)
    endif ()
    set(Vulkan_INCLUDE_DIR ${vulkan_headers_SOURCE_DIR}/include CACHE PATH "" FORCE)
    set(Vulkan_LIBRARY ${RESONA_VULKAN_LIB} CACHE FILEPATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE ${ANDROID_NDK}/shader-tools/${RESONA_NDK_HOST}/glslc CACHE FILEPATH "" FORCE)
    file(WRITE ${CMAKE_BINARY_DIR}/spirv-config/SPIRV-HeadersConfig.cmake
        "if (NOT TARGET SPIRV-Headers::SPIRV-Headers)\n  add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)\n  set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${spirv_headers_SOURCE_DIR}/include\")\nendif ()\n")
    set(SPIRV-Headers_DIR ${CMAKE_BINARY_DIR}/spirv-config CACHE PATH "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif ()
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_LIB_DIR}/ggml)
FetchContent_MakeAvailable(ggml)
target_compile_options(ggml PRIVATE ${GGML_COMPILE_OPTIONS})
if (TARGET ggml-cpu)
    target_compile_options(ggml-cpu PRIVATE -O3)
endif ()
target_link_libraries(resona_whisper ${LOG_LIB} android ggml)
