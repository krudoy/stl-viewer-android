# STL Viewer for Android

A lightweight Android app for previewing 3D model files. STL (binary and ASCII) works today; STEP is planned.

## Using it

- **Open** picks a file with the system file picker, or use "Open with" from a file manager, email or chat app.
- **One finger drag** rotates the model.
- **Pinch** zooms toward your fingers.
- **Two finger drag** pans.
- **Double tap** or **Reset** returns to the default view.
- **Orientation cube** (top right) turns with the model. Tap a face, edge or corner to look from that direction; faces are named Front (-Y), Back, Left, Right (+X), Top (+Z) and Bottom.
- A mouse wheel zooms too (emulator, Chromebook, DeX).

`samples/torus.stl` is a small file for trying it out.

## Getting the APK

Every push and pull request is built by GitHub Actions. Open the run under the **Actions** tab and download the `stl-viewer-debug-apk` artifact, then install it on a phone with "Install unknown apps" allowed.

## Building locally

Requires JDK 17+ and the Android SDK (Android Studio sets both up).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # parser tests
```

## Layout

- `model/StlParser.kt`: binary and ASCII STL reader. Detects the format by file size rather than the `solid` header, and recomputes normals from vertex winding.
- `render/Camera.kt`: orbit camera with trackball rotation, pan and focus-point zoom.
- `render/OrientationCube.kt`: the corner navigation cube, drawing and tap picking.
- `render/ModelRenderer.kt`: OpenGL ES 2.0 renderer, two-sided shading.
- `render/ModelView.kt`: `GLSurfaceView` that turns touch gestures into camera moves.
- `MainActivity.kt`: file picker, background loading, and the overlay UI.
