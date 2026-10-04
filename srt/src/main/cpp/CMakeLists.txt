cmake_minimum_required(VERSION 3.22.1)
project(karplayer_srt LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ----------------------------------------------------------------------------
# libsrt sourcing — three modes, picked in this order:
#
#   1. PREBUILT       — src/main/jniLibs/<abi>/libsrt.so + cpp/include/srt/srt.h
#                       exist. Fastest; ignores everything else.
#   2. FROM_SOURCE    — KARPLAYER_BUILD_LIBSRT_FROM_SOURCE=ON (default).
#                       Clones Haivision/srt at SRT_VERSION and cross-compiles
#                       per ABI via ExternalProject_Add using the Android NDK
#                       toolchain already configured for this CMake invocation.
#                       Output .so is copied next to karplayer_srt.so so AGP
#                       packages it into the APK automatically.
#   3. STUB           — neither is available. srt_jni.cpp compiles with
#                       KARPLAYER_SRT_STUB=1 and all native calls return error
#                       codes. Lets the app still build for plumbing work.
#
# The user-facing flag controls only mode 2. Mode 1 always wins if present.
# ----------------------------------------------------------------------------

option(KARPLAYER_BUILD_LIBSRT_FROM_SOURCE "Build libsrt from source via ExternalProject_Add" ON)
set(SRT_VERSION "v1.5.7" CACHE STRING "Tag/branch of Haivision/srt to build")

# KARPLAYER_ACCEPT_EMPTY_ACKACK relaxes one check libsrt added in 1.5.7.
#
# At the top of CUDT::processCtrl, 1.5.7 refuses any control packet whose
# payload length is zero and returns *before* the dispatch:
#
#     if (!pktlen || pktlen % sizeof(int32_t) != 0) { ...; return false; }
#
# An ACKACK carries nothing but a sequence number in its header, and
# senders built on gosrt send it with no payload at all. On 1.5.7 the
# receiver therefore never runs processCtrlAckAck, and loses the RTT
# estimate, the NAK interval derived from it, the TSBPD drift samples
# and light ACKs. Measured on this player: RTT pinned at its initial
# 100 ms against a sender that does not pad, 4 ms against one that
# does.
#
# The packet is not malformed. draft-sharabayko-srt-01 section 3.2.8
# says in as many words: "ACKACK control packets do not contain Control
# Information Field (CIF)." KEEPALIVE and SHUTDOWN are specified the
# same way. libsrt pads them anyway for a reason it states beside its
# own code -- "control info field should be none but 'writev' does not
# allow this" -- and in 1.5.7 it began requiring everyone else to do
# the same. So this is a workaround for a regression upstream, not a
# tolerance for broken peers.
#
# ON by default, which is a decision rather than a convenience: a
# player that must work with whatever sender it is pointed at cannot
# afford to lose RTT and drift correction because that sender follows
# the specification. KarRelay 0.6 pads (datarhei/gosrt#161 proposes the
# same for gosrt generally), but MediaMTX and any other gosrt-based
# sender do not.
#
# It drops only the zero-length half of the condition and leaves the
# alignment check, which is how 1.5.6 and earlier behaved. Turn it off
# with -PacceptEmptyAckAck=false to get stock libsrt.
option(KARPLAYER_ACCEPT_EMPTY_ACKACK
       "Patch libsrt to accept zero-length control payloads (see above)" ON)

set(LIBSRT_PREBUILT_HEADER "${CMAKE_CURRENT_SOURCE_DIR}/include/srt/srt.h")
set(LIBSRT_PREBUILT_SO "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libsrt.so")

set(KARPLAYER_LIBSRT_MODE "STUB")

if(EXISTS "${LIBSRT_PREBUILT_HEADER}" AND EXISTS "${LIBSRT_PREBUILT_SO}")
    set(KARPLAYER_LIBSRT_MODE "PREBUILT")
elseif(KARPLAYER_BUILD_LIBSRT_FROM_SOURCE)
    set(KARPLAYER_LIBSRT_MODE "FROM_SOURCE")
endif()

message(STATUS "karplayer-srt: libsrt mode = ${KARPLAYER_LIBSRT_MODE} (ABI=${ANDROID_ABI})")

# ----------------------------------------------------------------------------
# Mode 1: prebuilt
# ----------------------------------------------------------------------------
if(KARPLAYER_LIBSRT_MODE STREQUAL "PREBUILT")
    add_library(srt SHARED IMPORTED)
    set_target_properties(srt PROPERTIES IMPORTED_LOCATION "${LIBSRT_PREBUILT_SO}")

    add_library(karplayer_srt SHARED srt_jni.cpp)
    target_include_directories(karplayer_srt PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_link_libraries(karplayer_srt PRIVATE srt log android)

# ----------------------------------------------------------------------------
# Mode 2: ExternalProject_Add — cross-compile libsrt for this ABI
# ----------------------------------------------------------------------------
elseif(KARPLAYER_LIBSRT_MODE STREQUAL "FROM_SOURCE")
    include(ExternalProject)

    set(MBEDTLS_VERSION "v3.6.2")
    set(MBEDTLS_PREFIX  "${CMAKE_CURRENT_BINARY_DIR}/mbedtls-ep")
    set(MBEDTLS_INSTALL "${MBEDTLS_PREFIX}/install")
    set(MBEDTLS_INC     "${MBEDTLS_INSTALL}/include")
    set(MBEDTLS_LIB_A     "${MBEDTLS_INSTALL}/lib/libmbedtls.a")
    set(MBEDCRYPTO_LIB_A  "${MBEDTLS_INSTALL}/lib/libmbedcrypto.a")
    set(MBEDX509_LIB_A    "${MBEDTLS_INSTALL}/lib/libmbedx509.a")

    set(SRT_PREFIX  "${CMAKE_CURRENT_BINARY_DIR}/srt-ep")
    set(SRT_INSTALL "${SRT_PREFIX}/install")
    set(SRT_SO      "${SRT_INSTALL}/lib/libsrt.so")
    set(SRT_INC     "${SRT_INSTALL}/include")

    if(NOT DEFINED ANDROID_PLATFORM OR ANDROID_PLATFORM STREQUAL "")
        set(ANDROID_PLATFORM "android-26")
    endif()

    # ---- mbedtls (static) ----
    # libsrt only needs the crypto primitives (AES-CTR, SHA1, etc.) for SRT
    # encryption. We build static libs and let libsrt link them into libsrt.so.
    ExternalProject_Add(mbedtls_external
        GIT_REPOSITORY https://github.com/Mbed-TLS/mbedtls.git
        GIT_TAG        ${MBEDTLS_VERSION}
        GIT_SHALLOW    TRUE
        UPDATE_DISCONNECTED TRUE
        PREFIX         "${MBEDTLS_PREFIX}"
        INSTALL_DIR    "${MBEDTLS_INSTALL}"
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON
            "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
            -DENABLE_PROGRAMS=OFF
            -DENABLE_TESTING=OFF
            -DUSE_SHARED_MBEDTLS_LIBRARY=OFF
            -DUSE_STATIC_MBEDTLS_LIBRARY=ON
        BUILD_BYPRODUCTS
            "${MBEDTLS_LIB_A}"
            "${MBEDCRYPTO_LIB_A}"
            "${MBEDX509_LIB_A}"
    )

    # ---- libsrt with encryption ON ----
    # Empty unless asked for, so the normal build fetches and compiles
    # libsrt untouched.
    set(SRT_PATCH_COMMAND "")
    if(KARPLAYER_ACCEPT_EMPTY_ACKACK)
        # One substitution, on one line, keeping the alignment check.
        # sed over patch(1): no context to go stale when the pin moves,
        # and it fails loudly if the line is not there any more.
        set(SRT_PATCH_COMMAND
            sed -i "s/if (!pktlen || pktlen % sizeof(int32_t) != 0)/if (pktlen % sizeof(int32_t) != 0) \\/* KARPLAYER: zero-length control payloads accepted *\\//"
            <SOURCE_DIR>/srtcore/core.cpp)
        message(STATUS "KarPlayer: libsrt will be patched to accept empty ACKACK")
    endif()

    ExternalProject_Add(srt_external
        DEPENDS        mbedtls_external
        GIT_REPOSITORY https://github.com/Haivision/srt.git
        GIT_TAG        ${SRT_VERSION}
        GIT_SHALLOW    TRUE
        UPDATE_DISCONNECTED TRUE
        PATCH_COMMAND  ${SRT_PATCH_COMMAND}
        PREFIX         "${SRT_PREFIX}"
        INSTALL_DIR    "${SRT_INSTALL}"
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=c++_shared
            -DCMAKE_BUILD_TYPE=Release
            "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
            # Encryption via mbedtls (linked statically, no shared crypto .so).
            # libsrt's FindMbedTLS.cmake uses MBEDTLS_PREFIX as a HINTS root
            # for find_path/find_library. Under the Android NDK toolchain,
            # find_library is restricted to the sysroot, so we override the
            # find-mode for library/include to BOTH to allow lookup inside
            # MBEDTLS_PREFIX/lib and /include.
            -DENABLE_ENCRYPTION=ON
            -DUSE_ENCLIB=mbedtls
            -DSTATIC_MBEDTLS=ON
            -DMBEDTLS_PREFIX=${MBEDTLS_INSTALL}
            -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH
            -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH
            -DENABLE_APPS=OFF
            -DENABLE_SHARED=ON
            -DENABLE_STATIC=OFF
            -DENABLE_UNITTESTS=OFF
            -DENABLE_LOGGING=ON
            -DENABLE_BONDING=ON
            -DENABLE_CXX11=ON
            -DUSE_STATIC_LIBSTDCXX=OFF
        BUILD_BYPRODUCTS "${SRT_SO}"
    )

    add_library(srt SHARED IMPORTED)
    set_target_properties(srt PROPERTIES IMPORTED_LOCATION "${SRT_SO}")
    add_dependencies(srt srt_external)

    add_library(karplayer_srt SHARED srt_jni.cpp)
    add_dependencies(karplayer_srt srt_external)
    target_include_directories(karplayer_srt PRIVATE "${SRT_INC}")
    target_link_libraries(karplayer_srt PRIVATE srt log android)

    add_custom_command(TARGET karplayer_srt POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${SRT_SO}" "$<TARGET_FILE_DIR:karplayer_srt>/libsrt.so"
        COMMENT "karplayer-srt: staging libsrt.so for ${ANDROID_ABI}"
        VERBATIM
    )

# ----------------------------------------------------------------------------
# Mode 3: stub — let everything compile without libsrt present
# ----------------------------------------------------------------------------
else()
    message(WARNING "karplayer-srt: building in STUB mode — native calls will "
                    "return error codes. Set KARPLAYER_BUILD_LIBSRT_FROM_SOURCE=ON "
                    "or drop a prebuilt libsrt.so + headers into the source tree.")
    add_library(karplayer_srt SHARED srt_jni.cpp)
    target_include_directories(karplayer_srt PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/include")
    target_compile_definitions(karplayer_srt PRIVATE KARPLAYER_SRT_STUB=1)
    target_link_libraries(karplayer_srt PRIVATE log android)
endif()
