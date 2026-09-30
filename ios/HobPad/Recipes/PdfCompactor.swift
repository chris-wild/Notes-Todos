import UIKit
import PDFKit

/// One-shot repair for oversized recipe PDFs already on disk. Captures made before
/// Sept 30 embedded photographs losslessly (a single page could be 29 MB), which no
/// upload path can carry. On launch, any stored PDF over the threshold is re-rendered
/// page by page as JPEG-backed pages and atomically replaced — viewing quality at
/// recipe-page resolution is unaffected, and conversion becomes possible again.
enum PdfCompactor {
    static let thresholdBytes = 8 * 1024 * 1024
    static let maxPages = 20
    static let pageMaxEdge: CGFloat = 2200

    static func run() {
        let fileManager = FileManager.default
        guard let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
        let dir = base.appendingPathComponent("recipes")
        guard let files = try? fileManager.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey]) else { return }
        for url in files where url.pathExtension.lowercased() == "pdf" {
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            guard size > thresholdBytes else { continue }
            guard let document = PDFDocument(url: url),
                  document.pageCount > 0, document.pageCount <= maxPages else { continue }
            guard let compacted = render(document), compacted.count < size else { continue }
            try? compacted.write(to: url, options: .atomic)
        }
    }

    private static func render(_ document: PDFDocument) -> Data? {
        let renderer = UIGraphicsPDFRenderer(bounds: .zero)
        let data = renderer.pdfData { context in
            for index in 0..<document.pageCount {
                guard let page = document.page(at: index) else { continue }
                let box = page.bounds(for: .mediaBox)
                guard box.width > 0, box.height > 0 else { continue }
                let scale = min(1, pageMaxEdge / max(box.width, box.height))
                let pageSize = CGSize(width: box.width * scale, height: box.height * scale)
                // Rasterise at 2x the page size so text stays legible, then JPEG it —
                // the same DCT-passthrough trick as ImageToPDF.
                let thumb = page.thumbnail(of: CGSize(width: pageSize.width * 2, height: pageSize.height * 2), for: .mediaBox)
                guard let jpeg = thumb.jpegData(compressionQuality: 0.6),
                      let jpegImage = UIImage(data: jpeg) else { continue }
                context.beginPage(withBounds: CGRect(origin: .zero, size: pageSize), pageInfo: [:])
                jpegImage.draw(in: CGRect(origin: .zero, size: pageSize))
            }
        }
        return data.isEmpty ? nil : data
    }
}
