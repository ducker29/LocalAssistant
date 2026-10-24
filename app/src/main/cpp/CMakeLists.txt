cmake_minimum_required(VERSION 3.22.1)

project(localassistant LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")

# Pinned llama.cpp revision: v0.7.0 (620d2225cc436ab0b001e3200e590e3b286ef9ab).
# Only the inference library and its chat-template (Jinja) helper are built.
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS     OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)

# Conservative CPU backend: no host-specific instruction sets, no OpenMP,
# no ccache, no runtime backend loading.
set(GGML_NATIVE     OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP     OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE     OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_AVX512     OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(localassistant SHARED llama_jni.cpp)

target_include_directories(localassistant PRIVATE
        "${LLAMA_DIR}/include"
        "${LLAMA_DIR}/common")

target_link_libraries(localassistant PRIVATE
        llama
        llama-common
        android
        log)

target_compile_options(localassistant PRIVATE -O2 -fno-finite-math-only)
