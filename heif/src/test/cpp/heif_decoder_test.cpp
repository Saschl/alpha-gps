#include "heif_decoder.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
template<typename F> void rejects(F action) {
    bool failed = false;
    try { action(); } catch (const std::exception&) { failed = true; }
    require(failed, "Invalid input was accepted");
}
void verifyRed(const alpha::Image& image, int width, int height) {
    require(heif_image_get_primary_width(image.get()) == width, "Wrong image width");
    require(heif_image_get_primary_height(image.get()) == height, "Wrong image height");
    int stride = 0;
    const auto* pixels = heif_image_get_plane_readonly(image.get(), heif_channel_interleaved, &stride);
    require(pixels && stride >= width * 4, "Missing RGB pixels");
    const auto* center = pixels + (height / 2) * stride + (width / 2) * 4;
    require(center[0] > 240 && center[1] < 15 && center[2] < 15 && center[3] == 255, "Incorrect decoded colours");
}
}

int main(int argc, char** argv) {
    try {
        require(argc == 2, "Supply fixture directory");
        const std::string file = std::string(argv[1]) + "/red-422-10bit.hif";
        const std::string rotated = std::string(argv[1]) + "/red-422-10bit-rotated.hif";
        const auto neverCancel = [] { return false; };
        const auto memory = 128ULL * 1024 * 1024;
        verifyRed(alpha::decodeHeif(file.c_str(), nullptr, 0, 0, memory, neverCancel), 128, 64);
        verifyRed(alpha::decodeHeif(rotated.c_str(), nullptr, 0, 0, memory, neverCancel), 64, 128);
        const auto exif = alpha::readHeifExif(file.c_str());
        require(exif.size() > 6 && std::string(exif.begin(), exif.begin() + 4) == "Exif", "Missing EXIF header");
        require(std::string(exif.begin(), exif.end()).find("Alpha GPS test") != std::string::npos, "Missing camera metadata");
        std::ifstream input(file, std::ios::binary);
        std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)), {});
        verifyRed(alpha::decodeHeif(nullptr, bytes.data(), bytes.size(), 640, memory, neverCancel), 128, 64);
        rejects([&] { alpha::decodeHeif(nullptr, bytes.data(), 20, 0, memory, neverCancel); });
        rejects([&] { alpha::decodeHeif(file.c_str(), nullptr, 0, 0, 1, neverCancel); });
        rejects([&] { alpha::decodeHeif(file.c_str(), nullptr, 0, 0, memory, [] { return true; }); });
        std::cout << "10-bit 4:2:2 file/memory decode, colours, rotation, malformed input, limits, cancellation passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
