import SwiftUI
import HobPadCore

struct NotesTab: View {
    @Environment(AppServices.self) private var services
    @State private var model: NotesModel?

    var body: some View {
        Group {
            if let model {
                NotesContent(model: model)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            if model == nil { model = NotesModel(core: services.core) }
        }
    }
}

private struct NotesContent: View {
    @Bindable var model: NotesModel
    @State private var editingNote: NoteEntity?
    @State private var composing = false
    @State private var deleteTarget: NoteEntity?

    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 10)]

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: model.viewMode == .grid ? columns : [GridItem(.flexible())], spacing: 10) {
                    if !model.pinned.isEmpty {
                        Section {
                            ForEach(model.pinned, id: \.id) { note in
                                noteCard(note)
                            }
                        } header: { sectionLabel("PINNED") }
                    }
                    Section {
                        ForEach(model.others, id: \.id) { note in
                            noteCard(note)
                        }
                    } header: {
                        if !model.pinned.isEmpty && !model.others.isEmpty { sectionLabel("OTHERS") }
                    }
                }
                .padding(.horizontal)

                if model.pinned.isEmpty && model.others.isEmpty {
                    Text(model.query.isEmpty
                         ? "No notes yet — tap + to get started."
                         : "No notes match your search.")
                        .foregroundStyle(.secondary)
                        .padding(.top, 40)
                }
            }
            .navigationTitle("Notes")
            .searchable(text: $model.query, prompt: "Search notes")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Menu {
                        sortButton("Date ↓", .dateDesc)
                        sortButton("Date ↑", .dateAsc)
                        sortButton("A → Z", .alphaAsc)
                        sortButton("Z → A", .alphaDesc)
                    } label: {
                        Image(systemName: "arrow.up.arrow.down")
                    }
                    Button {
                        model.toggleViewMode()
                    } label: {
                        Image(systemName: model.viewMode == .grid ? "list.bullet" : "square.grid.2x2")
                    }
                    Button {
                        composing = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .sheet(isPresented: $composing) {
                NoteEditorSheet(title: "", content: "") { title, content in
                    model.create(title: title, content: content)
                }
            }
            .sheet(item: $editingNote) { note in
                NoteEditorSheet(title: note.title, content: note.content) { title, content in
                    model.save(id: note.id, title: title, content: content)
                }
            }
            .confirmationDialog(
                "Delete note?",
                isPresented: Binding(get: { deleteTarget != nil }, set: { if !$0 { deleteTarget = nil } }),
                titleVisibility: .visible,
            ) {
                Button("Delete", role: .destructive) {
                    if let note = deleteTarget { model.delete(note) }
                    deleteTarget = nil
                }
            } message: {
                Text("\(deleteTarget?.title.isEmpty == false ? deleteTarget!.title : "This note") will be permanently deleted.")
            }
            .task { await model.observeNotes() }
            .task { await model.observeSettings() }
            .task { await model.observeViewMode() }
        }
    }

    private func sortButton(_ label: String, _ sort: NoteSort) -> some View {
        Button {
            model.setSort(sort)
        } label: {
            if model.sort == sort {
                Label(label, systemImage: "checkmark")
            } else {
                Text(label)
            }
        }
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func noteCard(_ note: NoteEntity) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                Text(note.title)
                    .font(.headline)
                    .lineLimit(2)
                Spacer()
                Button {
                    model.togglePin(note)
                } label: {
                    Image(systemName: note.pinned ? "pin.fill" : "pin")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            LinkifiedText(text: String(note.content.prefix(220)) + (note.content.count > 220 ? "…" : ""))
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
        .contentShape(RoundedRectangle(cornerRadius: 12))
        .onTapGesture { editingNote = note }
        .contextMenu {
            Button(note.pinned ? "Unpin" : "Pin") { model.togglePin(note) }
            Button("Delete", role: .destructive) { deleteTarget = note }
        }
    }
}

private struct NoteEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var title: String
    @State var content: String
    let onSave: (String, String) -> Void

    var body: some View {
        NavigationStack {
            Form {
                TextField("Title", text: $title)
                TextField("Take a note…", text: $content, axis: .vertical)
                    .lineLimit(8...30)
            }
            .navigationTitle(title.isEmpty ? "New Note" : "Edit Note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(title, content)
                        dismiss()
                    }
                }
            }
        }
    }
}

extension NoteEntity: @retroactive Identifiable {}
