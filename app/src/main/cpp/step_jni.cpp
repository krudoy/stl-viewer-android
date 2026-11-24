// JNI bridge for com.krudoy.stlviewer.model.StepNative.
#include <jni.h>

#include <string>

#ifdef HAVE_OCCT
#include "step_mesher.h"
#endif

namespace {

void ThrowParseError(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("com/krudoy/stlviewer/model/StlParseException");
    if (cls == nullptr) {
        env->ExceptionClear();
        cls = env->FindClass("java/io/IOException");
    }
    env->ThrowNew(cls, message.c_str());
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_krudoy_stlviewer_model_StepNative_nativeAvailable(JNIEnv*, jclass) {
#ifdef HAVE_OCCT
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// Returns {positions, normals}, or throws StlParseException.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_krudoy_stlviewer_model_StepNative_nativeMesh(JNIEnv* env, jclass, jstring jpath) {
#ifdef HAVE_OCCT
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    StepMesh mesh;
    std::string error;
    const bool ok = MeshStepFile(path, mesh, error);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ok) {
        ThrowParseError(env, error);
        return nullptr;
    }

    const auto n = static_cast<jsize>(mesh.positions.size());
    jfloatArray positions = env->NewFloatArray(n);
    jfloatArray normals = positions ? env->NewFloatArray(n) : nullptr;
    if (positions == nullptr || normals == nullptr) return nullptr;  // OutOfMemoryError pending
    env->SetFloatArrayRegion(positions, 0, n, mesh.positions.data());
    // Free the native copy early; large models can be hundreds of MB.
    std::vector<float>().swap(mesh.positions);
    env->SetFloatArrayRegion(normals, 0, n, mesh.normals.data());

    jclass floatArrayClass = env->FindClass("[F");
    jobjectArray result = env->NewObjectArray(2, floatArrayClass, nullptr);
    if (result == nullptr) return nullptr;
    env->SetObjectArrayElement(result, 0, positions);
    env->SetObjectArrayElement(result, 1, normals);
    return result;
#else
    (void)jpath;
    ThrowParseError(env, "STEP support was not included in this build");
    return nullptr;
#endif
}
