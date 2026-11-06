// Usage: swift tools/sony_image_fixtures/manifest.swift /path/to/fixtures > /path/to/fixtures/manifest.json

import CryptoKit
import Foundation
import ImageIO

func hevcConfiguration(_ data: Data, start: Int = 0, end: Int? = nil) -> (Int, Int)? {
    let end = end ?? data.count
    var offset = start
    while offset + 8 <= end {
        let size = data[offset..<offset + 4].reduce(0) {
            ($0 << 8) | Int($1)
        }
        let length = size == 0 ? end - offset : size
        guard length >= 8, length <= end - offset else {
            return nil
        }
        let type = String(decoding: data[offset + 4..<offset + 8], as: UTF8.self)
        if type == "hvcC", length >= 31 {
            let chroma = [400, 420, 422, 444][Int(data[offset + 24] & 3)]
            return (8 + Int(data[offset + 25] & 7), chroma)
        }
        if ["meta", "iprp", "ipco"].contains(type),
           let result = hevcConfiguration(data, start: offset + (type == "meta" ? 12 : 8), end: offset + length) {
            return result
        }
        offset += length
    }
    return nil
}

do {
    guard CommandLine.arguments.count == 2 else {
        throw NSError(domain: "Supply one folder containing selected Sony originals", code: 1)
    }
    let directory = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
    .filter {
        ["hif", "heif", "heic", "jpg", "jpeg"].contains($0.pathExtension.lowercased())
    }
    .sorted {
        $0.lastPathComponent < $1.lastPathComponent
    }
    guard !files.isEmpty else {
        throw NSError(domain: "No image fixtures found", code: 1)
    }
    let fixtures: [[String: Any]] = try files.map { file in
        guard let source = CGImageSourceCreateWithURL(file as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
              let tiff = properties[kCGImagePropertyTIFFDictionary as String] as? [String: Any],
              tiff[kCGImagePropertyTIFFMake as String] as? String == "SONY",
              let model = tiff[kCGImagePropertyTIFFModel as String] as? String,
              let width = properties[kCGImagePropertyPixelWidth as String] as? Int,
              let height = properties[kCGImagePropertyPixelHeight as String] as? Int
        else {
            throw NSError(domain: "Missing Sony image metadata: \(file.lastPathComponent)", code: 1)
        }
        let exif = properties[kCGImagePropertyExifDictionary as String] as? [String: Any] ?? [:]
        var metadata = ["make": "SONY", "model": model]
        for (name, key) in [("lensModel", kCGImagePropertyExifLensModel), ("dateTimeOriginal", kCGImagePropertyExifDateTimeOriginal)] {
            if let value = exif[key as String] as? String {
                metadata[name] = value
            }
        }
        if let iso = (exif[kCGImagePropertyExifISOSpeedRatings as String] as? [NSNumber])?.first {
            metadata["iso"] = iso.stringValue
        }
        let data = try Data(contentsOf: file)
        let isJpeg = ["jpg", "jpeg"].contains(file.pathExtension.lowercased())
        var fixture: [String: Any] = [
            "file": file.lastPathComponent, "format": isJpeg ? "jpeg" : "heif",
            "width": width, "height": height,
            "orientation": properties[kCGImagePropertyOrientation as String] as? Int ?? 1,
            "sha256": SHA256.hash(data: data).map {
                String(format: "%02x", $0)
            }
            .joined(),
            "metadata": metadata,
        ]
        if !isJpeg {
            guard let (depth, chroma) = hevcConfiguration(data) else {
                throw NSError(domain: "Missing HEVC configuration: \(file.lastPathComponent)", code: 1)
            }
            fixture["bitDepth"] = depth
            fixture["chroma"] = chroma
        }
        return fixture
    }
    let json = try JSONSerialization.data(withJSONObject: ["fixtures": fixtures], options: [.prettyPrinted, .sortedKeys])
    print(String(decoding: json, as: UTF8.self))
} catch {
    FileHandle.standardError.write(Data("\(error)\n".utf8))
    exit(1)
}
