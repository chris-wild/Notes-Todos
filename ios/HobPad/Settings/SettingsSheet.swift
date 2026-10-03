import SwiftUI
import HobPadCore

struct SettingsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: RecipesModel

    @State private var keyDraft = ""
    @AppStorage("preferredUnits") private var preferredUnits = "auto"
    @State private var exportDocument: BackupDocument?
    @State private var importOpen = false
    @State private var confirmImportData: Data?
    @State private var paywallOpen = false
    #if DEBUG
    @State private var starterProbe: String?
    #endif

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Text("Credits remaining")
                        Spacer()
                        Text(model.ops.balance.map(String.init) ?? "—")
                            .fontWeight(.semibold)
                    }
                    Text("One credit converts one recipe page into a shopping list. Naming new recipes automatically is free.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Buy credits") { paywallOpen = true }
                } header: {
                    Text("Recipe conversions")
                }

                Section {
                    Picker("Units", selection: $preferredUnits) {
                        Text("Automatic").tag("auto")
                        Text("Metric (g, ml)").tag("metric")
                        Text("US (oz, cups)").tag("us")
                    }
                    Text("Ingredient lists are converted to these units, whatever the recipe uses. Automatic follows your device region.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Units")
                }

                #if DEBUG
                // Dev-only: a personal Anthropic key bypasses metering (BYO path). Released
                // builds have no key UI — everyone meters through the Worker.
                Section {
                    Text(model.hasKey
                         ? "A key is stored in the Keychain on this device. Extraction bypasses metering while it is present."
                         : "Add a key to bypass metering in this dev build. It is stored in the Keychain and never backed up.")
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
                    Text("Anthropic API key (dev build)")
                }
                #endif

                #if DEBUG
                // Dev-only since Sept 30: released iOS relies on iCloud device backup,
                // which covers everything (DB in Documents, PDFs + settings in Application
                // Support, nothing excluded — audited). The zip tooling stays for dev data
                // wrangling and Android compatibility.
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
                    Text("Backup (dev build)")
                }

                Section {
                    Text("Asks the staging service what Apple's DeviceCheck holds for this device, without granting or changing anything.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Check this device") {
                        Task { starterProbe = await Self.probeStarter() }
                    }
                    if let starterProbe {
                        Text(starterProbe).font(.footnote)
                    }
                } header: {
                    Text("Free credits device check (dev build)")
                }
                #endif

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
            .sheet(isPresented: $paywallOpen) {
                PaywallSheet(ops: model.ops)
            }
            .task { await model.ops.refreshBalance() }
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
                // Diagnosed on-device: a swallowed read failure here once looked
                // like a successful import. Every failure now names itself, and
                // the confirm dialog shows the byte count so an empty read is
                // impossible to miss.
                switch result {
                case .success(let url):
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    do {
                        let data = try Data(contentsOf: url)
                        guard !data.isEmpty else {
                            model.message = "Import failed: \(url.lastPathComponent) is empty"
                            return
                        }
                        confirmImportData = data
                    } catch {
                        model.message = "Import failed reading \(url.lastPathComponent): \(error.localizedDescription)"
                    }
                case .failure(let error):
                    model.message = "Picker failed: \(error.localizedDescription)"
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
                let size = ByteCountFormatter.string(
                    fromByteCount: Int64(confirmImportData?.count ?? 0), countStyle: .file,
                )
                Text("Importing this \(size) backup replaces every note, todo, category and recipe on this device.")
            }
        }
    }
}

#if DEBUG
extension SettingsSheet {
    /// POSTs a real DeviceCheck token to the staging Worker's probe (backend/ops/src/starter.js).
    static func probeStarter() async -> String {
        do {
            guard var check = try await StarterCheck.body() else { return "DeviceCheck is not supported here." }
            if check["platform"] as? String == "test" { return "No DeviceCheck in the simulator." }
            check["probe"] = true
            var request = URLRequest(url: OpsWorkerAPI.baseURL.appending(path: "v1/starter"))
            request.httpMethod = "POST"
            request.setValue("Bearer \(OpsAccount.token().uuidString.lowercased())", forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: check)
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return "\(status): \(String(data: data, encoding: .utf8) ?? "")"
        } catch {
            return "Failed: \(error.localizedDescription)"
        }
    }
}
#endif
