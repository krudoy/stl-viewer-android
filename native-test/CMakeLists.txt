# Desktop build of the STEP mesher for quick testing without Android:
#   scripts/build-occt.sh host
#   cmake -S native-test -B build/native-test -G Ninja && cmake --build build/native-test
#   build/native-test/step_mesh_test some.step
cmake_minimum_required(VERSION 3.22.1)
project(step_mesh_test CXX)
set(CMAKE_CXX_STANDARD 17)

find_package(OpenCASCADE CONFIG REQUIRED PATHS "${CMAKE_CURRENT_SOURCE_DIR}/../third_party/occt/host" NO_DEFAULT_PATH)
add_executable(step_mesh_test main.cpp ../app/src/main/cpp/step_mesher.cpp)
target_include_directories(step_mesh_test PRIVATE ${OpenCASCADE_INCLUDE_DIR} ../app/src/main/cpp)
target_link_libraries(step_mesh_test PRIVATE
    -Wl,--start-group TKSTEP TKSTEP209 TKSTEPAttr TKSTEPBase TKXSBase TKMesh TKShHealing TKTopAlgo TKGeomAlgo TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel -Wl,--end-group
    pthread dl)
