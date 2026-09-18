package uk.co.promptbuilt.notestodos.ui.recipes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import uk.co.promptbuilt.notestodos.NotesTodosApp
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity

@Composable
fun RecipesScreen(onOpenTodos: () -> Unit) {
    val app = LocalContext.current.applicationContext as NotesTodosApp
    val viewModel: RecipesViewModel = viewModel {
        RecipesViewModel(
            app.recipesRepository,
            app.recipeFiles,
            app.secureKeys,
            app.ingredientTodosUseCase,
            app.backupManager,
            app.anthropicClient,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var editingRecipe by remember { mutableStateOf<RecipeEntity?>(null) }
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var viewingRecipe by remember { mutableStateOf<RecipeEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<RecipeEntity?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search recipes") },
                singleLine = true,
            )
            IconButton(onClick = { settingsOpen = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        state.message?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                if (!composerOpen) {
                    Card(
                        onClick = { composerOpen = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = "Add a recipe…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                } else {
                    RecipeForm(
                        title = "Add Recipe",
                        initial = null,
                        viewModel = viewModel,
                        onSave = { name, notes, attachment, _ ->
                            viewModel.saveRecipe(null, name, notes, attachment, false) {
                                composerOpen = false
                            }
                        },
                        onCancel = { composerOpen = false },
                    )
                }
            }

            if (state.recipes.isEmpty()) {
                item {
                    Text(
                        text = if (state.query.isBlank()) {
                            "No recipes yet — add one above."
                        } else {
                            "No recipes match your search."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            items(state.recipes, key = { it.id }) { recipe ->
                RecipeListItem(
                    recipe = recipe,
                    onOpen = { viewingRecipe = recipe },
                    onEdit = { editingRecipe = recipe },
                    onDelete = { deleteTarget = recipe },
                )
            }
        }
    }

    editingRecipe?.let { recipe ->
        Dialog(
            onDismissRequest = { editingRecipe = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                RecipeForm(
                    title = "Edit Recipe",
                    initial = recipe,
                    viewModel = viewModel,
                    onSave = { name, notes, attachment, removeExisting ->
                        viewModel.saveRecipe(recipe.id, name, notes, attachment, removeExisting) {
                            editingRecipe = null
                        }
                    },
                    onCancel = { editingRecipe = null },
                )
            }
        }
    }

    viewingRecipe?.let { recipe ->
        RecipeViewerDialog(
            recipe = recipe,
            recipeFiles = app.recipeFiles,
            ingredientAutomation = state.ingredientAutomation,
            working = state.working != null,
            onCreateIngredients = {
                viewModel.createIngredientTodos(recipe.id) {
                    viewingRecipe = null
                    onOpenTodos()
                }
            },
            onClose = { viewingRecipe = null },
        )
    }

    deleteTarget?.let { recipe ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recipe?") },
            text = { Text("\"${recipe.name}\" and its attachment will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecipe(recipe)
                    deleteTarget = null
                    if (viewingRecipe?.id == recipe.id) viewingRecipe = null
                    if (editingRecipe?.id == recipe.id) editingRecipe = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (settingsOpen) {
        SettingsDialog(
            hasKey = state.ingredientAutomation,
            viewModel = viewModel,
            onClose = { settingsOpen = false },
        )
    }

    state.working?.let { text ->
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.large) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(text = text, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun RecipeForm(
    title: String,
    initial: RecipeEntity?,
    viewModel: RecipesViewModel,
    onSave: (name: String, notes: String, attachment: RecipeFiles.Imported?, removeExisting: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var notes by rememberSaveable(initial?.id) { mutableStateOf(initial?.notes ?: "") }
    var pending by remember(initial?.id) { mutableStateOf<RecipeFiles.Imported?>(null) }
    var removeExisting by rememberSaveable(initial?.id) { mutableStateOf(false) }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importAttachment(uri) { imported ->
                pending?.let(viewModel::discardImported)
                pending = imported
                removeExisting = false
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importAttachment(uri) { imported ->
                pending?.let(viewModel::discardImported)
                pending = imported
                removeExisting = false
            }
        }
    }

    Column(modifier = Modifier.padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            placeholder = { Text("Recipe name") },
            singleLine = true,
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            placeholder = { Text("Recipe notes (optional)…") },
            minLines = 4,
        )

        Row(modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) { Text("Attach PDF") }
            TextButton(onClick = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Attach image") }
        }

        val attachmentLabel = when {
            pending != null -> "Will attach: ${pending!!.originalName}"
            removeExisting -> "Attachment will be removed on save."
            initial?.pdfFileName != null -> "File attached: ${initial.pdfOriginalName ?: "PDF"}"
            else -> null
        }
        if (attachmentLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = attachmentLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when {
                    pending != null -> TextButton(onClick = {
                        pending?.let(viewModel::discardImported)
                        pending = null
                    }) { Text("Discard") }
                    removeExisting -> TextButton(onClick = { removeExisting = false }) { Text("Undo") }
                    initial?.pdfFileName != null -> TextButton(onClick = { removeExisting = true }) { Text("Remove") }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                pending?.let(viewModel::discardImported)
                onCancel()
            }) { Text("Cancel") }
            TextButton(onClick = { onSave(name, notes, pending, removeExisting) }) {
                Text(if (initial == null) "Save Recipe" else "Update Recipe")
            }
        }
    }
}

@Composable
private fun RecipeListItem(
    recipe: RecipeEntity,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(recipe.name, style = MaterialTheme.typography.titleSmall)
                if (recipe.notes.isNotBlank()) {
                    Text(
                        text = recipe.notes.take(80) + if (recipe.notes.length > 80) "…" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row {
                    if (recipe.pdfFileName != null) {
                        Text(
                            text = "PDF",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(
                        text = DateFormat.getDateInstance(DateFormat.SHORT)
                            .format(Date(if (recipe.updatedAt != 0L) recipe.updatedAt else recipe.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit recipe")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete recipe",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RecipeViewerDialog(
    recipe: RecipeEntity,
    recipeFiles: RecipeFiles,
    ingredientAutomation: Boolean,
    working: Boolean,
    onCreateIngredients: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recipe.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (ingredientAutomation) {
                        TextButton(onClick = onCreateIngredients, enabled = !working) {
                            Text("Create ingredient list")
                        }
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                if (recipe.notes.isNotBlank()) {
                    Text(
                        text = recipe.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .heightIn(max = 160.dp),
                    )
                }
                if (recipe.pdfFileName != null) {
                    PdfViewer(
                        file = recipeFiles.fileFor(recipe.pdfFileName),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No PDF attached to this recipe.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    hasKey: Boolean,
    viewModel: RecipesViewModel,
    onClose: () -> Unit,
) {
    var keyDraft by remember { mutableStateOf("") }
    var confirmImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri != null) viewModel.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) confirmImport = uri
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Anthropic API key", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (hasKey) {
                        "A key is stored securely on this device. It unlocks \"Create ingredient list\"."
                    } else {
                        "Add a key to unlock \"Create ingredient list\". It is stored in the Android Keystore and never backed up."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    placeholder = { Text(if (hasKey) "Replace key (sk-ant-…)" else "sk-ant-…") },
                    singleLine = true,
                    // Password keyboard: stops autocorrect/auto-capitalize mangling the key.
                    // Text stays visible (no VisualTransformation) so typos can be spotted.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row {
                    TextButton(onClick = {
                        viewModel.saveAnthropicKey(keyDraft) { ok -> if (ok) keyDraft = "" }
                    }) { Text("Save key") }
                    if (hasKey) {
                        TextButton(onClick = viewModel::deleteAnthropicKey) { Text("Remove key") }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Backup", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Export everything (including recipe PDFs) to a zip you can keep anywhere. " +
                        "Import replaces all current data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = {
                        exportLauncher.launch("notes-todos-backup.zip")
                    }) { Text("Export backup") }
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("Import backup") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Replace all data?") },
            text = { Text("Importing replaces every note, todo, category and recipe on this device with the backup's contents.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importBackup(uri)
                    confirmImport = null
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = null }) { Text("Cancel") }
            },
        )
    }
}
