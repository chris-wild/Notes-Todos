/**
 * In-memory data store backed by S3 JSON files.
 * Replaces PostgreSQL for all application data.
 *
 * Tables migrated: users, api_keys, notes, todos, todo_categories, recipes, ingredients
 */

const { S3Client, GetObjectCommand, PutObjectCommand } = require('@aws-sdk/client-s3');

const DATA_PREFIX = 'data/';

let s3;
let bucket;

// In-memory collections
let users = [];
let apiKeys = [];
let notes = [];
let todos = [];
let todoCategories = [];
let recipes = [];
let ingredients = [];

// Auto-increment counters
let nextId = {};

// ── S3 helpers ──────────────────────────────────────────

async function loadJson(key) {
  try {
    const resp = await s3.send(new GetObjectCommand({ Bucket: bucket, Key: DATA_PREFIX + key }));
    const body = await resp.Body.transformToString('utf-8');
    return JSON.parse(body);
  } catch (err) {
    if (err.name === 'NoSuchKey' || err.$metadata?.httpStatusCode === 404) return null;
    throw err;
  }
}

async function saveJson(key, data) {
  await s3.send(new PutObjectCommand({
    Bucket: bucket,
    Key: DATA_PREFIX + key,
    Body: JSON.stringify(data, null, 2),
    ContentType: 'application/json'
  }));
}

function _save(key, data) {
  saveJson(key, data).catch(err => console.error(`datastore: failed to persist ${key}:`, err.message));
}

async function _saveAwait(key, data) {
  await saveJson(key, data);
}

function _maxId(collection) {
  if (collection.length === 0) return 0;
  return Math.max(...collection.map(r => Number(r.id) || 0));
}

function _nextId(name, collection) {
  if (nextId[name] == null) nextId[name] = _maxId(collection) + 1;
  return nextId[name]++;
}

// ── Init ────────────────────────────────────────────────

async function init() {
  bucket = process.env.S3_BUCKET;
  if (!bucket) throw new Error('S3_BUCKET env var is required for datastore');

  const region = process.env.S3_REGION || process.env.AWS_REGION || 'eu-west-2';
  s3 = new S3Client({ region });

  const [u, ak, n, t, tc, r, ing] = await Promise.all([
    loadJson('users.json'),
    loadJson('api-keys.json'),
    loadJson('notes.json'),
    loadJson('todos.json'),
    loadJson('todo-categories.json'),
    loadJson('recipes.json'),
    loadJson('ingredients.json')
  ]);

  if (u) users = u;
  if (ak) apiKeys = ak;
  if (n) notes = n;
  if (t) todos = t;
  if (tc) todoCategories = tc;
  if (r) recipes = r;
  if (ing) ingredients = ing;

  // Initialize ID counters
  nextId.users = _maxId(users) + 1;
  nextId.apiKeys = _maxId(apiKeys) + 1;
  nextId.notes = _maxId(notes) + 1;
  nextId.todos = _maxId(todos) + 1;
  nextId.todoCategories = _maxId(todoCategories) + 1;
  nextId.recipes = _maxId(recipes) + 1;
  nextId.ingredients = _maxId(ingredients) + 1;

  console.log(`datastore: loaded ${users.length} users, ${notes.length} notes, ${todos.length} todos, ${recipes.length} recipes from S3`);
}

// ── Users ───────────────────────────────────────────────

function getUserByUsername(username) {
  return users.find(u => u.username === username) || null;
}

function getUserById(id) {
  const numId = Number(id);
  return users.find(u => Number(u.id) === numId) || null;
}

async function createUser(username, passwordHash) {
  const now = new Date().toISOString();
  const user = {
    id: nextId.users++,
    username,
    password_hash: passwordHash,
    anthropic_api_key: null,
    created_at: now
  };
  users.push(user);
  await _saveAwait('users.json', users);
  return user;
}

async function updateUserPassword(id, passwordHash) {
  const numId = Number(id);
  const user = users.find(u => Number(u.id) === numId);
  if (user) {
    user.password_hash = passwordHash;
    await _saveAwait('users.json', users);
  }
}

async function updateUserAnthropicKey(id, encryptedKey) {
  const numId = Number(id);
  const user = users.find(u => Number(u.id) === numId);
  if (user) {
    user.anthropic_api_key = encryptedKey;
    await _saveAwait('users.json', users);
  }
}

async function deleteUserAnthropicKey(id) {
  const numId = Number(id);
  const user = users.find(u => Number(u.id) === numId);
  if (user) {
    user.anthropic_api_key = null;
    await _saveAwait('users.json', users);
  }
}

