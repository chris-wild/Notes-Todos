package uk.co.promptbuilt.notestodos.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import uk.co.promptbuilt.notestodos.R
import uk.co.promptbuilt.notestodos.ui.notes.NotesScreen
import uk.co.promptbuilt.notestodos.ui.recipes.RecipesScreen
import uk.co.promptbuilt.notestodos.ui.todos.TodosScreen

enum class Tab(val route: String, @param:StringRes val labelRes: Int, val icon: ImageVector) {
    Notes("notes", R.string.tab_notes, Icons.AutoMirrored.Filled.StickyNote2),
    Todos("todos", R.string.tab_todos, Icons.Filled.Checklist),
    Recipes("recipes", R.string.tab_recipes, Icons.Filled.RestaurantMenu),
}

@Composable
fun AppScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Notes.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Tab.Notes.route) { NotesScreen() }
            composable(Tab.Todos.route) { TodosScreen() }
            composable(Tab.Recipes.route) {
                RecipesScreen(
                    onOpenTodos = {
                        navController.navigate(Tab.Todos.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title)
    }
}
