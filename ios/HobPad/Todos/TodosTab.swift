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
    @Environment(AppServices.self) private var services
    @Bindable var model: TodosModel
    @State private var newTodo = ""
    @State private var addingCategory = false
    @State private var categoryDraft = ""
    @State private var categoryError: String?
    @State private var confirmDeleteCategory = false
    @State private var confirmDeleteAll = false
    @FocusState private var addFieldFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                categoryStrip
                addRow
                if !model.visibleTodos.isEmpty {
                    // Bulk deletion is one visible button on the list it empties;
                    // per-item deletion is the bin on each row (the Android pattern,
                    // adopted here after two rounds of selection-mode clunk — Chris, Sept 30).
                    HStack {
                        Spacer()
                        Button("Delete All", role: .destructive) { confirmDeleteAll = true }
                            .font(.subheadline)
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 4)
                }
                List {
                    if model.visibleTodos.isEmpty {
                        Text("Nothing in \(model.activeCategory) yet — add one above.")
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
                            Button {
                                model.delete(todo)
                            } label: {
                                Image(systemName: "trash")
                                    .foregroundStyle(.red)
                                    .accessibilityLabel("Delete \(todo.text)")
                            }
                            .buttonStyle(.plain)
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) { model.delete(todo) }
                        }
                    }
                }
                .listStyle(.plain)
                .scrollDismissesKeyboard(.immediately)
            }
            .navigationTitle("Todos")
            .toolbar {
                // The way OUT of the keyboard: without this, focusing the add field left
                // no dismissal route at all — the keyboard sat over the tab bar until the
                // app was killed (Chris, Sept 30).
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { addFieldFocused = false }
                }
            }
            // Changing category is "I'm done typing here" too.
            .onChange(of: model.activeCategory) { addFieldFocused = false }
            // An alert, not a confirmationDialog: iOS 26 renders dialogs without the
            // automatic Cancel button they used to get (Chris's report, Sept 30).
            .alert(
                model.visibleTodos.count == 1
                    ? "Delete the only todo?"
                    : "Delete all \(model.visibleTodos.count) todos?",
                isPresented: $confirmDeleteAll,
            ) {
                Button("Delete All", role: .destructive) {
                    // Emptying a non-default category removes the category too.
                    model.delete(
                        ids: Set(model.visibleTodos.map { $0.id }),
                        removeCategory: !model.activeIsDefault,
                    )
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(!model.activeIsDefault
                     ? "Everything in \"\(model.activeCategory)\" will be deleted, and the empty category removed."
                     : "Everything in \"\(model.activeCategory)\" will be deleted.")
            }
            .task { await model.observeTodos() }
            .task { await model.observeCategories() }
            // A freshly created ingredient list routes here with its category preselected.
            .onAppear { consumePendingCategory() }
            .onChange(of: services.pendingTodoCategory) { consumePendingCategory() }
            .alert("Delete category?", isPresented: $confirmDeleteCategory) {
                Button("Cancel", role: .cancel) {}
                Button("OK", role: .destructive) { model.deleteActiveCategory() }
            } message: {
                Text("Todos in \"\(model.activeCategory)\" will be moved to General.")
            }
        }
    }

    private func consumePendingCategory() {
        if let category = services.pendingTodoCategory {
            model.activeCategory = category
            services.pendingTodoCategory = nil
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
            // Unmistakably a place to type, in BOTH color schemes: a leading pencil (the
            // search bar's magnifier trick), a fill, AND a stroke — fill alone all but
            // vanished against dark mode's black (Chris, Sept 30).
            HStack(spacing: 8) {
                Image(systemName: "pencil")
                    .foregroundStyle(.secondary)
                TextField("Add to \(model.activeCategory)…", text: $newTodo)
                    .focused($addFieldFocused)
                    .onSubmit(submit)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemBackground), in: Capsule())
            .overlay(Capsule().strokeBorder(Color(.systemGray3), lineWidth: 1))
            Button("Add", action: submit)
                .buttonStyle(.borderedProminent)
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