function getUserAnthropicKey(id) {
  const numId = Number(id);
  const user = users.find(u => Number(u.id) === numId);
  return user?.anthropic_api_key || null;
}

// ── API Keys ────────────────────────────────────────────

function listApiKeys(userId) {
  const numId = Number(userId);
  return apiKeys
    .filter(k => Number(k.user_id) === numId)
    .sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
}

async function createApiKey(encryptedKey, keyHash, name, userId) {
  const now = new Date().toISOString();
  const record = {
    id: nextId.apiKeys++,
    key: encryptedKey,
    key_hash: keyHash,
    name,
    user_id: Number(userId),
    active: true,
    created_at: now
  };
  apiKeys.push(record);
  await _saveAwait('api-keys.json', apiKeys);
  return record;
}

async function deleteApiKey(id, userId) {
  const numId = Number(id);
  const numUserId = Number(userId);
  const idx = apiKeys.findIndex(k => Number(k.id) === numId && Number(k.user_id) === numUserId);
  if (idx === -1) return 0;
  apiKeys.splice(idx, 1);
  await _saveAwait('api-keys.json', apiKeys);
  return 1;
}

function findApiKeyByHash(keyHash) {
  const ak = apiKeys.find(k => k.key_hash === keyHash && k.active);
  if (!ak) return null;
  const user = getUserById(ak.user_id);
  if (!user) return null;
  return { ...ak, username: user.username };
}

// ── Notes ───────────────────────────────────────────────

function listNotes(userId) {
  const numId = Number(userId);
  return notes
    .filter(n => Number(n.user_id) === numId)
    .sort((a, b) => {
      if ((b.pinned ? 1 : 0) !== (a.pinned ? 1 : 0)) return (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0);
      if ((Number(b.sort_order) || 0) !== (Number(a.sort_order) || 0)) return (Number(b.sort_order) || 0) - (Number(a.sort_order) || 0);
      return new Date(b.updated_at) - new Date(a.updated_at);
    });
}

function listNotesByUpdated(userId) {
  const numId = Number(userId);
  return notes
    .filter(n => Number(n.user_id) === numId)
    .sort((a, b) => new Date(b.updated_at) - new Date(a.updated_at));
}

function searchNotes(userId, query) {
  const numId = Number(userId);
  const q = query.toLowerCase();
  return notes
    .filter(n => Number(n.user_id) === numId && ((n.title || '').toLowerCase().includes(q) || (n.content || '').toLowerCase().includes(q)))
    .sort((a, b) => new Date(b.updated_at) - new Date(a.updated_at));
}

function getNote(id, userId) {
  const numId = Number(id);
  const numUserId = Number(userId);
  return notes.find(n => Number(n.id) === numId && Number(n.user_id) === numUserId) || null;
}

async function createNote(title, content, userId) {
  const now = new Date().toISOString();
  const sortOrder = Date.now();
  const record = {
    id: nextId.notes++,
    title,
    content: content || '',
    pinned: false,
    sort_order: sortOrder,
    user_id: Number(userId),
    created_at: now,
    updated_at: now
  };
  notes.push(record);
  await _saveAwait('notes.json', notes);
  return record;
}

async function updateNote(id, userId, fields) {
  const numId = Number(id);
  const numUserId = Number(userId);
  const note = notes.find(n => Number(n.id) === numId && Number(n.user_id) === numUserId);
  if (!note) return null;

  if (fields.title !== undefined) note.title = fields.title;
  if (fields.content !== undefined) note.content = fields.content;
  if (fields.pinned != null) note.pinned = fields.pinned;
  if (fields.sort_order != null) note.sort_order = fields.sort_order;
  note.updated_at = new Date().toISOString();

  await _saveAwait('notes.json', notes);
  return note;
}

async function deleteNote(id, userId) {
  const numId = Number(id);
  const numUserId = Number(userId);
  const idx = notes.findIndex(n => Number(n.id) === numId && Number(n.user_id) === numUserId);
  if (idx === -1) return 0;
  notes.splice(idx, 1);
  await _saveAwait('notes.json', notes);
  return 1;
}

// ── Todos ───────────────────────────────────────────────

function listTodos(userId, category) {
  const numId = Number(userId);
  let filtered = todos.filter(t => Number(t.user_id) === numId);
  if (category) filtered = filtered.filter(t => t.category === category);
  return filtered.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
}

function searchTodos(userId, query, completed) {
  const numId = Number(userId);
  const q = query ? query.toLowerCase() : null;
  return todos
    .filter(t => {
      if (Number(t.user_id) !== numId) return false;
      if (q && !(t.text || '').toLowerCase().includes(q)) return false;
      if (completed !== undefined) {
        const comp = completed === 'true' || completed === true;
        if (t.completed !== comp) return false;
      }
      return true;
    })
    .sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
}

