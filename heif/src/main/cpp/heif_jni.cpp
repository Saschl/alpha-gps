#include "heif_decoder.h"
#include <android/bitmap.h>
#include <jni.h>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace {
void throwIo(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    const auto exception = env->FindClass("java/io/IOException");
    if (exception) env->ThrowNew(exception, message);
}

// C++ exceptions must not cross the JNI boundary; preserve any pending Java exception.
    template<typename F>
    auto protect(JNIEnv *env, F action) noexcept -> decltype(action()) {
        try {
            return action();
        } catch (const std::bad_alloc &) {
            throwIo(env, "Insufficient memory for HEIF decode");
        } catch (const std::exception &failure) {
            throwIo(env, failure.what());
        } catch (...) {
            throwIo(env, "HEIF decode failed");
        }
        return nullptr;
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

    class ByteArrayElements {
    public:
        ByteArrayElements(JNIEnv *env, jbyteArray value) : env_(env), value_(value),
                                                           bytes_(env->GetByteArrayElements(value, nullptr)) {
        }

        ~ByteArrayElements() {
            if (bytes_) env_->ReleaseByteArrayElements(value_, bytes_, JNI_ABORT);
        }

        ByteArrayElements(const ByteArrayElements &) = delete;

        ByteArrayElements &operator=(const ByteArrayElements &) = delete;

        const uint8_t *get() const {
            return reinterpret_cast<const uint8_t *>(bytes_);
        }

    private:
        JNIEnv *env_;
        jbyteArray value_;
        jbyte *bytes_;
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

    jobject bitmapFromImage(JNIEnv *env, jclass decoder, heif_image *image) {
        const int width = heif_image_get_primary_width(image);
        const int height = heif_image_get_primary_height(image);
        int stride = 0;
        const auto *source = heif_image_get_plane_readonly(image, heif_channel_interleaved, &stride);
        if (!source || stride < width * 4) throw std::runtime_error("Invalid decoded HEIF pixels");
        const bool premultiplied = heif_image_is_premultiplied_alpha(image);
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

    jobject decodeMemory(JNIEnv *env, jclass decoder, jbyteArray encoded, jlong memoryBudget,
            jsize maxBytes, int maxDimension) {
        const auto length = encoded ? env->GetArrayLength(encoded) : 0;
        if (memoryBudget <= 0 || length <= 0 || length > maxBytes) {
            throwIo(env, "Invalid HEIF preview");
            return nullptr;
        }
        const ByteArrayElements bytes(env, encoded);
        if (env->ExceptionCheck()) return nullptr;
        if (!bytes.get()) throw std::bad_alloc();
        const auto image = alpha::decodeHeif(nullptr, bytes.get(), length, maxDimension,
                memoryBudget, [] {
                    return false;
                });
        return bitmapFromImage(env, decoder, image.get());
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_sasch_cameragps_heif_HeifDecoder_decodeFile(JNIEnv* env, jclass decoder, jstring path,
                                                  jlong memoryBudget, jobject callback) {
    return protect(env, [&]() -> jobject {
        // Guard the signed Java value before converting to the decoder's uint64_t.
        if (memoryBudget <= 0) throw std::invalid_argument("Invalid HEIF input");
        UtfChars filename(env, path);
        if (env->ExceptionCheck()) return nullptr;
        if (!filename.get() || !*filename.get()) throw std::invalid_argument("Invalid HEIF path");
        Cancellation cancelled(env, callback);
        if (env->ExceptionCheck()) return nullptr;
        const auto image = alpha::decodeHeif(filename.get(), nullptr, 0, 0, memoryBudget,
                [&] {
                    return cancelled();
                });
        return bitmapFromImage(env, decoder, image.get());
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_sasch_cameragps_heif_HeifDecoder_decodeThumbnail(JNIEnv* env, jclass decoder, jbyteArray encoded,
                                                       jlong memoryBudget) {
    return protect(env, [&] {
        return decodeMemory(env, decoder, encoded, memoryBudget, 512 * 1024, 640);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_sasch_cameragps_heif_HeifDecoder_decodePreview(JNIEnv *env, jclass decoder, jbyteArray encoded,
        jlong memoryBudget) {
    return protect(env, [&] {
        return decodeMemory(env, decoder, encoded, memoryBudget, 8 * 1024 * 1024, 2048);
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_sasch_cameragps_heif_HeifDecoder_readExif(JNIEnv* env, jclass, jstring path) {
    return protect(env, [&]() -> jbyteArray {
        UtfChars filename(env, path);
        if (env->ExceptionCheck()) return nullptr;
        if (!filename.get() || !*filename.get()) throw std::invalid_argument("Invalid HEIF path");
        const auto bytes = alpha::readHeifExif(filename.get());
        const auto size = static_cast<jsize>(bytes.size());
        auto result = env->NewByteArray(size);
        if (result && size)
            env->SetByteArrayRegion(result, 0, size,
                    reinterpret_cast<const jbyte *>(bytes.data()));
        return result;
    });
}
