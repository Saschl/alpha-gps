package com.sasch.cameragps.sharednew.bluetooth

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIImage
import platform.UIKit.UIImageRenderingMode.UIImageRenderingModeAlwaysOriginal
import platform.UIKit.UIImageSymbolConfiguration

/** Shared by Kotlin picker items and Swift's dynamically named discoveries. */
@OptIn(ExperimentalForeignApi::class)
object IosAccessoryArtwork {
    // The SVG is compiled into the app's asset catalog with vector data retained.
    // Its 540 × 360 canvas also covers the 180 × 120 pt picker frame at 3×.
    val image: UIImage by lazy {
        val artwork = UIImage.imageNamed("AccessoryCamera")
            // Standalone Kotlin tests do not contain the host's asset catalog.
            // Never fall back to a small text-sized symbol that gets enlarged.
            ?: UIImage.systemImageNamed(
                "camera.fill",
                withConfiguration = UIImageSymbolConfiguration.configurationWithPointSize(540.0),
            )
            ?: UIImage()
        artwork.imageWithRenderingMode(UIImageRenderingModeAlwaysOriginal)
    }
}
