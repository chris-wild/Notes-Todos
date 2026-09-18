import SwiftUI
import UniformTypeIdentifiers

/// FileDocument wrapper for fileExporter: the backup zip produced by the shared core.
struct BackupDocument: FileDocument {
    static let readableContentTypes: [UTType] = [.zip]

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
