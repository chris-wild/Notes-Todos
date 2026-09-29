import SwiftUI
import PhotosUI
import HobPadCore

struct RecipesTab: View {
    @Environment(AppServices.self) private var services
    @State private var model: RecipesModel?

    var body: some View {
        Group {
            if let model {
                RecipesContent(model: model)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            if model == nil { model = RecipesModel(core: services.core, ops: services.ops) }
        }
    }
}

private struct RecipesContent: View {
    @Bindable var model: RecipesModel
    @State private var editingRecipe: RecipeEntity?
    @State private var composing = false
    @State private var viewingRecipe: RecipeEntity?
    @State private var deleteTarget: RecipeEntity?
    @State private var settingsOpen = false
    @State private var cameraOpen = false
    @State private var cameraFallbackItem: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            List {
                if let message = model.message {
                    HStack {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(Color.accentColor)
                        Spacer()
                        Button("Dismiss") { model.message = nil }
                            .font(.footnote)
                    }
                }
                if model.filtered.isEmpty {
                    Text(model.query.isEmpty
                         ? "No recipes yet — tap + to add one."
                         : "No recipes match your search.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.filtered, id: \.id) { recipe in
                    Button {
                        viewingRecipe = recipe
                    } label: {
                        recipeRow(recipe)
                    }
                    .swipeActions {
                        Button("Delete", role: .destructive) { deleteTarget = recipe }
                        Button("Edit") { editingRecipe = recipe }.tint(.blue)
                    }
                    // Swipe is invisible until you know it exists; long-press offers the same.
                    .contextMenu {
                        Button("Edit") { editingRecipe = recipe }
                        Button("Delete", role: .destructive) { deleteTarget = recipe }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Recipes")
            .searchable(text: $model.query, prompt: "Search recipes")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    // Accessibility labels sit on the images: on a toolbar Button the
                    // modifier is silently dropped (observed with the UI test, Sept 2026).
                    if CameraCapture.isAvailable {
                        Button { cameraOpen = true } label: {
                            Image(systemName: "camera").accessibilityLabel("Photograph recipe")
                        }
                    } else {
                        // No camera (simulator / some iPads): photo library stands in.
                        PhotosPicker(selection: $cameraFallbackItem, matching: .images) {
                            Image(systemName: "camera").accessibilityLabel("Photograph recipe")
                        }
                    }
                    Button { settingsOpen = true } label: {
                        Image(systemName: "gearshape").accessibilityLabel("Settings")
                    }
                    Button { composing = true } label: {
                        Image(systemName: "plus").accessibilityLabel("Add recipe")
                    }
                }
            }
            .sheet(isPresented: $composing) {
                RecipeEditorSheet(model: model, recipe: nil)
            }
            .sheet(item: $editingRecipe) { recipe in
                RecipeEditorSheet(model: model, recipe: recipe)
            }
            .sheet(item: $viewingRecipe) { recipe in
                RecipeViewerSheet(model: model, recipe: recipe) {
                    // Edit from the viewer: swap sheets once the dismissal settles.
                    Task {
                        try? await Task.sleep(for: .milliseconds(600))
                        editingRecipe = recipe
                    }
                }
            }
            .sheet(isPresented: $settingsOpen) {
                SettingsSheet(model: model)
            }
            .fullScreenCover(isPresented: $cameraOpen) {
                CameraCapture { imageData in
                    model.createFromCapture(imageData: imageData)
                }
                .ignoresSafeArea()
            }
            .onChange(of: cameraFallbackItem) {
                guard let item = cameraFallbackItem else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        model.createFromCapture(imageData: data)
                    }
                    cameraFallbackItem = nil
                }
            }
            .confirmationDialog(
                "Delete recipe?",
                isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }),
                titleVisibility: .visible,
            ) {
                Button("Delete", role: .destructive) {
                    if let recipe = deleteTarget { model.delete(recipe) }
                    deleteTarget = nil
                }
            } message: {
                Text("\"\(deleteTarget?.name ?? "")\" and its attachments will be permanently deleted.")
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
            .task { await model.observeRecipes() }
            .task { await model.observeAttachments() }
            .task { await model.observeKey() }
        }
    }

    private func recipeRow(_ recipe: RecipeEntity) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(recipe.name)
                .font(.headline)
                .foregroundStyle(.primary)
            if !recipe.notes.isEmpty {
                Text(recipe.notes)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            HStack(spacing: 8) {
                let count = model.attachments(for: recipe).count
                if count > 0 {
                    Text(count == 1 ? "PDF" : "\(count) PDFs")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.accentColor)
                }
                Text(Date(timeIntervalSince1970: Double(recipe.updatedAt != 0 ? recipe.updatedAt : recipe.createdAt) / 1000),
                     style: .date)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct RecipeViewerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: RecipesModel
    let recipe: RecipeEntity
    let onEdit: () -> Void
    @State private var fullScreenPath: FullScreenPdf?
    @State private var confirmDelete = false

    private struct FullScreenPdf: Identifiable {
        let id: String
        var path: String { id }
    }

    @State private var confirmCost: Int?
    @State private var paywallOpen = false
    @State private var multiplier = 1

    var body: some View {
        let attachments = model.attachments(for: recipe)
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                // Scales the shopping list (cooking for more): quantities are multiplied
                // when the ingredient list is created. Cached recipes re-run free at any ×.
                Stepper(value: $multiplier, in: 1...10) {
                    Text(multiplier == 1 ? "Quantities ×1" : "Quantities ×\(multiplier)")
                        .font(.subheadline)
                        .fontWeight(multiplier == 1 ? .regular : .semibold)
                }
                .padding(.horizontal)
                if !recipe.notes.isEmpty {
                    ScrollView {
                        Text(recipe.notes)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 160)
                    .padding(.horizontal)
                }
                if attachments.isEmpty {
                    Spacer()
                    Text("No PDF attached to this recipe.")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                    Spacer()
                } else if attachments.count == 1 {
                    pdfPage(attachments[0])
                } else {
                    TabView {
                        ForEach(attachments, id: \.id) { attachment in
                            pdfPage(attachment)
                        }
                    }
                    .tabViewStyle(.page)
                    .indexViewStyle(.page(backgroundDisplayMode: .always))
                }
            }
            .navigationTitle(recipe.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    // The visible route to Edit/Delete (swipe on the list also works).
                    Menu {
                        Button("Edit Recipe") {
                            dismiss()
                            onEdit()
                        }
                        Button("Delete Recipe", role: .destructive) { confirmDelete = true }
                    } label: {
                        Image(systemName: "ellipsis.circle").accessibilityLabel("Recipe actions")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create ingredient list") {
                        Task {
                            switch await model.conversionGate(for: recipe) {
                            case .run:
                                model.createIngredients(for: recipe, multiplier: multiplier) { dismiss() }
                            case .confirm(let cost):
                                confirmCost = cost
                            case .paywall:
                                paywallOpen = true
                            }
                        }
                    }
                    .disabled(model.working != nil)
                }
            }
            .confirmationDialog(
                "Convert this recipe?",
                isPresented: Binding(get: { confirmCost != nil }, set: { if !$0 { confirmCost = nil } }),
                titleVisibility: .visible,
            ) {
                Button("Convert") {
                    confirmCost = nil
                    model.createIngredients(for: recipe, multiplier: multiplier) { dismiss() }
                }
            } message: {
                let cost = confirmCost ?? 1
                let balance = model.ops.balance ?? 0
                Text("This uses \(cost) of your \(balance) conversion credit\(balance == 1 ? "" : "s").")
            }
            .sheet(isPresented: $paywallOpen) {
                PaywallSheet(ops: model.ops)
            }
            .confirmationDialog(
                "Delete recipe?",
                isPresented: $confirmDelete,
                titleVisibility: .visible,
            ) {
                Button("Delete", role: .destructive) {
                    model.delete(recipe)
                    dismiss()
                }
            } message: {
                Text("\"\(recipe.name)\" and its attachments will be permanently deleted.")
            }
            .onChange(of: model.paywallNeeded) {
                // The server's page count disagreed with the preview and the balance fell
                // short mid-run — offer packs right away.
                if model.paywallNeeded {
                    model.paywallNeeded = false
                    paywallOpen = true
                }
            }
            .fullScreenCover(item: $fullScreenPath) { item in
                NavigationStack {
                    RecipePDFView(path: item.path)
                        .ignoresSafeArea(edges: .bottom)
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") { fullScreenPath = nil }
                            }
                        }
                }
            }
        }
    }

    private func pdfPage(_ attachment: RecipeAttachmentEntity) -> some View {
        RecipePDFView(path: model.pdfPath(attachment))
            .overlay(alignment: .topTrailing) {
                Button {
                    fullScreenPath = FullScreenPdf(id: model.pdfPath(attachment))
                } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .padding(10)
                        .background(.regularMaterial, in: Circle())
                }
                .padding(12)
            }
    }
}

