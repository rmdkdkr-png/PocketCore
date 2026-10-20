cmake_minimum_required(VERSION 3.22.1)
project(pocketcore C)

add_library(pocketcore SHARED native.c pace.c sndfx.c league.c practice.c look.c slash.c vpanel.c vsband.c cheat.c ovplane.c uisfx.c callout.c)

target_compile_options(pocketcore PRIVATE -O2 -Wall)

find_library(log-lib log)
find_library(gles-lib GLESv2)
find_library(aaudio-lib aaudio)
find_library(android-lib android)

target_link_libraries(pocketcore
        ${log-lib} ${gles-lib} ${aaudio-lib} ${android-lib} dl m)
