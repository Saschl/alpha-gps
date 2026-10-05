#pragma once

#include <libheif/heif.h>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

namespace alpha {
using Image = std::unique_ptr<heif_image, decltype(&heif_image_release)>;

Image decodeHeif(const char* path, const uint8_t* bytes, size_t length, int maxDimension,
                 uint64_t memoryBudget, const std::function<bool()>& cancelled);
std::vector<uint8_t> readHeifExif(const char* path);
}
