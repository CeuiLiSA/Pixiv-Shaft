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
        -Wl,-z,now
        # Google Play 自 2025-11 起要求 16 KB page size 支持：LOAD 段必须按 16 KB 对齐，
        # 否则上传时报 "not compatible with 16 KB devices"。
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

set_target_properties(shaft_secrets PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)

# 图片滤镜导出内核（shaft_image_filter.cpp）：与密钥库分开编译，不共享符号，也不受密钥生成任务影响。
# 无论 Debug / Release 都开 -O3：拖滑条的每一帧预览和全分辨率导出都跑它，Debug 包也得跟手。
add_library(shaft_filter SHARED shaft_image_filter.cpp)

target_compile_features(shaft_filter PRIVATE cxx_std_20)
target_compile_options(shaft_filter PRIVATE
        -O3
        -Wall
        -Wextra
        -Werror
        -ffunction-sections
        -fdata-sections)
target_link_libraries(shaft_filter PRIVATE jnigraphics)
target_link_options(shaft_filter PRIVATE
        -Wl,--gc-sections
        -Wl,-z,relro
        -Wl,-z,now
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

set_target_properties(shaft_filter PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN YES)
