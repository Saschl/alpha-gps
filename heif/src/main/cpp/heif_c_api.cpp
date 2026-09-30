#include "heif_c_api.h"
#include "heif_decoder.h"
#include <cstdio>
#include <stdexcept>
#include <utility>

struct alpha_heif_image {
    alpha::Image image;
    const uint8_t* pixels;
    int stride;

    explicit alpha_heif_image(alpha::Image decoded) : image(std::move(decoded)) {
        pixels = heif_image_get_plane_readonly(image.get(), heif_channel_interleaved, &stride);
        if (!pixels || stride < heif_image_get_primary_width(image.get()) * 4) {
            throw std::runtime_error("Invalid decoded HEIF pixels");
        }
    }
};

struct alpha_heif_bytes { std::vector<uint8_t> bytes; };

namespace {
template<typename F> auto protect(alpha_heif_error* error, F action) noexcept -> decltype(action()) {
    if (error) error->message[0] = '\0';
    try {
        return action();
    } catch (const std::bad_alloc&) {
        if (error) std::snprintf(error->message, sizeof(error->message), "Insufficient memory for HEIF decode");
    } catch (const std::exception& failure) {
        if (error) std::snprintf(error->message, sizeof(error->message), "%s", failure.what());
    } catch (...) {
        if (error) std::snprintf(error->message, sizeof(error->message), "HEIF decode failed");
    }
    return nullptr;
}
}

alpha_heif_image* alpha_heif_decode_file(const char* path, uint64_t memory_budget,
    alpha_heif_cancelled cancelled, void* context, alpha_heif_error* error) {
    return protect(error, [&] {
        if (!path || !*path) throw std::invalid_argument("Invalid HEIF path");
        return new alpha_heif_image(alpha::decodeHeif(path, nullptr, 0, 0, memory_budget,
            [&] { return cancelled && cancelled(context); }));
    });
}

alpha_heif_image* alpha_heif_decode_thumbnail(const uint8_t* bytes, size_t length,
    uint64_t memory_budget, alpha_heif_error* error) {
    return protect(error, [&] {
        if (!bytes || !length || length > 512 * 1024) throw std::invalid_argument("Invalid HEIF thumbnail size");
        return new alpha_heif_image(alpha::decodeHeif(nullptr, bytes, length, 640, memory_budget,
            [] { return false; }));
    });
}

int alpha_heif_image_width(const alpha_heif_image* image) { return heif_image_get_primary_width(image->image.get()); }
int alpha_heif_image_height(const alpha_heif_image* image) { return heif_image_get_primary_height(image->image.get()); }
int alpha_heif_image_stride(const alpha_heif_image* image) { return image->stride; }
int alpha_heif_image_premultiplied(const alpha_heif_image* image) { return heif_image_is_premultiplied_alpha(image->image.get()); }
const uint8_t* alpha_heif_image_pixels(const alpha_heif_image* image) { return image->pixels; }
void alpha_heif_image_release(alpha_heif_image* image) { delete image; }

alpha_heif_bytes* alpha_heif_read_exif(const char* path, alpha_heif_error* error) {
    return protect(error, [&] {
        if (!path || !*path) throw std::invalid_argument("Invalid HEIF path");
        return new alpha_heif_bytes{alpha::readHeifExif(path)};
    });
}
const uint8_t* alpha_heif_bytes_data(const alpha_heif_bytes* bytes) { return bytes->bytes.data(); }
size_t alpha_heif_bytes_size(const alpha_heif_bytes* bytes) { return bytes->bytes.size(); }
void alpha_heif_bytes_release(alpha_heif_bytes* bytes) { delete bytes; }