function getTodo(id, userId) {
  const numId = Number(id);
  const numUserId = Number(userId);
  return todos.find(t => Number(t.id) === numId && Number(t.user_id) === numUserId) || null;
}

async function createTodo(text, category, userId) {
  const now = new Date().toISOString();
  const record = {
    id: nextId.todos++,
    text,
    completed: false,
    category: category || 'General',
    user_id: Number(userId),
    created_at: now
  };
  todos.push(record);
  await _saveAwait('todos.json', todos);
  return record;
}

async function createTodoWithCompleted(text, completed, userId) {
  const now = new Date().toISOString();
  const record = {
    id: nextId.todos++,
    text,
    completed: !!completed,
    category: 'General',
    user_id: Number(userId),
    created_at: now
  };
  todos.push(record);
  await _saveAwait('todos.json', todos);
  return record;
}

async function updateTodo(id, userId, fields) {
  const numId = Number(id);
  const numUserId = Number(userId);
  const todo = todos.find(t => Number(t.id) === numId && Number(t.user_id) === numUserId);
  if (!todo) return null;

  if (fields.completed !== undefined) todo.completed = !!fields.completed;
  if (fields.text !== undefined) todo.text = fields.text;

  await _saveAwait('todos.json', todos);
  return todo;
}

async function deleteTodo(id, userId) {
  const numId = Number(id);
  const numUserId = Number(userId);
  const idx = todos.findIndex(t => Number(t.id) === numId && Number(t.user_id) === numUserId);
  if (idx === -1) return 0;
  todos.splice(idx, 1);
  await _saveAwait('todos.json', todos);
  return 1;
}

function countTodos(userId, category) {
  const numId = Number(userId);
  return todos.filter(t => Number(t.user_id) === numId && t.category === category).length;
}

async function deleteTodosByCategory(userId, category) {
  const numId = Number(userId);
  const before = todos.length;
  todos = todos.filter(t => !(Number(t.user_id) === numId && t.category === category));
  if (todos.length !== before) {
    await _saveAwait('todos.json', todos);
  }
}

async function reassignTodosCategory(userId, fromNormalized, toCategory) {
  const numId = Number(userId);
  let changed = false;
  for (const t of todos) {
    if (Number(t.user_id) === numId && (t.category || '').toLowerCase() === fromNormalized) {
      t.category = toCategory;
      changed = true;
    }
  }
  if (changed) await _saveAwait('todos.json', todos);
}

// ── Todo Categories ─────────────────────────────────────

function listCategories(userId) {
  const numId = Number(userId);
  return todoCategories
    .filter(c => Number(c.user_id) === numId)
    .sort((a, b) => (a.name || '').localeCompare(b.name || ''))
    .map(c => c.name);
}

function listUsedCategories(userId) {
  const numId = Number(userId);
  const cats = new Set();
  for (const t of todos) {
    if (Number(t.user_id) === numId && t.category) cats.add(t.category);
  }
  return [...cats].sort();
}

async function createCategory(name, normalizedName, userId) {
  const numId = Number(userId);
  const existing = todoCategories.find(c => Number(c.user_id) === numId && c.normalized_name === normalizedName);
  if (existing) return existing; // ON CONFLICT DO NOTHING equivalent

  const now = new Date().toISOString();
  const record = {
    id: nextId.todoCategories++,
    name,
    normalized_name: normalizedName,
    user_id: numId,
    created_at: now
  };
  todoCategories.push(record);
  await _saveAwait('todo-categories.json', todoCategories);
  return record;
}

async function deleteCategory(userId, normalizedName) {
  const numId = Number(userId);
  // Reassign todos to General
  await reassignTodosCategory(userId, normalizedName, 'General');
  // Remove category
  const idx = todoCategories.findIndex(c => Number(c.user_id) === numId && c.normalized_name === normalizedName);
  if (idx >= 0) {
    todoCategories.splice(idx, 1);
    await _saveAwait('todo-categories.json', todoCategories);
  }
}

// ── Recipes ─────────────────────────────────────────────

function listRecipes(userId) {
  const numId = Number(userId);
  return recipes
    .filter(r => Number(r.user_id) === numId)
    .sort((a, b) => new Date(b.updated_at) - new Date(a.updated_at));
}

function searchRecipes(userId, query) {
  const numId = Number(userId);
  const q = query.toLowerCase();
  return recipes
    .filter(r => Number(r.user_id) === numId && ((r.name || '').toLowerCase().includes(q) || (r.notes || '').toLowerCase().includes(q)))
    .sort((a, b) => new Date(b.updated_at) - new Date(a.updated_at));
}

