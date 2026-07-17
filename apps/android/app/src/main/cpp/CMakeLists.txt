cmake_minimum_required(VERSION 3.22.1)
project(reviewfault_android LANGUAGES CXX)

get_filename_component(REVIEWFAULT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)

add_library(reviewfault SHARED
    scheduler_jni.cpp
    "${REVIEWFAULT_ROOT}/core/src/domain.cpp"
    "${REVIEWFAULT_ROOT}/core/src/scheduler.cpp"
    "${REVIEWFAULT_ROOT}/core/src/reviewfault_c.cpp"
)

target_include_directories(reviewfault PRIVATE "${REVIEWFAULT_ROOT}/core/include")
target_compile_features(reviewfault PRIVATE cxx_std_20)
target_compile_definitions(reviewfault PRIVATE REVIEWFAULT_BUILD_SHARED=1)

