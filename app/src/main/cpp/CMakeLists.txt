cmake_minimum_required(VERSION 3.22.1)
project(gf2capture C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

set(ZDTUN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/zdtun)

add_library(zdtun SHARED
    ${ZDTUN_DIR}/zdtun.c
    ${ZDTUN_DIR}/utils.c
)

target_include_directories(zdtun PUBLIC ${ZDTUN_DIR})
target_compile_definitions(zdtun PRIVATE ANDROID NO_DEBUG _LITTLE_ENDIAN)
target_compile_options(zdtun PRIVATE
    -Wall
    -Wextra
    -Wno-unused-function
    -Wno-unused-variable
    -ffunction-sections
    -fdata-sections
)

add_library(gf2capture SHARED gf2capture.c)
target_include_directories(gf2capture PRIVATE ${ZDTUN_DIR})
target_compile_options(gf2capture PRIVATE
    -Wall
    -Wextra
    -Werror
    -ffunction-sections
    -fdata-sections
)

find_library(LOG_LIBRARY log)
target_link_libraries(zdtun PRIVATE ${LOG_LIBRARY})
target_link_libraries(gf2capture PRIVATE zdtun ${LOG_LIBRARY})

target_link_options(zdtun PRIVATE -Wl,--gc-sections)
target_link_options(gf2capture PRIVATE -Wl,--gc-sections)
