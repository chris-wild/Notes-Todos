import UIKit
import ImageIO

/// iOS counterpart of Android's RecipeFiles.imageToPdf: downsample the picked image and
/// wrap it in a one-page PDF whose image stream is JPEG.
///
/// The JPEG step is load-bearing: UIGraphicsPDFRenderer embeds a plain drawn image
/// essentially losslessly, which turned a single photographed page into a 29 MB PDF
/// (found Sept 30) — beyond the metering Worker's upload cap and beyond Anthropic's own
/// request limit, making the app's core flow unconvertible. Drawing a JPEG-backed image
/// lets Quartz pass the DCT stream through, and a recipe page needs nothing more.
enum ImageToPDF {
    static let maxEdge: CGFloat = 2200
    static let jpegQuality: CGFloat = 0.6

    static func convert(_ imageData: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(imageData as CFData, nil),
              let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                  kCGImageSourceCreateThumbnailFromImageAlways: true,
                  kCGImageSourceCreateThumbnailWithTransform: true,
                  kCGImageSourceThumbnailMaxPixelSize: maxEdge,
              ] as CFDictionary)
        else { return nil }
        return pdfWrappingJpeg(UIImage(cgImage: cgImage))
    }

    /// One page, [image] re-encoded as JPEG and drawn at its natural size.
    static func pdfWrappingJpeg(_ image: UIImage) -> Data? {
        guard let jpeg = image.jpegData(compressionQuality: jpegQuality),
              let jpegImage = UIImage(data: jpeg) else { return nil }
        let bounds = CGRect(origin: .zero, size: jpegImage.size)
        let renderer = UIGraphicsPDFRenderer(bounds: bounds)
        let data = renderer.pdfData { context in
            context.beginPage()
            jpegImage.draw(in: bounds)
        }
        return data.isEmpty ? nil : data
    }
}
