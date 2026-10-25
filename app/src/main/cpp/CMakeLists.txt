# Native side of on-device transcription: whisper.cpp (pinned submodule in
# third_party/whisper.cpp) linked statically into one JNI library.
cmake_minimum_required(VERSION 3.22.1)
project(callnotes_native C CXX)

set(CMAKE_CXX_STANDARD 17)
# See app/build.gradle.kts: inference code must be optimised even in debug APKs.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Library only, statically linked, no OpenMP (ggml's own thread pool; no libomp to ship).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# ARMv8.2 with fp16 + dot product: every 64-bit phone core since Cortex-A55/A75.
# The app checks /proc/cpuinfo before loading this library (WhisperNative.isSupported).
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)

# Local fixes to the pinned whisper.cpp, applied to the submodule before it is built (once:
# an already-applied patch is left alone). A patch that no longer fits — after a submodule
# bump — stops the build, so it is never silently dropped. See docs/wiki/models.md.
foreach(PATCH whisper-dtw-short-window.patch)
    set(PATCH_FILE ${CMAKE_CURRENT_SOURCE_DIR}/patches/${PATCH})
    execute_process(COMMAND git apply --reverse --check ${PATCH_FILE}
        WORKING_DIRECTORY ${WHISPER_DIR} RESULT_VARIABLE ALREADY OUTPUT_QUIET ERROR_QUIET)
    if(NOT ALREADY EQUAL 0)
        execute_process(COMMAND git apply ${PATCH_FILE}
            WORKING_DIRECTORY ${WHISPER_DIR} RESULT_VARIABLE APPLIED)
        if(NOT APPLIED EQUAL 0)
            message(FATAL_ERROR "whisper.cpp patch ${PATCH} does not apply")
        endif()
    endif()
endforeach()

add_subdirectory(${WHISPER_DIR} whisper_build EXCLUDE_FROM_ALL)

add_library(callnotes_whisper SHARED whisper_jni.cpp quantize_jni.cpp)
target_link_libraries(callnotes_whisper PRIVATE whisper log android)
target_compile_options(callnotes_whisper PRIVATE -O3 -fvisibility=hidden)
target_link_options(callnotes_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
