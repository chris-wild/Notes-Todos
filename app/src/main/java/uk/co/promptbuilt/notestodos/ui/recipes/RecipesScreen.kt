package uk.co.promptbuilt.notestodos.ui.recipes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import uk.co.promptbuilt.notestodos.ui.common.LinkifiedText
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.DateFormat
import java.util.Date
import uk.co.promptbuilt.notestodos.BuildConfig
import uk.co.promptbuilt.notestodos.NotesTodosApp
import uk.co.promptbuilt.notestodos.data.AppPrefs
import uk.co.promptbuilt.notestodos.backup.DriveBackup
import uk.co.promptbuilt.notestodos.data.RecipeFiles
import uk.co.promptbuilt.notestodos.data.db.RecipeAttachmentEntity
import uk.co.promptbuilt.notestodos.data.db.RecipeEntity

@Composable
fun RecipesScreen(onOpenTodos: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NotesTodosApp
    val viewModel: RecipesViewModel = viewModel {
        RecipesViewModel(
            app.recipesRepository,
            app.recipeFiles,
            app.secureKeys,
            app.ingredientTodosUseCase,
            app.safBackup,
            app.anthropicClient,
            app.ocrService,
            app.opsStore,
            app.appPrefs,
            app::usesByoKey,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ops by app.opsStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var editingRecipe by remember { mutableStateOf<RecipeEntity?>(null) }
    var composerOpen by rememberSaveable { mutableStateOf(false) }
    var viewingRecipe by remember { mutableStateOf<RecipeEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<RecipeEntity?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var paywallOpen by remember { mutableStateOf(false) }
    // The recipe being converted, with its credit cost (null cost = converts free).
    var converting by remember { mutableStateOf<Pair<RecipeEntity, Int?>?>(null) }

    fun startConversion(recipe: RecipeEntity) {
        scope.launch {
            when (val gate = viewModel.conversionGate(recipe)) {
                ConversionGate.Free -> converting = recipe to null
                is ConversionGate.Confirm -> converting = recipe to gate.cost
                ConversionGate.Paywall -> paywallOpen = true
            }
        }
    }

    // "Take photo of recipe": camera writes to a FileProvider Uri in cache/captures/.
    var captureUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = captureUri
        if (taken && uri != null) {
            viewModel.importAttachment(uri) { imported ->
                viewModel.createFromCapture(imported)
            }
        }
        captureUri = null
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(dir, "recipe-capture.jpg"),
        )
        captureUri = uri
        cameraLauncher.launch(uri)
    }

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
            IconButton(onClick = ::launchCamera) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo of recipe")
            }
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
                        existingAttachments = emptyList(),
                        viewModel = viewModel,
                        onSave = { name, notes, newAttachments, removals ->
                            viewModel.saveRecipe(null, name, notes, newAttachments, removals) {
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
                    attachmentCount = state.attachmentsByRecipe[recipe.id]?.size ?: 0,
                    onOpen = { viewingRecipe = recipe },
                    onEdit = { editingRecipe = recipe },
                    onDelete = { deleteTarget = recipe },
                )
            }
        }
    }

    viewingRecipe?.let { opened ->
        // The live row, so a rename made from the viewer shows straight away.
        val recipe = state.recipes.firstOrNull { it.id == opened.id } ?: opened
        RecipeViewerDialog(
            recipe = recipe,
            attachments = state.attachmentsByRecipe[recipe.id].orEmpty(),
            recipeFiles = app.recipeFiles,
            working = state.working != null,
            onCreateIngredients = { startConversion(recipe) },
            onEdit = { editingRecipe = recipe },
            onDelete = { deleteTarget = recipe },
            onClose = { viewingRecipe = null },
        )
    }

    editingRecipe?.let { recipe ->
        Dialog(
            onDismissRequest = { editingRecipe = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                shape = MaterialTheme.shapes.large,
            ) {
                RecipeForm(
                    title = "Edit Recipe",
                    initial = recipe,
                    existingAttachments = state.attachmentsByRecipe[recipe.id].orEmpty(),
                    viewModel = viewModel,
                    onSave = { name, notes, newAttachments, removals ->
                        viewModel.saveRecipe(recipe.id, name, notes, newAttachments, removals) {
                            editingRecipe = null
                        }
                    },
                    onCancel = { editingRecipe = null },
                )
            }
        }
    }

    converting?.let { (recipe, cost) ->
        ConvertDialog(
            cost = cost,
            balance = ops.balance,
            working = state.working != null,
            onCreate = { multiplier ->
                viewModel.createIngredientTodos(
                    recipe.id,
                    multiplier,
                    onNeedsCredits = {
                        converting = null
                        paywallOpen = true
                    },
                    onSuccess = { category ->
                        converting = null
                        viewingRecipe = null
                        app.pendingTodoCategory.value = category
                        onOpenTodos()
                    },
                )
            },
            onCancel = { converting = null },
        )
    }

    state.pendingName?.let { pending ->
        NameRecipeDialog(
            dailyLimit = pending.reason == PendingName.Reason.DAILY_LIMIT,
            onSave = viewModel::renamePending,
            onBuyCredits = {
                viewModel.dismissPendingName()
                paywallOpen = true
            },
            onCancel = viewModel::dismissPendingName,
        )
    }

    if (paywallOpen) {
        PaywallDialog(opsStore = app.opsStore, onClose = { paywallOpen = false })
    }

    deleteTarget?.let { recipe ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete recipe?") },
            text = { Text("\"${recipe.name}\" and its attachments will be permanently deleted.") },
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
            hasKey = state.hasKey,
            viewModel = viewModel,
            driveBackup = app.driveBackup,
            balance = ops.balance,
            appPrefs = app.appPrefs,
            message = state.message,
            onRefreshBalance = { app.opsStore.refreshBalance() },
            onBuyCredits = {
                settingsOpen = false
                paywallOpen = true
            },
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
    existingAttachments: List<RecipeAttachmentEntity>,
    viewModel: RecipesViewModel,
    onSave: (
        name: String,
        notes: String,
        newAttachments: List<RecipeFiles.Imported>,
        removals: List<RecipeAttachmentEntity>,
    ) -> Unit,
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(initial?.id) { mutableStateOf(initial?.name ?: "") }
    var notes by rememberSaveable(initial?.id) { mutableStateOf(initial?.notes ?: "") }
    var pending by remember(initial?.id) { mutableStateOf<List<RecipeFiles.Imported>>(emptyList()) }
    var removals by remember(initial?.id) { mutableStateOf<List<RecipeAttachmentEntity>>(emptyList()) }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importAttachment(uri) { imported -> pending = pending + imported }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importAttachment(uri) { imported -> pending = pending + imported }
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
            supportingText = if (initial == null && name.isBlank()) {
                { Text("Leave blank to have it named from the attached recipe.") }
            } else {
                null
            },
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
            maxLines = 12,
        )

        Row(modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) { Text("Attach PDF") }
            TextButton(onClick = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Attach image") }
        }

        for (attachment in existingAttachments) {
            val removed = attachment in removals
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (attachment.originalName ?: attachment.fileName) +
                        if (removed) "  (will be removed)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (removed) {
                    TextButton(onClick = { removals = removals - attachment }) { Text("Undo") }
                } else {
                    TextButton(onClick = { removals = removals + attachment }) { Text("Remove") }
                }
            }
        }
        for (imported in pending) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Will attach: ${imported.originalName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = {
                    viewModel.discardImported(imported)
                    pending = pending - imported
                }) { Text("Discard") }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                pending.forEach(viewModel::discardImported)
                onCancel()
            }) { Text("Cancel") }
            TextButton(onClick = { onSave(name, notes, pending, removals) }) {
                Text(if (initial == null) "Save Recipe" else "Update Recipe")
            }
        }
    }
}

