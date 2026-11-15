cmake_minimum_required(VERSION 3.22.1)
project(afterglow_memory LANGUAGES C)
add_library(afterglow_memory SHARED heap_reclaimer.c)
target_compile_options(afterglow_memory PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(afterglow_memory PRIVATE
    -Wl,-z,relro,-z,now,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384)
