cmake_minimum_required(VERSION 3.5.0)
project(reviewfault_harmony LANGUAGES CXX)

get_filename_component(REVIEWFAULT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.." ABSOLUTE)
file(READ "${REVIEWFAULT_ROOT}/schema/migrations/001_initial.sql" REVIEWFAULT_SCHEMA_SQL)
configure_file(schema_sql.hpp.in "${CMAKE_CURRENT_BINARY_DIR}/schema_sql.hpp" @ONLY)

add_library(reviewfault SHARED
    napi_scheduler.cpp
    "${REVIEWFAULT_ROOT}/core/src/domain.cpp"
    "${REVIEWFAULT_ROOT}/core/src/scheduler.cpp"
    "${REVIEWFAULT_ROOT}/core/src/reviewfault_c.cpp"
)
target_include_directories(reviewfault PRIVATE "${REVIEWFAULT_ROOT}/core/include")
target_include_directories(reviewfault PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_compile_features(reviewfault PRIVATE cxx_std_20)
target_compile_definitions(reviewfault PRIVATE REVIEWFAULT_BUILD_SHARED=1)
target_link_libraries(reviewfault PUBLIC libace_napi.z.so)
