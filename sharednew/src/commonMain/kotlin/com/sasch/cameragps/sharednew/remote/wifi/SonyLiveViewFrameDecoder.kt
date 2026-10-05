package com.sasch.cameragps.sharednew.remote.wifi

internal data class SonyLiveViewFrame(
    val imageBytes: ByteArray,
    val focalMetadata: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun toString() = "SonyLiveViewFrame(width=$width, height=$height, imageBytes=${imageBytes.size})"
}

/** Incremental parser for the 16-byte header and image/focal payload used by APK HTTP live view. */
internal class SonyLiveViewFrameDecoder(private val maxFrameBytes: Int = 4 * 1024 * 1024) {
    private val header = ByteArray(16)
    private var headerBytes = 0
    private var frame: ByteArray? = null
    private var frameBytes = 0
    private var imageOffset = 0
    private var imageSize = 0
    private var focalOffset = 0
    private var focalSize = 0

    init { require(maxFrameBytes > 16) }

    fun feed(input: ByteArray): List<SonyLiveViewFrame> {
        val result = mutableListOf<SonyLiveViewFrame>()
        var source = 0
        while (source < input.size) {
            if (frame == null) {
                val copy = minOf(16 - headerBytes, input.size - source)
                input.copyInto(header, headerBytes, source, source + copy)
                headerBytes += copy
                source += copy
                if (headerBytes < 16) break

                imageOffset = read32(0)
                imageSize = read32(4)
                focalOffset = read32(8)
                focalSize = read32(12)
                require(imageOffset >= 16 && imageSize > 0 && imageOffset <= maxFrameBytes - imageSize) {
                    "Invalid live-view image extent"
                }
                require(focalSize == 0 || (focalOffset >= 16 && focalOffset <= maxFrameBytes - focalSize)) {
                    "Invalid live-view metadata extent"
                }
                require(focalSize == 0 || focalOffset + focalSize <= imageOffset ||
                    imageOffset + imageSize <= focalOffset) { "Overlapping live-view regions" }
                val total = maxOf(imageOffset + imageSize,
                    if (focalSize > 0) focalOffset + focalSize else 16)
                frame = ByteArray(total).also { header.copyInto(it) }
                frameBytes = 16
                headerBytes = 0
            }

            val target = frame!!
            val copy = minOf(target.size - frameBytes, input.size - source)
            input.copyInto(target, frameBytes, source, source + copy)
            frameBytes += copy
            source += copy
            if (frameBytes == target.size) {
                val image = target.copyOfRange(imageOffset, imageOffset + imageSize)
                val jpeg = SonyJpeg.inspect(image)
                result += SonyLiveViewFrame(
                    imageBytes = image.copyOf(jpeg.byteCount),
                    focalMetadata = if (focalSize > 0) target.copyOfRange(focalOffset, focalOffset + focalSize)
                    else ByteArray(0),
                    width = jpeg.width,
                    height = jpeg.height,
                )
                frame = null
                frameBytes = 0
            }
        }
        return result
    }

    fun reset() {
        headerBytes = 0
        frame = null
        frameBytes = 0
    }

    private fun read32(offset: Int): Int {
        val value = (0..3).fold(0L) { current, index ->
            current or ((header[offset + index].toLong() and 0xff) shl (index * 8))
        }
        require(value <= Int.MAX_VALUE) { "Invalid live-view extent" }
        return value.toInt()
    }
}
