cmake_minimum_required(VERSION 3.10.2)
project(meshtastic_codec2 C)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/codec2)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/codec2/codec2)

file(GLOB CODEC2_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/codec2/*.c")

add_library(
    codec2
    SHARED
    codec2_jni.c
    ${CODEC2_SOURCES}
)

find_library(log-lib log)
find_library(m-lib m)

target_link_libraries(
    codec2
    ${log-lib}
    ${m-lib}
)
