import SwiftUI
import HobPadCore

@main
struct HobPadApp: App {
    @State private var services = AppServices()

    var body: some Scene {
        WindowGroup {
            TabView {
                NotesTab()
                    .tabItem { Label("Notes", systemImage: "note.text") }
                TodosTab()
                    .tabItem { Label("Todos", systemImage: "checklist") }
                RecipesTab()
                    .tabItem { Label("Recipes", systemImage: "fork.knife") }
            }
            .environment(services)
        }
    }
}
