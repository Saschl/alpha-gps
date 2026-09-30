#include "heif_c_api.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void require(int condition, const char* message) {
    if (!condition) { fprintf(stderr, "%s\n", message); exit(1); }
}

static int cancelled(void* context) { return *(const int*)context; }

int main(int argc, char** argv) {
    require(argc == 2, "Supply fixture directory");
    char path[4096];
    snprintf(path, sizeof(path), "%s/red-422-10bit-rotated.hif", argv[1]);
    const uint64_t budget = 128ULL * 1024 * 1024;
    alpha_heif_error error;
    alpha_heif_image* image = alpha_heif_decode_file(path, budget, NULL, NULL, &error);
    require(image != NULL, error.message);
    require(error.message[0] == '\0', "Success retained an error");
    require(alpha_heif_image_width(image) == 64 && alpha_heif_image_height(image) == 128, "Wrong rotated dimensions");
    const uint8_t* pixel = alpha_heif_image_pixels(image) + 64 * alpha_heif_image_stride(image) + 32 * 4;
    require(pixel[0] > 240 && pixel[1] < 15 && pixel[2] < 15 && pixel[3] == 255, "Incorrect RGBA pixels");
    alpha_heif_image_release(image);

    alpha_heif_bytes* exif = alpha_heif_read_exif(path, &error);
    require(exif != NULL, error.message);
    require(alpha_heif_bytes_size(exif) > 6 && memcmp(alpha_heif_bytes_data(exif), "Exif\0\0", 6) == 0, "Missing EXIF");
    alpha_heif_bytes_release(exif);

    int stop = 1;
    require(alpha_heif_decode_file(path, budget, cancelled, &stop, &error) == NULL, "Cancellation ignored");
    require(strstr(error.message, "cancelled") != NULL, "Missing cancellation error");
    require(alpha_heif_decode_file(path, 1, NULL, NULL, &error) == NULL, "Invalid budget accepted");
    require(alpha_heif_decode_file(NULL, budget, NULL, NULL, &error) == NULL, "Null path accepted");
    require(alpha_heif_read_exif(NULL, &error) == NULL, "Null EXIF path accepted");
    require(alpha_heif_decode_thumbnail(NULL, 0, budget, &error) == NULL, "Empty thumbnail accepted");
    const uint8_t invalid[] = {0, 1, 2, 3};
    require(alpha_heif_decode_thumbnail(invalid, sizeof(invalid), budget, &error) == NULL, "Malformed thumbnail accepted");
    require(error.message[0] != '\0', "Missing decode error");
    require(alpha_heif_decode_file(NULL, budget, NULL, NULL, NULL) == NULL, "Null error pointer failed");
    alpha_heif_image_release(NULL);
    alpha_heif_bytes_release(NULL);
    puts("C ABI decode, rotation, pixels, EXIF, cancellation and failure handling passed");
    return 0;
}
