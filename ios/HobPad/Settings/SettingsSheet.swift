import SwiftUI
import HobPadCore

struct SettingsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: RecipesModel

    @State private var keyDraft = ""
    @State private var exportDocument: BackupDocument?
    @State private var importOpen = false
    @State private var confirmImportData: Data?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(model.hasKey
                         ? "A key is stored in the Keychain on this device. It unlocks \"Create ingredient list\"."
                         : "Add a key to unlock \"Create ingredient list\". It is stored in the Keychain and never backed up.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    TextField(model.hasKey ? "Replace key (sk-ant-…)" : "sk-ant-…", text: $keyDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Button("Save key") {
                            model.saveKey(keyDraft)
                            keyDraft = ""
                        }
                        if model.hasKey {
                            Spacer()
                            Button("Remove key", role: .destructive) { model.deleteKey() }
                        }
                    }
                } header: {
                    Text("Anthropic API key")
                }

                Section {
                    Text("Export everything (including recipe PDFs) to a zip you can keep anywhere. Import replaces all current data. The zip format matches the Android app's backups.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Export backup") {
                        Task {
                            if let data = await model.exportBackup() {
                                exportDocument = BackupDocument(data: data)
                            }
                        }
                    }
                    Button("Import backup") { importOpen = true }
                } header: {
                    Text("Backup")
                }

                if let message = model.message {
                    Section {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(Color.accentColor)
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .overlay {
                if let working = model.working {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(working)
                    }
                    .padding(24)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
            }
            .fileExporter(
                isPresented: Binding(get: { exportDocument != nil }, set: { if !$0 { exportDocument = nil } }),
                document: exportDocument,
                contentType: .zip,
                defaultFilename: "hobpad-backup",
            ) { result in
                if case .success = result { model.message = "Backup exported" }
                exportDocument = nil
            }
            .fileImporter(isPresented: $importOpen, allowedContentTypes: [.zip, .data]) { result in
                if case .success(let url) = result {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    if let data = try? Data(contentsOf: url) {
                        confirmImportData = data
                    } else {
                        model.message = "Could not read the selected file"
                    }
                }
            }
            .alert(
                "Replace all data?",
                isPresented: Binding(get: { confirmImportData != nil }, set: { if !$0 { confirmImportData = nil } }),
            ) {
                Button("Cancel", role: .cancel) { confirmImportData = nil }
                Button("Import", role: .destructive) {
                    if let data = confirmImportData { model.importBackup(data) }
                    confirmImportData = nil
                }
            } message: {
                Text("Importing replaces every note, todo, category and recipe on this device with the backup's contents.")
            }
        }
    }
}
