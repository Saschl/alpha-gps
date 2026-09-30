#include "heif_c_api.h"
#include <android/bitmap.h>
#include <jni.h>
#include <cstring>
#include <memory>

namespace {
using Image = std::unique_ptr<alpha_heif_image, decltype(&alpha_heif_image_release)>;
using Bytes = std::unique_ptr<alpha_heif_bytes, decltype(&alpha_heif_bytes_release)>;

void throwIo(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    const auto exception = env->FindClass("java/io/IOException");
    if (exception) env->ThrowNew(exception, message);
}

class UtfChars {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value),
        chars_(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}
    ~UtfChars() { if (chars_) env_->ReleaseStringUTFChars(value_, chars_); }
    const char* get() const { return chars_; }
private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_;
};

class Cancellation {
public:
    Cancellation(JNIEnv* env, jobject callback) : callback_(callback ? env->NewGlobalRef(callback) : nullptr) {
        env->GetJavaVM(&vm_);
        if (callback_) {
            const auto type = env->GetObjectClass(callback_);
            if (type) {
                method_ = env->GetMethodID(type, "getAsBoolean", "()Z");
                env->DeleteLocalRef(type);
            }
        }
    }
    ~Cancellation() {
        JNIEnv* env = nullptr;
        if (callback_ && vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(callback_);
        }
    }
    bool operator()() const {
        if (!callback_) return false;
        JNIEnv* env = nullptr;
        const auto status = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        const bool attach = status == JNI_EDETACHED;
        if (attach) {
            if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
        } else if (status != JNI_OK) return true;
        const bool cancelled = env->CallBooleanMethod(callback_, method_);
        const bool failed = env->ExceptionCheck();
        if (failed) env->ExceptionClear();
        if (attach) vm_->DetachCurrentThread();
        return cancelled || failed;
    }
private:
    JavaVM* vm_ = nullptr;
    jobject callback_;
    jmethodID method_ = nullptr;
};

jobject bitmapFromImage(JNIEnv* env, jclass decoder, const alpha_heif_image* image) {
    const int width = alpha_heif_image_width(image);
    const int height = alpha_heif_image_height(image);
    const auto create = env->GetStaticMethodID(decoder, "createBitmap", "(II)Landroid/graphics/Bitmap;");
    if (!create) return nullptr;
    jobject bitmap = env->CallStaticObjectMethod(decoder, create, width, height);
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throwIo(env, "Cannot access decoded bitmap");
        return nullptr;
    }
    const int stride = alpha_heif_image_stride(image);
    const auto* source = alpha_heif_image_pixels(image);
    const bool premultiplied = alpha_heif_image_premultiplied(image);
    for (int y = 0; y < height; ++y) {
        auto* row = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        std::memcpy(row, source + static_cast<size_t>(y) * stride, static_cast<size_t>(width) * 4);
        if (!premultiplied) {
            for (int x = 0; x < width; ++x) {
                auto* p = row + x * 4;
                if (p[3] != 255) for (int c = 0; c < 3; ++c) p[c] = (p[c] * p[3] + 127) / 255;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_sasch_cameragps_heif_HeifDecoder_decodeFile(JNIEnv* env, jclass decoder, jstring path,
                                                  jlong memoryBudget, jobject callback) {
    // Guard the signed Java value before converting to the C API's uint64_t.
    if (memoryBudget <= 0) { throwIo(env, "Invalid HEIF input"); return nullptr; }
    UtfChars filename(env, path);
    if (env->ExceptionCheck()) return nullptr;
    Cancellation cancelled(env, callback);
    if (env->ExceptionCheck()) return nullptr;
    alpha_heif_error error{};
    const Image image(alpha_heif_decode_file(filename.get(), memoryBudget,
        [](void* context) { return (*static_cast<Cancellation*>(context))() ? 1 : 0; },
        &cancelled, &error), alpha_heif_image_release);
    if (!image) { throwIo(env, error.message); return nullptr; }
    return bitmapFromImage(env, decoder, image.get());
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_sasch_cameragps_heif_HeifDecoder_decodeThumbnail(JNIEnv* env, jclass decoder, jbyteArray encoded,
                                                       jlong memoryBudget) {
    if (memoryBudget <= 0) { throwIo(env, "Invalid HEIF thumbnail"); return nullptr; }
    const auto length = encoded ? env->GetArrayLength(encoded) : 0;
    auto* bytes = length ? env->GetByteArrayElements(encoded, nullptr) : nullptr;
    if (env->ExceptionCheck()) return nullptr;
    alpha_heif_error error{};
    const Image image(alpha_heif_decode_thumbnail(reinterpret_cast<const uint8_t*>(bytes), length,
        memoryBudget, &error), alpha_heif_image_release);
    if (bytes) env->ReleaseByteArrayElements(encoded, bytes, JNI_ABORT);
    if (!image) { throwIo(env, error.message); return nullptr; }
    return bitmapFromImage(env, decoder, image.get());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sasch_cameragps_heif_HeifDecoder_readExif(JNIEnv* env, jclass, jstring path) {
    UtfChars filename(env, path);
    if (env->ExceptionCheck()) return nullptr;
    alpha_heif_error error{};
    const Bytes bytes(alpha_heif_read_exif(filename.get(), &error), alpha_heif_bytes_release);
    if (!bytes) { throwIo(env, error.message); return nullptr; }
    const auto size = static_cast<jsize>(alpha_heif_bytes_size(bytes.get()));
    auto result = env->NewByteArray(size);
    if (result && size) env->SetByteArrayRegion(result, 0, size,
        reinterpret_cast<const jbyte*>(alpha_heif_bytes_data(bytes.get())));
    return result;
}
