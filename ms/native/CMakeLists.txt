cmake_minimum_required(VERSION 3.25)
project(MiSideCraftNative LANGUAGES CXX)

# The engine-free half of the MiSide mod (see src/Api.cpp): the shared memory
# with Minecraft, its picture over MiSide's frame, its blocks in 3D, keyboard
# and mouse, starting Minecraft. The game-specific half is the BepInEx plugin
# in ../plugin.
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(MiSideCraftNative SHARED
    src/Api.cpp
    src/Input.cpp
    src/Launcher.cpp
    src/Link.cpp
    src/Overlay.cpp
    src/World.cpp
)

target_include_directories(MiSideCraftNative PRIVATE
    src
    shim
    ${CMAKE_CURRENT_SOURCE_DIR}/../..
)

target_compile_definitions(MiSideCraftNative PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN)
target_compile_options(MiSideCraftNative PRIVATE /utf-8 /W3 /permissive-)
target_link_libraries(MiSideCraftNative PRIVATE d3d11 dxgi d3dcompiler gdi32 user32 shell32 ole32)
