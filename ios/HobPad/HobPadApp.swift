import SwiftUI
import HobPadCore

@main
struct HobPadApp: App {
    @State private var services = AppServices()

    var body: some Scene {
        WindowGroup {
            // Recipes first — the primary use case; it is also the launch tab.
            TabView {
                RecipesTab()
                    .tabItem { Label("Recipes", systemImage: "fork.knife") }
                TodosTab()
                    .tabItem { Label("Todos", systemImage: "checklist") }
                NotesTab()
                    .tabItem { Label("Notes", systemImage: "note.text") }
            }
            .environment(services)
            .task { await services.ops.start() }
        }
    }
}
