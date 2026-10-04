cmake_minimum_required(VERSION 3.22.1)
project(skylog_native)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# Keep the exact upstream USB ioctl bridge and IQ/DC DSP primitives.
find_library(log-lib log)
add_library(ninegradio_dsp SHARED ninegradio_dsp_jni.cpp)
target_compile_options(ninegradio_dsp PRIVATE -O3 -ffast-math)
target_link_libraries(ninegradio_dsp ${log-lib} android)
