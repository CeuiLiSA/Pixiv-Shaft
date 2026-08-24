cmake_minimum_required(VERSION 3.22.1)

project(shaft_secrets LANGUAGES CXX)

if(NOT DEFINED SHAFT_GENERATED_SECRET_DIR)
    message(FATAL_ERROR "SHAFT_GENERATED_SECRET_DIR is required")
endif()

set(SHAFT_GENERATED_SECRET_HEADER
        "${SHAFT_GENERATED_SECRET_DIR}/shaft_secrets.generated.h")
if(NOT EXISTS "${SHAFT_GENERATED_SECRET_HEADER}")
    message(FATAL_ERROR "Generated Shaft secret header is missing")
endif()

add_library(shaft_secrets SHARED shaft_hmac.cpp)

target_include_directories(shaft_secrets PRIVATE "${SHAFT_GENERATED_SECRET_DIR}")
target_compile_features(shaft_secrets PRIVATE cxx_std_20)
target_compile_options(shaft_secrets PRIVATE
        -Wall
        -Wextra
        -Werror
        -ffunction-sections
        -fdata-sections)
target_link_options(shaft_secrets PRIVATE
        -Wl,--gc-sections
        -Wl,-z,relro
        -Wl,-z,now)

set_target_properties(shaft_secrets PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)
