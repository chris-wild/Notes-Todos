import SwiftUI
import HobPadCore

struct TodosTab: View {
    @Environment(AppServices.self) private var services
    @State private var model: TodosModel?

    var body: some View {
        Group {
            if let model {
                TodosContent(model: model)
            } else {
                ProgressView()
            }
        }
        .onAppear {
            if model == nil { model = TodosModel(core: services.core) }
        }
    }
}

private struct TodosContent: View {
    @Bindable var model: TodosModel
    @State private var newTodo = ""
    @State private var addingCategory = false
    @State private var categoryDraft = ""
    @State private var categoryError: String?
    @State private var confirmDeleteCategory = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                categoryStrip
                addRow
                List {
                    if model.visibleTodos.isEmpty {
                        Text(model.query.isEmpty
                             ? "Nothing in \(model.activeCategory) yet — add one above."
                             : "No todos match your search.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(model.visibleTodos, id: \.id) { todo in
                        HStack {
                            Button {
                                model.toggle(todo)
                            } label: {
                                Image(systemName: todo.completed ? "checkmark.square.fill" : "square")
                                    .foregroundStyle(todo.completed ? Color.accentColor : .secondary)
                            }
                            .buttonStyle(.plain)
                            Text(todo.text)
                                .strikethrough(todo.completed)
                                .foregroundStyle(todo.completed ? .secondary : .primary)
                            Spacer()
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) { model.delete(todo) }
                        }
                    }
                }
                .listStyle(.plain)
            }
            .navigationTitle("Todos")
            .searchable(text: $model.query, prompt: "Search todos")
            .task { await model.observeTodos() }
            .task { await model.observeCategories() }
            .alert("Delete category?", isPresented: $confirmDeleteCategory) {
                Button("Cancel", role: .cancel) {}
                Button("OK", role: .destructive) { model.deleteActiveCategory() }
            } message: {
                Text("Todos in \"\(model.activeCategory)\" will be moved to General.")
            }
        }
    }

    private var categoryStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(model.categories, id: \.self) { category in
                    let active = category.lowercased() == model.activeCategory.lowercased()
                    HStack(spacing: 4) {
                        Text(category)
                        if active && !model.activeIsDefault {
                            Image(systemName: "xmark")
                                .font(.caption2)
                                .onTapGesture { confirmDeleteCategory = true }
                        }
                    }
                    .font(.subheadline)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .background(active ? Color.accentColor.opacity(0.25) : Color(.secondarySystemBackground),
                                in: Capsule())
                    .onTapGesture { model.activeCategory = category }
                }
                Button {
                    categoryError = nil
                    addingCategory = true
                } label: {
                    Image(systemName: "plus")
                        .padding(6)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
        .alert("New category", isPresented: $addingCategory) {
            TextField("Category name", text: $categoryDraft)
            Button("Cancel", role: .cancel) { categoryDraft = "" }
            Button("Add") {
                let draft = categoryDraft
                Task {
                    if await model.addCategory(draft) {
                        categoryDraft = ""
                    } else {
                        categoryError = "\"\(draft)\" already exists (or is empty)."
                    }
                }
            }
        }
        .alert("Could not add category", isPresented: Binding(
            get: { categoryError != nil },
            set: { if !$0 { categoryError = nil } },
        )) {
            Button("OK") { categoryError = nil }
        } message: {
            Text(categoryError ?? "")
        }
    }

    private var addRow: some View {
        HStack {
            TextField("Add to \(model.activeCategory)…", text: $newTodo)
                .textFieldStyle(.roundedBorder)
                .onSubmit(submit)
            Button("Add", action: submit)
                .disabled(newTodo.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(.horizontal)
        .padding(.bottom, 6)
    }

    private func submit() {
        let text = newTodo.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        model.addTodo(text)
        newTodo = ""
    }
}
