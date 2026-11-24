# STL Viewer for Android

A lightweight Android app for previewing 3D model files: STL (binary and ASCII), 3MF (including multi-part projects from Bambu Studio, PrusaSlicer and Orca) and STEP (AP203/AP214/AP242 solids and surfaces, meshed with Open CASCADE).

## Using it

- **Open** picks a file with the system file picker, or use "Open with" from a file manager, email or chat app.
- **One finger drag** rotates the model.
- **Pinch** zooms toward your fingers.
- **Two finger drag** pans.
- **Two finger twist** spins the model flat on the screen (it starts after about 10° so pinching stays steady). The ↺ ↻ buttons under the cube spin it by 90°.
- **Double tap** or **Reset** returns to the default view.
- **Orientation cube** (top right) turns with the model. Tap a face, edge or corner to look from that direction; faces are named Front (-Y), Back, Left, Right (+X), Top (+Z) and Bottom.
- A mouse wheel zooms too (emulator, Chromebook, DeX).

`samples/torus.stl` and `samples/screw.step` are small files for trying it out (the screw comes from the Open CASCADE sample data).

## Getting the APK

Every push and pull request is built by GitHub Actions. Open the run under the **Actions** tab and download the `stl-viewer-debug-apk` artifact, then install it on a phone with "Install unknown apps" allowed.

## Building locally

Requires JDK 17+, the Android SDK with NDK 27.2.12479018, CMake and Ninja (Android Studio's SDK Manager installs all of these).

STEP support needs Open CASCADE built for each CPU type once (about 20 minutes each). Without it the app still builds, and STEP files show a "not included in this build" message.

```sh
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018
scripts/build-occt.sh arm64-v8a      # phones
scripts/build-occt.sh x86_64         # emulator
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest          # parser tests
```

To try the STEP mesher on a desktop without Android:

```sh
scripts/build-occt.sh host
cmake -S native-test -B build/native-test -G Ninja && cmake --build build/native-test
build/native-test/step_mesh_test samples/screw.step
```

## Layout

- `model/ModelLoader.kt`: detects the format from the first bytes and picks a parser.
- `model/ThreeMfParser.kt`: 3MF reader (core spec plus the production extension's `p:path` components), applies build and component transforms, converts units to mm.
- `model/StepNative.kt` + `app/src/main/cpp/`: STEP import. `step_mesher.cpp` reads the file with OCCT's `STEPControl_Reader`, meshes it with `BRepMesh_IncrementalMesh` (deflection 0.1% of the model size, ~17° angular) and returns triangles with smooth normals.
- `model/StlParser.kt`: binary and ASCII STL reader. Detects the format by file size rather than the `solid` header, and recomputes normals from vertex winding.
- `render/Camera.kt`: orbit camera with trackball rotation, pan and focus-point zoom.
- `render/OrientationCube.kt`: the corner navigation cube, drawing and tap picking.
- `render/ModelRenderer.kt`: OpenGL ES 2.0 renderer, two-sided shading.
- `render/ModelView.kt`: `GLSurfaceView` that turns touch gestures into camera moves.
- `MainActivity.kt`: file picker, background loading, and the overlay UI.

## Licenses

STEP support links [Open CASCADE Technology](https://dev.opencascade.org/) 7.7.2, which is LGPL 2.1 with an additional exception. It is linked statically into `libstepmesh.so`.
