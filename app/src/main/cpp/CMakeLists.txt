cmake_minimum_required(VERSION 3.22.1)
project(yediscord)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# build.gradle unpacks app/libs/discord_partner_sdk.aar into ./discord-sdk
set(SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/discord-sdk)

file(GLOB_RECURSE SDK_HEADERS "${SDK_DIR}/*discordpp.h")
list(GET SDK_HEADERS 0 SDK_HEADER)
if(NOT SDK_HEADER)
    message(FATAL_ERROR "discordpp.h not found inside discord_partner_sdk.aar")
endif()
get_filename_component(SDK_INCLUDE ${SDK_HEADER} DIRECTORY)

file(GLOB_RECURSE SDK_LIBS "${SDK_DIR}/*libdiscord_partner_sdk.so")
set(SDK_LIB "")
foreach(candidate ${SDK_LIBS})
    string(FIND "${candidate}" "${ANDROID_ABI}" pos)
    if(NOT pos EQUAL -1 AND SDK_LIB STREQUAL "")
        set(SDK_LIB ${candidate})
    endif()
endforeach()
if(SDK_LIB STREQUAL "")
    message(FATAL_ERROR "libdiscord_partner_sdk.so for ${ANDROID_ABI} not found inside the AAR")
endif()

add_library(discord_sdk SHARED IMPORTED)
set_target_properties(discord_sdk PROPERTIES IMPORTED_LOCATION ${SDK_LIB})

add_library(yediscord SHARED discord_bridge.cpp)
target_include_directories(yediscord PRIVATE ${SDK_INCLUDE})
target_link_libraries(yediscord discord_sdk log)
