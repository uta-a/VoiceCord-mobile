cmake_minimum_required(VERSION 3.22)
project(voicecord LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(shadowhook SHARED IMPORTED)
set_target_properties(shadowhook PROPERTIES
    IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/libshadowhook.so)

add_library(voicecord SHARED src/hook.cpp)
target_include_directories(voicecord PRIVATE ${CMAKE_SOURCE_DIR}/include)
target_link_libraries(voicecord shadowhook log)
target_compile_options(voicecord PRIVATE -Wall -O2 -fvisibility=hidden)