private struct RecipeEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: RecipesModel
    let recipe: RecipeEntity?

    @State private var name = ""
    @State private var notes = ""
    @State private var pending: [(data: Data, originalName: String)] = []
    @State private var removals: [RecipeAttachmentEntity] = []
    @State private var pdfPickerOpen = false
    @State private var photoItem: PhotosPickerItem?

    var body: some View {
        let existing = recipe.map { model.attachments(for: $0) } ?? []
        NavigationStack {
            Form {
                TextField("Recipe name", text: $name)
                TextField("Recipe notes (optional)…", text: $notes, axis: .vertical)
                    .lineLimit(4...12)

                Section("Attachments") {
                    Button("Attach PDF") { pdfPickerOpen = true }
                    PhotosPicker("Attach image", selection: $photoItem, matching: .images)

                    ForEach(existing, id: \.id) { attachment in
                        let removed = removals.contains { $0.id == attachment.id }
                        HStack {
                            Text((attachment.originalName ?? attachment.fileName) +
                                 (removed ? "  (will be removed)" : ""))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            Spacer()
                            if removed {
                                Button("Undo") { removals.removeAll { $0.id == attachment.id } }
                            } else {
                                Button("Remove") { removals.append(attachment) }
                            }
                        }
                    }
                    ForEach(Array(pending.enumerated()), id: \.offset) { index, item in
                        HStack {
                            Text("Will attach: \(item.originalName)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            Spacer()
                            Button("Discard") { pending.remove(at: index) }
                        }
                    }
                }
            }
            .navigationTitle(recipe == nil ? "Add Recipe" : "Edit Recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(recipe == nil ? "Save" : "Update") {
                        model.save(
                            id: recipe?.id,
                            name: name,
                            notes: notes,
                            newAttachments: pending,
                            removals: removals,
                        )
                        dismiss()
                    }
                }
            }
            .fileImporter(isPresented: $pdfPickerOpen, allowedContentTypes: [.pdf]) { result in
                switch result {
                case .success(let url):
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    do {
                        pending.append((try Data(contentsOf: url), url.lastPathComponent))
                    } catch {
                        model.message = "Could not read \(url.lastPathComponent): \(error.localizedDescription)"
                    }
                case .failure(let error):
                    model.message = "Picker failed: \(error.localizedDescription)"
                }
            }
            .onChange(of: photoItem) {
                guard let item = photoItem else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let pdf = ImageToPDF.convert(data) {
                        pending.append((pdf, "photo.jpg"))
                    }
                    photoItem = nil
                }
            }
            .onAppear {
                if let recipe {
                    name = recipe.name
                    notes = recipe.notes
                }
            }
        }
    }
}

extension RecipeEntity: @retroactive Identifiable {}
