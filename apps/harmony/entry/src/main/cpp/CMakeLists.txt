cmake_minimum_required(VERSION 3.20)
project(reviewfault_harmony LANGUAGES CXX)
set(RF_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../..)
add_library(reviewfault SHARED
  napi_init.cpp
  ${RF_ROOT}/core/src/domain.cpp
  ${RF_ROOT}/core/src/scheduler.cpp
  ${RF_ROOT}/core/src/scheduler_v2.cpp
  ${RF_ROOT}/core/src/scheduler_v3.cpp
  ${RF_ROOT}/core/src/scheduler_v4.cpp
  ${RF_ROOT}/core/src/scheduler_v5.cpp
  ${RF_ROOT}/core/src/reviewfault_c.cpp)
target_include_directories(reviewfault PRIVATE ${RF_ROOT}/core/include)
target_compile_features(reviewfault PRIVATE cxx_std_20)
target_link_libraries(reviewfault PUBLIC libace_napi.z.so libhilog_ndk.z.so)