function getRecipe(id, userId) {
  const numId = Number(id);
  const numUserId = Number(userId);
  return recipes.find(r => Number(r.id) === numId && Number(r.user_id) === numUserId) || null;
}

async function createRecipe(fields) {
  const now = new Date().toISOString();
  const record = {
    id: nextId.recipes++,
    name: fields.name,
    notes: fields.notes || '',
    pdf_filename: fields.pdf_filename || null,
    pdf_original_name: fields.pdf_original_name || null,
    ingredient_todo_category: null,
    ingredient_todos_count: null,
    ingredient_todos_created_at: null,
    user_id: Number(fields.user_id),
    created_at: now,
    updated_at: now
  };
  recipes.push(record);
  await _saveAwait('recipes.json', recipes);
  return record;
}

async function updateRecipe(id, userId, fields) {
  const numId = Number(id);
  const numUserId = Number(userId);
  const recipe = recipes.find(r => Number(r.id) === numId && Number(r.user_id) === numUserId);
  if (!recipe) return null;

  if (fields.name !== undefined) recipe.name = fields.name;
  if (fields.notes !== undefined) recipe.notes = fields.notes;
  if (fields.pdf_filename !== undefined) recipe.pdf_filename = fields.pdf_filename;
  if (fields.pdf_original_name !== undefined) recipe.pdf_original_name = fields.pdf_original_name;
  if (fields.ingredient_todo_category !== undefined) recipe.ingredient_todo_category = fields.ingredient_todo_category;
  if (fields.ingredient_todos_count !== undefined) recipe.ingredient_todos_count = fields.ingredient_todos_count;
  if (fields.ingredient_todos_created_at !== undefined) recipe.ingredient_todos_created_at = fields.ingredient_todos_created_at;
  recipe.updated_at = new Date().toISOString();

  await _saveAwait('recipes.json', recipes);
  return recipe;
}

async function deleteRecipe(id, userId) {
  const numId = Number(id);
  const numUserId = Number(userId);
  const idx = recipes.findIndex(r => Number(r.id) === numId && Number(r.user_id) === numUserId);
  if (idx === -1) return { found: false };

  const recipe = recipes[idx];
  recipes.splice(idx, 1);
  // Also delete associated ingredients
  ingredients = ingredients.filter(i => Number(i.recipe_id) !== numId);

  await Promise.all([
    _saveAwait('recipes.json', recipes),
    _saveAwait('ingredients.json', ingredients)
  ]);
  return { found: true, pdf_filename: recipe.pdf_filename };
}

// ── Ingredients ─────────────────────────────────────────

function listIngredients(recipeId) {
  const numId = Number(recipeId);
  return ingredients
    .filter(i => Number(i.recipe_id) === numId)
    .sort((a, b) => Number(a.id) - Number(b.id));
}

async function deleteIngredients(recipeId) {
  const numId = Number(recipeId);
  const before = ingredients.length;
  ingredients = ingredients.filter(i => Number(i.recipe_id) !== numId);
  if (ingredients.length !== before) {
    await _saveAwait('ingredients.json', ingredients);
  }
}

async function addIngredient(recipeId, name, quantity) {
  const now = new Date().toISOString();
  const record = {
    id: nextId.ingredients++,
    recipe_id: Number(recipeId),
    name,
    quantity: quantity || null,
    created_at: now
  };
  ingredients.push(record);
  // Don't save each individually — caller should call saveIngredients() after batch
  return record;
}

async function saveIngredients() {
  await _saveAwait('ingredients.json', ingredients);
}

module.exports = {
  init,
  // Users
  getUserByUsername, getUserById, createUser, updateUserPassword,
  updateUserAnthropicKey, deleteUserAnthropicKey, getUserAnthropicKey,
  // API Keys
  listApiKeys, createApiKey, deleteApiKey, findApiKeyByHash,
  // Notes
  listNotes, listNotesByUpdated, searchNotes, getNote, createNote, updateNote, deleteNote,
  // Todos
  listTodos, searchTodos, getTodo, createTodo, createTodoWithCompleted, updateTodo, deleteTodo,
  countTodos, deleteTodosByCategory, reassignTodosCategory,
  // Categories
  listCategories, listUsedCategories, createCategory, deleteCategory,
  // Recipes
  listRecipes, searchRecipes, getRecipe, createRecipe, updateRecipe, deleteRecipe,
  // Ingredients
  listIngredients, deleteIngredients, addIngredient, saveIngredients
};
