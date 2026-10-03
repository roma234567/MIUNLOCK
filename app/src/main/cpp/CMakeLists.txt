cmake_minimum_required(VERSION 3.22.1)

project(miunlock CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(miunlock_core STATIC
        time_util.cpp
        ntp_client.cpp
        device_fingerprint.cpp
        sniper.cpp)

target_include_directories(miunlock_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(miunlock_core PRIVATE -Wall -Wextra -Wno-unused-parameter -Os)

add_library(miunlock SHARED jni_bridge.cpp)

target_link_libraries(miunlock PRIVATE miunlock_core)

target_compile_options(miunlock PRIVATE -Wall -Wextra -Wno-unused-parameter -Os -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(miunlock PRIVATE ${log-lib})
