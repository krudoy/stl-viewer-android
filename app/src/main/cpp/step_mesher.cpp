#include "step_mesher.h"

#include <BRepBndLib.hxx>
#include <BRepLib_ToolTriangulatedShape.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Tool.hxx>
#include <Bnd_Box.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <Interface_Static.hxx>
#include <Poly_Triangulation.hxx>
#include <STEPControl_Reader.hxx>
#include <Standard_Failure.hxx>
#include <TopExp_Explorer.hxx>
#include <TopLoc_Location.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <gp_Trsf.hxx>

#include <algorithm>
#include <cmath>
#include <exception>

namespace {

// Mesh density relative to the model's bounding box diagonal. 0.1% keeps curved
// parts smooth on a phone screen without exploding the triangle count.
constexpr double kRelativeDeflection = 0.001;
// Max angle between neighboring facets on curved surfaces, in radians (~17 deg).
constexpr double kAngularDeflection = 0.3;

void AppendFace(const TopoDS_Face& face, StepMesh& out) {
    TopLoc_Location loc;
    Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, loc);
    if (tri.IsNull() || tri->NbTriangles() == 0) return;

    if (!tri->HasNormals()) {
        BRepLib_ToolTriangulatedShape::ComputeNormals(face, tri);
    }
    const gp_Trsf trsf = loc.Transformation();
    const bool reversed = face.Orientation() == TopAbs_REVERSED;
    // A mirroring placement flips winding just like a reversed face does.
    const bool flipWinding = reversed != trsf.IsNegative();

    out.positions.reserve(out.positions.size() + tri->NbTriangles() * 9);
    out.normals.reserve(out.normals.size() + tri->NbTriangles() * 9);

    for (int i = 1; i <= tri->NbTriangles(); ++i) {
        int n[3];
        tri->Triangle(i).Get(n[0], n[1], n[2]);
        if (flipWinding) std::swap(n[1], n[2]);
        for (int k = 0; k < 3; ++k) {
            gp_Pnt p = tri->Node(n[k]).Transformed(trsf);
            out.positions.push_back(static_cast<float>(p.X()));
            out.positions.push_back(static_cast<float>(p.Y()));
            out.positions.push_back(static_cast<float>(p.Z()));

            gp_Dir d = tri->HasNormals() ? tri->Normal(n[k]) : gp_Dir(0, 0, 1);
            d.Transform(trsf);
            if (reversed) d.Reverse();
            out.normals.push_back(static_cast<float>(d.X()));
            out.normals.push_back(static_cast<float>(d.Y()));
            out.normals.push_back(static_cast<float>(d.Z()));
        }
    }
}

}  // namespace

bool MeshStepFile(const char* path, StepMesh& out, std::string& error) {
    out.positions.clear();
    out.normals.clear();
    try {
        STEPControl_Reader reader;
        // Bring everything to millimeters regardless of the file's own units.
        Interface_Static::SetCVal("xstep.cascade.unit", "MM");
        if (reader.ReadFile(path) != IFSelect_RetDone) {
            error = "Not a readable STEP file";
            return false;
        }
        if (reader.TransferRoots() == 0) {
            error = "The STEP file has no shapes to show";
            return false;
        }
        TopoDS_Shape shape = reader.OneShape();
        if (shape.IsNull()) {
            error = "The STEP file has no shapes to show";
            return false;
        }

        Bnd_Box box;
        BRepBndLib::Add(shape, box);
        if (box.IsVoid()) {
            error = "The STEP file has no surfaces to show";
            return false;
        }
        const double diagonal = std::sqrt(box.SquareExtent());
        const double deflection = std::max(diagonal * kRelativeDeflection, 1e-6);
        BRepMesh_IncrementalMesh mesher(shape, deflection, false, kAngularDeflection, true);

        for (TopExp_Explorer ex(shape, TopAbs_FACE); ex.More(); ex.Next()) {
            AppendFace(TopoDS::Face(ex.Current()), out);
        }
        if (out.positions.empty()) {
            error = "The STEP file has no surfaces to show (wireframe or points only?)";
            return false;
        }
        return true;
    } catch (const Standard_Failure& e) {
        error = std::string("STEP import failed: ") + e.GetMessageString();
    } catch (const std::bad_alloc&) {
        error = "Not enough memory to load this STEP file";
    } catch (const std::exception& e) {
        error = std::string("STEP import failed: ") + e.what();
    } catch (...) {
        error = "STEP import failed";
    }
    out.positions.clear();
    out.normals.clear();
    return false;
}