@Composable
private fun RecipeListItem(
    recipe: RecipeEntity,
    attachmentCount: Int,
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
                    if (attachmentCount > 0) {
                        Text(
                            text = if (attachmentCount == 1) "PDF" else "$attachmentCount PDFs",
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
    attachments: List<RecipeAttachmentEntity>,
    recipeFiles: RecipeFiles,
    working: Boolean,
    onCreateIngredients: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
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
                    TextButton(onClick = onClose) { Text("Close") }
                }
                if (recipe.notes.isNotBlank()) {
                    // Long notes scroll within their band, as on iOS, rather than being cut off.
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        LinkifiedText(text = recipe.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (attachments.isNotEmpty()) {
                    PdfViewer(
                        files = attachments.map { recipeFiles.fileFor(it.fileName) },
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
                // The primary action is always visible and full width, as on iOS; credits,
                // quantities and cost are handled in the dialog it opens.
                Button(
                    onClick = onCreateIngredients,
                    enabled = !working,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text("Create ingredient list")
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    hasKey: Boolean,
    viewModel: RecipesViewModel,
    driveBackup: DriveBackup,
    balance: Int?,
    appPrefs: AppPrefs,
    message: String?,
    onRefreshBalance: suspend () -> Unit,
    onBuyCredits: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var units by remember { mutableStateOf(appPrefs.units) }
    var keyDraft by remember { mutableStateOf("") }
    var confirmImport by remember { mutableStateOf<Uri?>(null) }

    // As iOS's Settings sheet: the balance is fetched fresh whenever Settings opens.
    LaunchedEffect(Unit) { onRefreshBalance() }

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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Recipe conversions", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Credits remaining", modifier = Modifier.weight(1f))
                    Text(balance?.toString() ?: "–", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "One credit converts one recipe page into a shopping list. " +
                        "Naming new recipes automatically is free.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onBuyCredits) { Text("Buy credits") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text("Units", style = MaterialTheme.typography.titleSmall)
                listOf(
                    AppPrefs.Units.AUTOMATIC to "Automatic",
                    AppPrefs.Units.METRIC to "Metric (g, ml)",
                    AppPrefs.Units.US to "US (oz, cups)",
                ).forEach { (option, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                appPrefs.units = option
                                units = option
                            },
                    ) {
                        RadioButton(
                            selected = units == option,
                            onClick = {
                                appPrefs.units = option
                                units = option
                            },
                        )
                        Text(label)
                    }
                }
                Text(
                    text = "Ingredient lists are converted to these units, whatever the recipe uses. " +
                        "Automatic follows your device region.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // A personal key bypasses metering: a developer tool, absent from release builds.
                if (BuildConfig.DEBUG) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Anthropic API key (dev build)", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (hasKey) {
                        "A key is stored on this device. Extraction bypasses metering while it is present."
                    } else {
                        "Add a key to bypass metering in this dev build. It is stored on this device and never backed up."
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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                )
                Row {
                    TextButton(onClick = {
                        viewModel.saveAnthropicKey(keyDraft) { ok -> if (ok) keyDraft = "" }
                    }) { Text("Save key") }
                    if (hasKey) {
                        TextButton(onClick = viewModel::deleteAnthropicKey) { Text("Remove key") }
                    }
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
                        exportLauncher.launch("hobpad-backup.zip")
                    }) { Text("Export backup") }
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    }) { Text("Import backup") }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DriveBackupSection(driveBackup)

                // Results of actions taken here ("Backup exported" and so on) show here too,
                // as in iOS's Settings sheet, not only on the list behind it.
                message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
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
            text = {
                val size = remember(uri) { backupSize(context, uri) }
                val backup = size?.let { "this $it backup" } ?: "this backup"
                Text("Importing $backup replaces every note, todo, category and recipe on this device.")
            },
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

/** The picked backup's size for the import confirmation, as iOS shows it ("12.3 MB"). */
private fun backupSize(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) android.text.format.Formatter.formatShortFileSize(context, it.getLong(0)) else null
        }
    }.getOrNull()
