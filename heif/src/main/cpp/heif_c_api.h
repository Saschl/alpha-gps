#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct alpha_heif_image alpha_heif_image;
typedef struct alpha_heif_bytes alpha_heif_bytes;
typedef int (*alpha_heif_cancelled)(void* context);

typedef struct alpha_heif_error {
    char message[256];
} alpha_heif_error;

/* Calls are synchronous. Callback/context and input bytes must live until return.
 * Failures return NULL and populate error; no C++ exceptions cross this API. */
alpha_heif_image* alpha_heif_decode_file(const char* path, uint64_t memory_budget,
    alpha_heif_cancelled cancelled, void* context, alpha_heif_error* error);
alpha_heif_image* alpha_heif_decode_thumbnail(const uint8_t* bytes, size_t length,
    uint64_t memory_budget, alpha_heif_error* error);

/* Pixels are RGBA8 in sRGB, with HEIF orientation already applied.
 * The pixel pointer remains valid until image_release. */
int alpha_heif_image_width(const alpha_heif_image* image);
int alpha_heif_image_height(const alpha_heif_image* image);
int alpha_heif_image_stride(const alpha_heif_image* image);
int alpha_heif_image_premultiplied(const alpha_heif_image* image);
const uint8_t* alpha_heif_image_pixels(const alpha_heif_image* image);
void alpha_heif_image_release(alpha_heif_image* image);

/* Missing EXIF returns an empty buffer; NULL means failure. */
alpha_heif_bytes* alpha_heif_read_exif(const char* path, alpha_heif_error* error);
const uint8_t* alpha_heif_bytes_data(const alpha_heif_bytes* bytes);
size_t alpha_heif_bytes_size(const alpha_heif_bytes* bytes);
void alpha_heif_bytes_release(alpha_heif_bytes* bytes);

#ifdef __cplusplus
}
#endif
