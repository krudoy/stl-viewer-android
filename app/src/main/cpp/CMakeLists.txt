cmake_minimum_required(VERSION 3.22.1)
project(stepmesh CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(stepmesh SHARED step_jni.cpp)

# OCCT static libraries built by scripts/build-occt.sh for this ABI.
set(OCCT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/occt/${ANDROID_ABI}")
find_package(OpenCASCADE CONFIG QUIET PATHS "${OCCT_ROOT}" NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)

if(OpenCASCADE_FOUND)
    message(STATUS "STEP support: OCCT ${OpenCASCADE_VERSION} from ${OCCT_ROOT}")
    target_sources(stepmesh PRIVATE step_mesher.cpp)
    target_compile_definitions(stepmesh PRIVATE HAVE_OCCT)
    target_include_directories(stepmesh PRIVATE ${OpenCASCADE_INCLUDE_DIR})
    # Static OCCT toolkits reference each other in both directions, so link them as a group.
    target_link_libraries(stepmesh PRIVATE
        -Wl,--start-group
        TKSTEP TKSTEP209 TKSTEPAttr TKSTEPBase TKXSBase TKMesh TKShHealing TKTopAlgo TKGeomAlgo TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel
        -Wl,--end-group
        log)
    # Drop unused OCCT code and symbols; keeps the library a fraction of its size.
    target_link_options(stepmesh PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -s)
else()
    message(WARNING "STEP support: OCCT not found in ${OCCT_ROOT}; building without it. "
                    "Run scripts/build-occt.sh ${ANDROID_ABI} to enable STEP import.")
    target_link_libraries(stepmesh PRIVATE log)
endif()
