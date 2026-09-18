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
            if model == nil { model = RecipesModel(core: services.core) }
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
                                if recipe.pdfFileName != nil {
                                    Text("PDF")
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
                    .swipeActions {
                        Button("Delete", role: .destructive) { deleteTarget = recipe }
                        Button("Edit") { editingRecipe = recipe }.tint(.blue)
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Recipes")
            .searchable(text: $model.query, prompt: "Search recipes")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { settingsOpen = true } label: { Image(systemName: "gearshape") }
                    Button { composing = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $composing) {
                RecipeEditorSheet(model: model, recipe: nil)
            }
            .sheet(item: $editingRecipe) { recipe in
                RecipeEditorSheet(model: model, recipe: recipe)
            }
            .sheet(item: $viewingRecipe) { recipe in
                RecipeViewerSheet(model: model, recipe: recipe)
            }
            .sheet(isPresented: $settingsOpen) {
                SettingsSheet(model: model)
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
                Text("\"\(deleteTarget?.name ?? "")\" and its attachment will be permanently deleted.")
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
            .task { await model.observeKey() }
        }
    }
}

private struct RecipeViewerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: RecipesModel
    let recipe: RecipeEntity

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                if !recipe.notes.isEmpty {
                    ScrollView {
                        Text(recipe.notes)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(maxHeight: 160)
                    .padding(.horizontal)
                }
                if let path = model.pdfPath(recipe) {
                    RecipePDFView(path: path)
                } else {
                    Spacer()
                    Text("No PDF attached to this recipe.")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                    Spacer()
                }
            }
            .navigationTitle(recipe.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                if model.hasKey {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Create ingredient list") {
                            model.createIngredients(for: recipe) { dismiss() }
                        }
                        .disabled(model.working != nil)
                    }
                }
            }
        }
    }
}

private struct RecipeEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var model: RecipesModel
    let recipe: RecipeEntity?

    @State private var name = ""
    @State private var notes = ""
    @State private var pendingAttachment: (data: Data, originalName: String)?
    @State private var removeExisting = false
    @State private var pdfPickerOpen = false
    @State private var photoItem: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            Form {
                TextField("Recipe name", text: $name)
                TextField("Recipe notes (optional)…", text: $notes, axis: .vertical)
                    .lineLimit(4...12)

                Section("Attachment") {
                    Button("Attach PDF") { pdfPickerOpen = true }
                    PhotosPicker("Attach image", selection: $photoItem, matching: .images)

                    if let pending = pendingAttachment {
                        HStack {
                            Text("Will attach: \(pending.originalName)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("Discard") { pendingAttachment = nil }
                        }
                    } else if removeExisting {
                        HStack {
                            Text("Attachment will be removed on save.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("Undo") { removeExisting = false }
                        }
                    } else if let existingName = recipe?.pdfOriginalName ?? recipe?.pdfFileName {
                        HStack {
                            Text("File attached: \(existingName)")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("Remove") { removeExisting = true }
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
                            newAttachment: pendingAttachment,
                            removeAttachment: removeExisting,
                        )
                        dismiss()
                    }
                }
            }
            .fileImporter(isPresented: $pdfPickerOpen, allowedContentTypes: [.pdf]) { result in
                if case .success(let url) = result {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    if let data = try? Data(contentsOf: url) {
                        pendingAttachment = (data, url.lastPathComponent)
                        removeExisting = false
                    }
                }
            }
            .onChange(of: photoItem) {
                guard let item = photoItem else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self),
                       let pdf = ImageToPDF.convert(data) {
                        pendingAttachment = (pdf, "photo.jpg")
                        removeExisting = false
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
