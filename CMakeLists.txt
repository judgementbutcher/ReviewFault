cmake_minimum_required(VERSION 3.20)
project(ReviewFaultCore VERSION 0.1.0 LANGUAGES CXX)

add_library(reviewfault_core
  core/src/domain.cpp
  core/src/scheduler.cpp
  core/src/reviewfault_c.cpp
)
target_include_directories(reviewfault_core PUBLIC core/include)
target_compile_features(reviewfault_core PUBLIC cxx_std_20)
if(BUILD_SHARED_LIBS)
  target_compile_definitions(reviewfault_core PRIVATE REVIEWFAULT_BUILD_SHARED=1)
endif()
set_target_properties(reviewfault_core PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
)

option(REVIEWFAULT_BUILD_TESTS "Build core tests" ON)
if(REVIEWFAULT_BUILD_TESTS)
  enable_testing()
  add_executable(reviewfault_core_tests core/tests/scheduler_test.cpp)
  target_link_libraries(reviewfault_core_tests PRIVATE reviewfault_core)
  add_test(NAME reviewfault_core_tests COMMAND reviewfault_core_tests)

  add_executable(reviewfault_domain_tests core/tests/domain_test.cpp)
  target_link_libraries(reviewfault_domain_tests PRIVATE reviewfault_core)
  add_test(NAME reviewfault_domain_tests COMMAND reviewfault_domain_tests)
endif()
