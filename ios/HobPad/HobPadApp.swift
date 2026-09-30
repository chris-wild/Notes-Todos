import SwiftUI
import HobPadCore

@main
struct HobPadApp: App {
    @State private var services = AppServices()

    var body: some Scene {
        WindowGroup {
            // Recipes first — the primary use case; it is also the launch tab.
            @Bindable var services = services
            TabView(selection: $services.selectedTab) {
                RecipesTab()
                    .tabItem { Label("Recipes", systemImage: "fork.knife") }
                    .tag(AppTab.recipes)
                TodosTab()
                    .tabItem { Label("Todos", systemImage: "checklist") }
                    .tag(AppTab.todos)
                NotesTab()
                    .tabItem { Label("Notes", systemImage: "note.text") }
                    .tag(AppTab.notes)
            }
            .environment(services)
            .task { await services.ops.start() }
        }
    }
}
