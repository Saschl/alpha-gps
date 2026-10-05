#include "heif_decoder.h"
#include <algorithm>
#include <stdexcept>
#include <string>
#include <mutex>

namespace alpha {
namespace {
void check(heif_error error) {
    if (error.code != heif_error_Ok) {
        throw std::runtime_error("HEIF decode error " + std::to_string(error.code) + "/" +
                                 std::to_string(error.subcode));
    }
}
void initialize() {
    static std::once_flag initialized;
    std::call_once(initialized, [] { check(heif_init(nullptr)); });
}
}

Image decodeHeif(const char* path, const uint8_t* bytes, size_t length, int maxDimension,
                 uint64_t memoryBudget, const std::function<bool()>& cancelled) {
    initialize();
    if (memoryBudget < 1024 * 1024 || (maxDimension != 0 && maxDimension != 640 && maxDimension != 2048)) {
        throw std::runtime_error("Invalid HEIF decode limits");
    }
    auto context = std::unique_ptr<heif_context, decltype(&heif_context_free)>(heif_context_alloc(), heif_context_free);
    if (!context) throw std::bad_alloc();
    auto* limits = heif_context_get_security_limits(context.get());
    limits->max_image_size_pixels = maxDimension ? 8192ULL * 8192 : 100000000;
    limits->max_memory_block_size = std::min<uint64_t>(memoryBudget, 512ULL * 1024 * 1024);
    limits->max_total_memory = memoryBudget;
    heif_context_set_max_decoding_threads(context.get(), 1);
    if (path) check(heif_context_read_from_file(context.get(), path, nullptr));
    else check(heif_context_read_from_memory_without_copy(context.get(), bytes, length, nullptr));

    heif_image_handle* rawHandle = nullptr;
    check(heif_context_get_primary_image_handle(context.get(), &rawHandle));
    auto handle = std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)>(rawHandle, heif_image_handle_release);
    const int width = heif_image_handle_get_width(handle.get());
    const int height = heif_image_handle_get_height(handle.get());
    if (maxDimension && (width > 8192 || height > 8192)) {
        throw std::runtime_error("Invalid HEIF thumbnail dimensions");
    }
    if (width <= 0 || height <= 0 || static_cast<uint64_t>(width) * height > memoryBudget / 12) {
        throw std::runtime_error("Insufficient memory for full-resolution HEIF decode");
    }
    if (cancelled()) throw std::runtime_error("HEIF decode cancelled");
    auto options = std::unique_ptr<heif_decoding_options, decltype(&heif_decoding_options_free)>(
        heif_decoding_options_alloc(), heif_decoding_options_free);
    if (!options) throw std::bad_alloc();
    options->convert_hdr_to_8bit = 1;
    options->autocorrect_broken_input = 1; // Sony HIF NCLX/VUI range mismatch.
    options->num_codec_threads = 2;
    options->progress_user_data = const_cast<std::function<bool()>*>(&cancelled);
    options->cancel_decoding = [](void* user) { return (*static_cast<std::function<bool()>*>(user))() ? 1 : 0; };
    // Default output NCLX is sRGB; HEIF crop/rotation/mirroring are applied by libheif.
    heif_image* rawImage = nullptr;
    const auto error = heif_decode_image(handle.get(), &rawImage, heif_colorspace_RGB,
                                        heif_chroma_interleaved_RGBA, options.get());
    Image image(rawImage, heif_image_release);
    check(error);
    if (cancelled()) throw std::runtime_error("HEIF decode cancelled");
    const int decodedWidth = heif_image_get_primary_width(image.get());
    const int decodedHeight = heif_image_get_primary_height(image.get());
    const int longest = std::max(decodedWidth, decodedHeight);
    if (maxDimension && longest > maxDimension) {
        heif_image* scaled = nullptr;
        const auto scaleError = heif_image_scale_image(image.get(), &scaled,
              std::max(1, decodedWidth * maxDimension / longest),
              std::max(1, decodedHeight * maxDimension / longest), nullptr);
        Image scaledImage(scaled, heif_image_release);
        check(scaleError);
        image = std::move(scaledImage);
    }
    return image;
}

std::vector<uint8_t> readHeifExif(const char* path) {
    initialize();
    auto context = std::unique_ptr<heif_context, decltype(&heif_context_free)>(heif_context_alloc(), heif_context_free);
    if (!context) throw std::bad_alloc();
    auto* limits = heif_context_get_security_limits(context.get());
    limits->max_image_size_pixels = 100000000;
    limits->max_memory_block_size = 512ULL * 1024 * 1024;
    limits->max_total_memory = 512ULL * 1024 * 1024;
    check(heif_context_read_from_file(context.get(), path, nullptr));
    heif_image_handle* rawHandle = nullptr;
    check(heif_context_get_primary_image_handle(context.get(), &rawHandle));
    auto handle = std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)>(rawHandle, heif_image_handle_release);
    heif_item_id id;
    if (!heif_image_handle_get_list_of_metadata_block_IDs(handle.get(), "Exif", &id, 1)) return {};
    const auto size = heif_image_handle_get_metadata_size(handle.get(), id);
    if (size < 4 || size > 1024 * 1024) throw std::runtime_error("Invalid HEIF EXIF size");
    std::vector<uint8_t> bytes(size);
    check(heif_image_handle_get_metadata(handle.get(), id, bytes.data()));
    const uint64_t offset = 4ULL + (static_cast<uint32_t>(bytes[0]) << 24) +
        (static_cast<uint32_t>(bytes[1]) << 16) + (static_cast<uint32_t>(bytes[2]) << 8) + bytes[3];
    if (offset >= size) throw std::runtime_error("Invalid HEIF EXIF offset");
    std::vector<uint8_t> exif = {'E', 'x', 'i', 'f', 0, 0};
    exif.insert(exif.end(), bytes.begin() + offset, bytes.end());
    return exif;
}
}
