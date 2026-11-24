// Meshes each STEP file given on the command line and prints a summary.
// Exits non-zero if any file fails or yields an inconsistent mesh.
#include <chrono>
#include <cmath>
#include <cstdio>

#include "step_mesher.h"

int main(int argc, char** argv) {
    int failures = 0;
    for (int i = 1; i < argc; ++i) {
        auto start = std::chrono::steady_clock::now();
        StepMesh mesh;
        std::string error;
        bool ok = MeshStepFile(argv[i], mesh, error);
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        if (!ok) {
            std::printf("FAIL %s: %s\n", argv[i], error.c_str());
            ++failures;
            continue;
        }
        float lo[3] = {1e30f, 1e30f, 1e30f}, hi[3] = {-1e30f, -1e30f, -1e30f};
        bool sane = mesh.positions.size() % 9 == 0 && mesh.normals.size() == mesh.positions.size();
        for (size_t k = 0; k < mesh.positions.size(); ++k) {
            float v = mesh.positions[k];
            if (!std::isfinite(v)) sane = false;
            lo[k % 3] = std::fmin(lo[k % 3], v);
            hi[k % 3] = std::fmax(hi[k % 3], v);
        }
        for (size_t k = 0; k + 2 < mesh.normals.size(); k += 3) {
            float len = std::sqrt(mesh.normals[k] * mesh.normals[k] + mesh.normals[k + 1] * mesh.normals[k + 1] +
                                  mesh.normals[k + 2] * mesh.normals[k + 2]);
            if (std::fabs(len - 1.0f) > 1e-3f) sane = false;
        }
        // Winding and normals should agree: the face normal from vertex order should
        // point the same way as the surface normals OCCT reports.
        size_t agree = 0, total = mesh.positions.size() / 9;
        for (size_t t = 0; t < total; ++t) {
            const float* p = &mesh.positions[t * 9];
            const float* n = &mesh.normals[t * 9];
            float ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
            float bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
            float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
            float d = cx * (n[0] + n[3] + n[6]) + cy * (n[1] + n[4] + n[7]) + cz * (n[2] + n[5] + n[8]);
            if (d >= 0) ++agree;
        }
        if (total > 0 && agree * 100 < total * 95) sane = false;
        std::printf("%s %s: %zu triangles, %.1f%% consistent winding, size %.2f x %.2f x %.2f mm, %.0f ms\n",
                    sane ? "OK  " : "BAD ", argv[i], total, total ? 100.0 * agree / total : 0.0,
                    hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2], ms);
        if (!sane) ++failures;
    }
    return failures == 0 ? 0 : 1;
}
