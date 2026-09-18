import SwiftUI
import PDFKit

/// Native inline PDF viewing (the iOS counterpart of Android's PdfRenderer pager).
struct RecipePDFView: UIViewRepresentable {
    let path: String

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.document = PDFDocument(url: URL(fileURLWithPath: path))
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {
        if view.document?.documentURL?.path != path {
            view.document = PDFDocument(url: URL(fileURLWithPath: path))
        }
    }
}
