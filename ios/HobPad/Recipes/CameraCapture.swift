import SwiftUI
import UIKit

/// System camera for "take photo of recipe". The photo is handed back as JPEG data.
struct CameraCapture: UIViewControllerRepresentable {
    let onImage: (Data) -> Void
    @Environment(\.dismiss) private var dismiss

    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onImage: onImage, dismiss: { dismiss() })
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onImage: (Data) -> Void
        let dismiss: () -> Void

        init(onImage: @escaping (Data) -> Void, dismiss: @escaping () -> Void) {
            self.onImage = onImage
            self.dismiss = dismiss
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any],
        ) {
            if let image = info[.originalImage] as? UIImage,
               let jpeg = image.jpegData(compressionQuality: 0.9) {
                onImage(jpeg)
            }
            dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            dismiss()
        }
    }
}
