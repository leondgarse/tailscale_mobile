cmake_minimum_required(VERSION 3.22.1)
project(tailscale_jni)

set(JNILIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(tailscale_jni SHARED tailscale_jni.cpp)
target_include_directories(tailscale_jni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(tailscale_jni
    -L${JNILIBS_DIR} -ltailscale
    android
    log)
