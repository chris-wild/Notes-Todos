import UIKit
import ImageIO

/// iOS counterpart of Android's RecipeFiles.imageToPdf: downsample the picked
/// image to <=4000px on the long edge, wrap it in a one-page PDF.
enum ImageToPDF {
    static let maxEdge: CGFloat = 4000

    static func convert(_ imageData: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(imageData as CFData, nil),
              let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: maxEdge,
              ] as CFDictionary)
        else { return nil }

        let image = UIImage(cgImage: cgImage)
        let bounds = CGRect(origin: .zero, size: image.size)
        let renderer = UIGraphicsPDFRenderer(bounds: bounds)
        return renderer.pdfData { context in
            context.beginPage()
            image.draw(in: bounds)
        }
    }
}
