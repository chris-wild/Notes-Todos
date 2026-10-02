import Foundation
import Observation
import HobPadCore

@Observable @MainActor
final class TodosModel {
    private let core: CoreServices

    private(set) var allTodos: [TodoEntity] = []
    private(set) var categories: [String] = ["General", "Shopping List"]
    var activeCategory = "General"

    init(core: CoreServices) {
        self.core = core
    }

    var visibleTodos: [TodoEntity] {
        allTodos
            .filter { todo in
                let category = todo.category.isEmpty ? "General" : todo.category
                return category.lowercased() == activeCategory.lowercased()
            }
    }

    var activeIsDefault: Bool {
        CategoryRules.shared.isDefault(name: activeCategory)
    }

    func observeTodos() async {
        for await todos in core.todosRepository.observeTodos() {
            allTodos = todos
        }
    }

    func observeCategories() async {
        for await list in core.todosRepository.observeCategories() {
            categories = list
            if !list.contains(where: { $0.lowercased() == activeCategory.lowercased() }) {
                activeCategory = "General"
            }
        }
    }

    func addTodo(_ text: String) {
        let category = activeCategory
        Task { try? await core.todosRepository.addTodo(text: text, category: category) }
    }

    func toggle(_ todo: TodoEntity) {
        Task { try? await core.todosRepository.setCompleted(id: todo.id, completed: !todo.completed) }
    }

    func delete(_ todo: TodoEntity) {
        Task { try? await core.todosRepository.deleteTodo(id: todo.id) }
    }

    /// Bulk deletion for Delete All: ids are resolved against the CURRENT list first, so a
    /// stale id (a todo deleted elsewhere meanwhile) is simply skipped. With
    /// [removeCategory], the now-empty non-default category goes too — strictly AFTER its
    /// todos in the same task, because deleting a category moves any survivors to General.
    func delete(ids: Set<Int64>, removeCategory: Bool = false) {
        let targets = allTodos.filter { ids.contains($0.id) }
        let category = activeCategory
        if removeCategory { activeCategory = "General" }
        Task {
            for todo in targets { try? await core.todosRepository.deleteTodo(id: todo.id) }
            if removeCategory {
                try? await core.todosRepository.deleteCategory(name: category)
            }
        }
    }

    /// Returns false when the name is blank or already exists.
    func addCategory(_ name: String) async -> Bool {
        let added = (try? await core.todosRepository.addCategory(name: name).boolValue) ?? false
        if added { activeCategory = name.trimmingCharacters(in: .whitespaces) }
        return added
    }

    func deleteActiveCategory() {
        let name = activeCategory
        activeCategory = "General"
        Task { try? await core.todosRepository.deleteCategory(name: name) }
    }
}
