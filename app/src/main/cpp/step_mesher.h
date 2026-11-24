// Reads a STEP file with Open CASCADE and turns its surfaces into triangles.
#pragma once

#include <string>
#include <vector>

struct StepMesh {
    // Triangle soup: 9 floats per triangle (3 vertices x xyz), in millimeters.
    std::vector<float> positions;
    // One normal per vertex, same layout as positions.
    std::vector<float> normals;
};

// Returns true on success. On failure, `error` holds a short human-readable reason.
bool MeshStepFile(const char* path, StepMesh& out, std::string& error);
