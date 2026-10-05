@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sasch.cameragps.sharednew.remote.wifi

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.CoreGraphics.CGImageRelease
import platform.ImageIO.*
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Owns only Create/Copy references; constants and borrowed properties stay borrowed. */
private class ImageIoReferences {
    private val owned = mutableListOf<CFTypeRef>()
    fun <T : CPointed> own(value: CPointer<T>?): CPointer<T> = checkNotNull(value) {
        "Image I/O could not decode this image"
    }.also { owned += it }
    fun close() = owned.asReversed().forEach { CFRelease(it) }
    fun options(vararg values: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef {
        val dictionary = own(CFDictionaryCreateMutable(null, 0, null, null))
        values.forEach { (key, value) -> CFDictionarySetValue(dictionary, key, value) }
        return dictionary
    }
    fun number(value: Int): CFNumberRef = memScoped {
        own(CFNumberCreate(null, kCFNumberIntType, alloc<IntVar> { this.value = value }.ptr))
    }
    fun number(value: Double): CFNumberRef = memScoped {
        own(CFNumberCreate(null, kCFNumberDoubleType, alloc<DoubleVar> { this.value = value }.ptr))
    }
    fun file(path: String): CFURLRef = path.encodeToByteArray().usePinned {
        own(CFURLCreateFromFileSystemRepresentation(null, it.addressOf(0).reinterpret(), it.get().size.toLong(), false))
    }
    fun string(value: String): CFStringRef = own(CFStringCreateWithCString(null, value, kCFStringEncodingUTF8))
}

internal fun decodeIosCameraThumbnail(bytes: ByteArray): ImageBitmap? =
    decodeIosCameraImage(bytes, 640)

internal fun decodeIosCameraPreview(bytes: ByteArray): ImageBitmap? =
    decodeIosCameraImage(bytes, 2048)

private fun decodeIosCameraImage(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val refs = ImageIoReferences()
    return try {
        val data = bytes.usePinned { refs.own(CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong())) }
        val source = refs.own(CGImageSourceCreateWithData(data, null))
        val options = refs.options(
            kCGImageSourceCreateThumbnailFromImageAlways to kCFBooleanTrue,
            kCGImageSourceCreateThumbnailWithTransform to kCFBooleanTrue,
            kCGImageSourceThumbnailMaxPixelSize to refs.number(maxDimension),
            kCGImageSourceShouldCacheImmediately to kCFBooleanTrue,
        )
        val image = checkNotNull(CGImageSourceCreateThumbnailAtIndex(source, 0u, options))
        try {
            val output = refs.own(CFDataCreateMutable(null, 0))
            val destination = refs.own(CGImageDestinationCreateWithData(output, refs.string("public.png"), 1u, null))
            CGImageDestinationAddImage(destination, image, null)
            check(CGImageDestinationFinalize(destination))
            checkNotNull(CFDataGetBytePtr(output)).readBytes(CFDataGetLength(output).toInt()).decodeToImageBitmap()
        } finally {
            CGImageRelease(image)
        }
    } catch (_: Exception) {
        null
    } finally {
        refs.close()
    }
}

/** Image I/O carries source metadata, including orientation, into the JPEG. */
internal fun convertIosCameraHeifToJpeg(sourcePath: String, outputPath: String) {
    val refs = ImageIoReferences()
    try {
        val source = refs.own(CGImageSourceCreateWithURL(refs.file(sourcePath), null))
        check(CGImageSourceGetCount(source) > 0u) { "Image contains no picture" }
        val destination = refs.own(CGImageDestinationCreateWithURL(refs.file(outputPath), refs.string("public.jpeg"), 1u, null))
        val options = refs.options(
            kCGImageDestinationLossyCompressionQuality to refs.number(0.95),
            kCGImageDestinationEncodeToSDR to kCFBooleanTrue,
        )
        CGImageDestinationAddImageFromSource(destination, source, 0u, options)
        check(CGImageDestinationFinalize(destination)) { "iOS could not convert this HEIF image" }
    } finally {
        refs.close()
    }
}
