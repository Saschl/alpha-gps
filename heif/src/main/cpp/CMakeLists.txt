cmake_minimum_required(VERSION 3.22.1)
project(alpha_heif LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Vendored, unmodified source archives keep Android/F-Droid builds offline.
include(FetchContent)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
foreach(flag ENABLE_DECODER ENABLE_ENCODER ENABLE_SHERLOCK265 ENABLE_SDL
        ENABLE_INTERNAL_DEVELOPMENT_TOOLS WITH_FUZZERS ENABLE_AVX512)
    set(${flag} OFF CACHE BOOL "" FORCE)
endforeach()
FetchContent_Declare(libde265
    URL "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libde265-1.1.3.tar.gz"
    URL_HASH SHA256=189baa08fd6d2dd34db099de411bd6b2e6bd5eb88e81236a3d0fa3826b9715c4)
FetchContent_MakeAvailable(libde265)
target_include_directories(de265 PUBLIC "$<BUILD_INTERFACE:${libde265_SOURCE_DIR}>" "$<BUILD_INTERFACE:${libde265_BINARY_DIR}>")
set(LIBDE265_INCLUDE_DIR "${libde265_SOURCE_DIR}" CACHE PATH "" FORCE)
set(LIBDE265_LIBRARY de265 CACHE STRING "" FORCE)

foreach(codec X265 KVAZAAR UVG266 VVDEC VVENC X264 OpenH264_DECODER DAV1D AOM_DECODER
        AOM_ENCODER SvtEnc RAV1E JPEG_DECODER JPEG_ENCODER OpenJPEG_ENCODER OpenJPEG_DECODER
        FFMPEG_DECODER OPENJPH_ENCODER)
    set(WITH_${codec} OFF CACHE BOOL "" FORCE)
endforeach()
foreach(flag ENABLE_PLUGIN_LOADING WITH_EXAMPLES WITH_GDK_PIXBUF BUILD_TESTING BUILD_DOCUMENTATION
        WITH_LIBSHARPYUV WITH_UNCOMPRESSED_CODEC WITH_HEADER_COMPRESSION ENABLE_PARALLEL_TILE_DECODING)
    set(${flag} OFF CACHE BOOL "" FORCE)
endforeach()
set(WITH_LIBDE265 ON CACHE BOOL "" FORCE)
set(WITH_LIBDE265_PLUGIN OFF CACHE BOOL "" FORCE)
FetchContent_Declare(libheif
    URL "${CMAKE_CURRENT_SOURCE_DIR}/vendor/libheif-1.23.5.tar.gz"
    URL_HASH SHA256=3be49950c75d3fd9318ba775e8253248dd9e08c61ea955fca81b6b89c02d6b2b)
FetchContent_MakeAvailable(libheif)
target_include_directories(heif PUBLIC "$<BUILD_INTERFACE:${libheif_SOURCE_DIR}/libheif/api>" "$<BUILD_INTERFACE:${libheif_BINARY_DIR}>")

if(ANDROID)
    # Android packages unversioned .so files, including their runtime dependencies.
    set_target_properties(heif de265 PROPERTIES NO_SONAME OFF VERSION "" SOVERSION "")
    add_library(alpha_heif SHARED heif_decoder.cpp heif_c_api.cpp heif_jni.cpp)
    target_link_libraries(alpha_heif PRIVATE heif jnigraphics)
    target_link_options(alpha_heif PRIVATE "-Wl,-z,max-page-size=16384")
else()
    enable_testing()
    add_executable(heif_decoder_test heif_decoder.cpp heif_c_api.cpp ../../test/cpp/heif_decoder_test.cpp)
    target_include_directories(heif_decoder_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(heif_decoder_test PRIVATE heif)
    add_test(NAME heif_decoder COMMAND heif_decoder_test "${CMAKE_CURRENT_SOURCE_DIR}/../../androidTest/assets")
    add_executable(heif_c_api_test heif_decoder.cpp heif_c_api.cpp ../../test/cpp/heif_c_api_test.c)
    target_include_directories(heif_c_api_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(heif_c_api_test PRIVATE heif)
    add_test(NAME heif_c_api COMMAND heif_c_api_test "${CMAKE_CURRENT_SOURCE_DIR}/../../androidTest/assets")
endif()
