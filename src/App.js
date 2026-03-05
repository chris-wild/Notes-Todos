import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useAuth } from './context/AuthContext';
import AuthPage from './components/AuthPage';
import NotesTab from './components/notes/NotesTab';
import TodosTab from './components/todos/TodosTab';
import RecipesTab from './components/recipes/RecipesTab';
import AdminPanel from './components/AdminPanel';
import AboutModal from './components/AboutModal';
import ClaudeModal from './components/ClaudeModal';
import './App.css';

function App() {
  const {
    token,
    username,
    authFetch,
    handleLogout,
    API_URL,
    instance,
  } = useAuth();

  const isMobileDevice = (() => {
    try {
      const ua = navigator.userAgent || '';
      const coarse = typeof window !== 'undefined' && window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
      return coarse || /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(ua);
    } catch (e) {
      return false;
    }
  })();

  // Visual cue: set data-instance on root for CSS targeting
  useEffect(() => {
    document.documentElement.setAttribute('data-instance', instance);
  }, [instance]);

  // Theme: dark mode by default
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', 'dark');
  }, []);

  // ---- Reset feature state on logout ----
  useEffect(() => {
    if (!token) {
      setNotes([]);
      setTodos([]);
      setApiKeys([]);
      setRecipes([]);
      setCurrentNote({ id: null, title: '', content: '' });
      setComposerNote({ title: '', content: '' });
      setNewTodo('');
      setActiveTodoCategory('General');
      setShowAdminPanel(false);
      setRecipeForm({ id: null, name: '', notes: '' });
      setRecipePdfFile(null);
      setViewingRecipe(null);
      setRemovePdf(false);
      setRecipeError('');
      setFeatures({ ingredientAutomation: false });
      setAnthropicKeyInfo({ hasKey: false, maskedKey: null });
      setAnthropicKeyDraft('');
      setAnthropicKeyMessage({ type: '', text: '' });
      setActiveTab('notes');
    }
  }, [token]);

  // ---- Layout state ----
  const topbarRef = useRef(null);
  const sidebarRef = useRef(null);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [topbarHeight, setTopbarHeight] = useState(64);
  const [sidebarWidth, setSidebarWidth] = useState(0);
  const [searchQuery, setSearchQuery] = useState('');
  const [searchActive, setSearchActive] = useState(false);
  const [activeTab, setActiveTab] = useState('notes');
  const [showAdminPanel, setShowAdminPanel] = useState(false);
  const [showAbout, setShowAbout] = useState(false);
  const [showClaudeProfile, setShowClaudeProfile] = useState(false);

  const [viewMode, setViewMode] = useState(localStorage.getItem('viewMode') || 'grid');
  useEffect(() => {
    localStorage.setItem('viewMode', viewMode);
  }, [viewMode]);

  // Responsive view mode: grid on wide, list on narrow
  useEffect(() => {
    const mq = window.matchMedia('(min-width: 900px)');
    const apply = () => {
      const desired = mq.matches ? 'grid' : 'list';
      setViewMode((prev) => (prev === desired ? prev : desired));
    };
    apply();
    if (typeof mq.addEventListener === 'function') {
      mq.addEventListener('change', apply);
      return () => mq.removeEventListener('change', apply);
    }
    mq.addListener(apply);
    return () => mq.removeListener(apply);
  }, []);

  useEffect(() => {
    if (!sidebarRef.current) return;
    const el = sidebarRef.current;
    const measure = () => {
      const w = Math.ceil(el.getBoundingClientRect().width);
      if (w && w !== sidebarWidth) setSidebarWidth(w);
    };
    measure();
    const ro = new ResizeObserver(() => measure());
    ro.observe(el);
    const raf = requestAnimationFrame(measure);
    return () => { cancelAnimationFrame(raf); ro.disconnect(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sidebarOpen]);

  useEffect(() => {
    if (!topbarRef.current) return;
    const el = topbarRef.current;
    const measure = () => {
      const h = Math.ceil(el.getBoundingClientRect().height);
      if (h && h !== topbarHeight) setTopbarHeight(h);
    };
    measure();
    const ro = new ResizeObserver(() => measure());
    ro.observe(el);
    const raf = requestAnimationFrame(measure);
    return () => { cancelAnimationFrame(raf); ro.disconnect(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sidebarOpen, searchQuery]);

  // ---- Notes state ----
  const [notes, setNotes] = useState([]);
  const [currentNote, setCurrentNote] = useState({ id: null, title: '', content: '' });
  const [noteSort, setNoteSort] = useState(localStorage.getItem('noteSort') || 'dateDesc');
  const [composerOpen, setComposerOpen] = useState(false);
  const [composerNote, setComposerNote] = useState({ title: '', content: '' });
  const [noteModalOpen, setNoteModalOpen] = useState(false);
  const [noteModalEditing, setNoteModalEditing] = useState(false);

  useEffect(() => {
    localStorage.setItem('noteSort', noteSort);
  }, [noteSort]);

  // ---- Todos state ----
  const [todos, setTodos] = useState([]);
  const [newTodo, setNewTodo] = useState('');
  const [todoCategories, setTodoCategories] = useState(['General', 'Shopping List']);
  const [activeTodoCategory, setActiveTodoCategory] = useState('General');
  const [todoCategoryDraft, setTodoCategoryDraft] = useState('');
  const [todoCategoryUiError, setTodoCategoryUiError] = useState('');
  const [todoCategoryAdding, setTodoCategoryAdding] = useState(false);
  const [confirmDeleteCategory, setConfirmDeleteCategory] = useState(null);

  // ---- Recipes state ----
  const [recipes, setRecipes] = useState([]);
  const [recipeForm, setRecipeForm] = useState({ id: null, name: '', notes: '' });
  const [recipePdfFile, setRecipePdfFile] = useState(null);
  const [viewingRecipe, setViewingRecipe] = useState(null);
  const [removePdf, setRemovePdf] = useState(false);
  const [recipeError, setRecipeError] = useState('');
  const [recipeWorking, setRecipeWorking] = useState(false);
  const [recipeWorkingText, setRecipeWorkingText] = useState('');
  const [features, setFeatures] = useState({ ingredientAutomation: false });

  // ---- Account state ----
  const [passwordReset, setPasswordReset] = useState({ current: '', new: '', confirm: '' });
  const [resetMessage, setResetMessage] = useState({ type: '', text: '' });
  const [apiKeys, setApiKeys] = useState([]);
  const [newKeyName, setNewKeyName] = useState('');
  const [newlyCreatedKey, setNewlyCreatedKey] = useState('');
  const [copiedKeyId, setCopiedKeyId] = useState(null);
  const [anthropicKeyInfo, setAnthropicKeyInfo] = useState({ hasKey: false, maskedKey: null });
  const [anthropicKeyDraft, setAnthropicKeyDraft] = useState('');
  const [anthropicKeyMessage, setAnthropicKeyMessage] = useState({ type: '', text: '' });
  const [anthropicKeySaving, setAnthropicKeySaving] = useState(false);

  // ---- Data fetchers ----
  const fetchNotes = useCallback(async () => {
    const res = await authFetch(`${API_URL}/notes`);
    if (res) setNotes(await res.json());
  }, [authFetch, API_URL]);

  const fetchTodos = useCallback(async () => {
    const res = await authFetch(`${API_URL}/todos`);
    if (res) setTodos(await res.json());
  }, [authFetch, API_URL]);

  const fetchTodoCategories = useCallback(async () => {
    const res = await authFetch(`${API_URL}/todo-categories`);
    if (res) {
      const data = await res.json();
      if (Array.isArray(data) && data.length) {
        setTodoCategories(data);
        if (!data.includes(activeTodoCategory)) setActiveTodoCategory('General');
      }
    }
  }, [authFetch, activeTodoCategory, API_URL]);

  const fetchApiKeys = useCallback(async () => {
    const res = await authFetch(`${API_URL}/keys`);
    if (res) setApiKeys(await res.json());
  }, [authFetch, API_URL]);

  const fetchAnthropicKey = useCallback(async () => {
    const res = await authFetch(`${API_URL}/anthropic-key`);
    if (res) {
      const data = await res.json().catch(() => ({}));
      setAnthropicKeyInfo({ hasKey: !!data.hasKey, maskedKey: data.maskedKey || null });
    }
  }, [authFetch, API_URL]);

  const fetchRecipes = useCallback(async () => {
    const res = await authFetch(`${API_URL}/recipes`);
    if (res) setRecipes(await res.json());
  }, [authFetch, API_URL]);

  const fetchFeatures = useCallback(async () => {
    const res = await authFetch(`${API_URL}/features`);
    if (res) {
      const data = await res.json().catch(() => ({}));
      setFeatures({ ingredientAutomation: !!(data && data.ingredientAutomation) });
    } else {
      setFeatures({ ingredientAutomation: false });
    }
  }, [authFetch, API_URL]);

  // Load all data on login
  useEffect(() => {
    if (token) {
      fetchNotes();
      fetchTodos();
      fetchTodoCategories();
      fetchRecipes();
      fetchFeatures();
    }
  }, [token, fetchNotes, fetchTodos, fetchTodoCategories, fetchRecipes, fetchFeatures]);

  // Load API keys and Anthropic key info when admin panel opens
  useEffect(() => {
    if (token && showAdminPanel) {
      fetchApiKeys();
      fetchAnthropicKey();
    }
  }, [token, showAdminPanel, fetchApiKeys, fetchAnthropicKey]);

  // ---- Notes handlers ----
  const createNote = async (title, content) => {
    const t = (title || '').trim();
    const c = (content || '').trim();
    if (!t && !c) return;
    await authFetch(`${API_URL}/notes`, {
      method: 'POST',
      body: JSON.stringify({ title: t || '(No title)', content: c })
    });
    fetchNotes();
  };

  const saveNote = async () => {
    const title = (currentNote.title || '').trim();
    const content = (currentNote.content || '').trim();
    if (!title && !content) return;
    if (currentNote.id) {
      await authFetch(`${API_URL}/notes/${currentNote.id}`, {
        method: 'PUT',
        body: JSON.stringify({ id: currentNote.id, title, content, pinned: currentNote.pinned, sort_order: currentNote.sort_order })
      });
      fetchNotes();
    } else {
      await createNote(title, content);
    }
    setCurrentNote({ id: null, title: '', content: '' });
  };

  const updateNote = async (id, patch) => {
    await authFetch(`${API_URL}/notes/${id}`, { method: 'PUT', body: JSON.stringify(patch) });
    fetchNotes();
  };

  const togglePin = async (note) => {
    await updateNote(note.id, {
      title: note.title || '',
      content: note.content || '',
      pinned: !note.pinned,
      sort_order: Date.now()
    });
  };

  const deleteNote = async (id) => {
    await authFetch(`${API_URL}/notes/${id}`, { method: 'DELETE' });
    fetchNotes();
  };

  // ---- Todos handlers ----
  const addTodo = async (e) => {
    e.preventDefault();
    if (!newTodo.trim()) return;
    await authFetch(`${API_URL}/todos`, {
      method: 'POST',
      body: JSON.stringify({ text: newTodo, category: activeTodoCategory })
    });
    setNewTodo('');
    fetchTodos();
  };

  const toggleTodo = async (id, completed) => {
    await authFetch(`${API_URL}/todos/${id}`, { method: 'PUT', body: JSON.stringify({ completed: !completed }) });
    fetchTodos();
  };

  const deleteTodo = async (id) => {
    await authFetch(`${API_URL}/todos/${id}`, { method: 'DELETE' });
    fetchTodos();
  };

  const addTodoCategory = async () => {
    const trimmed = (todoCategoryDraft || '').trim();
    if (!trimmed) { setTodoCategoryUiError('Enter category name'); return; }
    setTodoCategoryUiError('');
    const res = await authFetch(`${API_URL}/todo-categories`, {
      method: 'POST',
      body: JSON.stringify({ name: trimmed })
    });
    if (res && res.ok) {
      const data = await res.json().catch(() => ({}));
      await fetchTodoCategories();
      setActiveTodoCategory(data.name || trimmed);
      setTodoCategoryDraft('');
      setTodoCategoryAdding(false);
      return;
    }
    if (!res) { setTodoCategoryUiError('Failed to add category'); return; }
    const data = await res.json().catch(() => ({}));
    setTodoCategoryUiError(data.error || 'Failed to add category');
  };

  const requestRemoveTodoCategory = (name) => {
    if (!name || name === 'General' || name === 'Shopping List') return;
    setConfirmDeleteCategory(name);
  };

  const removeTodoCategory = async (name) => {
    if (!name || name === 'General' || name === 'Shopping List') return;
    setTodoCategoryUiError('');
    const res = await authFetch(`${API_URL}/todo-categories`, {
      method: 'DELETE',
      body: JSON.stringify({ name })
    });
    if (res && res.ok) {
      setTodos((prev) => prev.map((t) => ((t.category || 'General') === name ? { ...t, category: 'General' } : t)));
      await fetchTodoCategories();
      setActiveTodoCategory('General');
      return;
    }
    if (!res) { setTodoCategoryUiError('Failed to delete category'); return; }
    const data = await res.json().catch(() => ({}));
    setTodoCategoryUiError(data.error || 'Failed to delete category');
  };

  // ---- Recipes handlers ----
  const saveRecipe = async () => {
    if (!recipeForm.name) { setRecipeError('Recipe name is required'); return; }
    setRecipeError('');
    const formData = new FormData();
    formData.append('name', recipeForm.name);
    formData.append('notes', recipeForm.notes);
    if (recipePdfFile) formData.append('pdf', recipePdfFile);
    if (removePdf) formData.append('remove_pdf', 'true');
    const url = recipeForm.id ? `${API_URL}/recipes/${recipeForm.id}` : `${API_URL}/recipes`;
    const method = recipeForm.id ? 'PUT' : 'POST';
    try {
      const res = await fetch(url, { method, headers: { 'Authorization': `Bearer ${token}` }, body: formData });
      if (res.status === 401) { handleLogout(); return; }
      if (res.ok) {
        setRecipeForm({ id: null, name: '', notes: '' });
        setRecipePdfFile(null);
        setRemovePdf(false);
        setRecipeError('');
        setViewingRecipe(null);
        const fileInput = document.getElementById('recipe-pdf-input');
        if (fileInput) fileInput.value = '';
        fetchRecipes();
      } else {
        const data = await res.json();
        setRecipeError(data.error || 'Failed to save recipe');
      }
    } catch (err) {
      setRecipeError('Error connecting to server');
    }
  };

  const deleteRecipe = async (id) => {
    await authFetch(`${API_URL}/recipes/${id}`, { method: 'DELETE' });
    if (viewingRecipe && viewingRecipe.id === id) setViewingRecipe(null);
    if (recipeForm.id === id) {
      setRecipeForm({ id: null, name: '', notes: '' });
      setRecipePdfFile(null);
      setRemovePdf(false);
    }
    fetchRecipes();
  };

  const editRecipe = (recipe) => {
    setRecipeForm({ id: recipe.id, name: recipe.name, notes: recipe.notes || '' });
    setRecipePdfFile(null);
    setRemovePdf(false);
    setViewingRecipe(null);
    const fileInput = document.getElementById('recipe-pdf-input');
    if (fileInput) fileInput.value = '';
  };

  const viewRecipe = (recipe) => {
    setViewingRecipe(recipe);
    setRecipeForm({ id: null, name: '', notes: '' });
    setRecipePdfFile(null);
    setRemovePdf(false);
  };

  const cancelRecipeEdit = () => {
    setRecipeForm({ id: null, name: '', notes: '' });
    setRecipePdfFile(null);
    setRemovePdf(false);
    const fileInput = document.getElementById('recipe-pdf-input');
    if (fileInput) fileInput.value = '';
  };

  // Cross-tab: create ingredient todos and switch to todos tab
  const createIngredientTodos = async (recipeId) => {
    setViewingRecipe((prev) => prev ? { ...prev, _creatingIngredients: true } : prev);
    setRecipeError('');
    setRecipeWorking(true);
    setRecipeWorkingText('Creating ingredient list…');

    const res = await authFetch(`${API_URL}/recipes/${recipeId}/create-ingredient-todos`, { method: 'POST' });

    if (!res) {
      setRecipeWorking(false);
      setRecipeWorkingText('');
      setRecipeError('Failed to create ingredient list');
      await fetchFeatures();
      setViewingRecipe((prev) => prev ? { ...prev, _creatingIngredients: false } : prev);
      return;
    }

    const data = await res.json().catch(() => ({}));

    if (!res.ok) {
      setRecipeWorking(false);
      setRecipeWorkingText('');
      setRecipeError(data.error || 'Failed to create ingredient list');
      await fetchFeatures();
      setViewingRecipe((prev) => prev ? { ...prev, _creatingIngredients: false } : prev);
      return;
    }

    await fetchRecipes();
    await fetchTodoCategories();
    await fetchTodos();
    await fetchFeatures();

    const cat = data.category;
    if (cat) {
      setActiveTab('todos');
      setActiveTodoCategory(cat);
    }

    setRecipeWorking(false);
    setRecipeWorkingText('');
    setViewingRecipe((prev) => prev ? { ...prev, _creatingIngredients: false } : prev);
  };

  // ---- Account handlers ----
  const handlePasswordReset = async (e) => {
    e.preventDefault();
    setResetMessage({ type: '', text: '' });
    if (passwordReset.new !== passwordReset.confirm) {
      setResetMessage({ type: 'error', text: 'New passwords do not match' });
      return;
    }
    if (passwordReset.new.length < 6) {
      setResetMessage({ type: 'error', text: 'New password must be at least 6 characters' });
      return;
    }
    const res = await authFetch(`${API_URL}/reset-password`, {
      method: 'POST',
      body: JSON.stringify({ currentPassword: passwordReset.current, newPassword: passwordReset.new })
    });
    if (res && res.ok) {
      setResetMessage({ type: 'success', text: 'Password updated successfully!' });
      setPasswordReset({ current: '', new: '', confirm: '' });
    } else if (res) {
      const data = await res.json();
      setResetMessage({ type: 'error', text: data.error || 'Failed to update password' });
    } else {
      setResetMessage({ type: 'error', text: 'Error connecting to server' });
    }
  };

  const handleCreateKey = async (e) => {
    e.preventDefault();
    const res = await authFetch(`${API_URL}/keys`, {
      method: 'POST',
      body: JSON.stringify({ name: newKeyName || 'Unnamed Key' })
    });
    if (res && res.ok) {
      const data = await res.json();
      setNewlyCreatedKey(data.key);
      setNewKeyName('');
      fetchApiKeys();
    }
  };

  const handleDeleteKey = async (id) => {
    const res = await authFetch(`${API_URL}/keys/${id}`, { method: 'DELETE' });
    if (res && res.ok) { fetchApiKeys(); setNewlyCreatedKey(''); }
  };

  const copyToClipboard = async (text, keyId) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedKeyId(keyId);
      setTimeout(() => setCopiedKeyId(null), 2000);
    } catch (err) {
      // Fallback for older browsers
      const textarea = document.createElement('textarea');
      textarea.value = text;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      setCopiedKeyId(keyId);
      setTimeout(() => setCopiedKeyId(null), 2000);
    }
  };

  const handleSaveAnthropicKey = async (e) => {
    e.preventDefault();
    const key = (anthropicKeyDraft || '').trim();
    if (!key) { setAnthropicKeyMessage({ type: 'error', text: 'Paste your API key' }); return; }
    setAnthropicKeyMessage({ type: '', text: '' });
    setAnthropicKeySaving(true);
    const res = await authFetch(`${API_URL}/anthropic-key`, {
      method: 'PUT',
      body: JSON.stringify({ key })
    });
    setAnthropicKeySaving(false);
    if (res && res.ok) {
      const data = await res.json().catch(() => ({}));
      setAnthropicKeyInfo({ hasKey: true, maskedKey: data.maskedKey || '****' });
      setAnthropicKeyDraft('');
      setAnthropicKeyMessage({ type: 'success', text: 'Key saved and verified!' });
      fetchFeatures();
    } else if (res) {
      const data = await res.json().catch(() => ({}));
      setAnthropicKeyMessage({ type: 'error', text: data.error || 'Failed to save key' });
    } else {
      setAnthropicKeyMessage({ type: 'error', text: 'Error connecting to server' });
    }
  };

  const handleDeleteAnthropicKey = async () => {
    const res = await authFetch(`${API_URL}/anthropic-key`, { method: 'DELETE' });
    if (res && res.ok) {
      setAnthropicKeyInfo({ hasKey: false, maskedKey: null });
      setAnthropicKeyDraft('');
      setAnthropicKeyMessage({ type: 'success', text: 'Key removed' });
      fetchFeatures();
    }
  };

  // ---- Unauthenticated ----
  if (!token) return <AuthPage />;

  // ---- Derived values ----
  const rawTitleName = (username || '').trim() || 'My';
  const titleName = rawTitleName
    ? rawTitleName[0].toUpperCase() + rawTitleName.slice(1)
    : rawTitleName;
  const possessive = titleName.endsWith('s') || titleName.endsWith('S')
    ? `${titleName}'`
    : `${titleName}'s`;

  const q = searchQuery.trim().toLowerCase();
  const doSearch = searchActive && q.length > 0;

  const filteredNotes = doSearch
    ? notes.filter((n) => `${n.title || ''}\n${n.content || ''}`.toLowerCase().includes(q))
    : notes;
  const filteredTodos = doSearch
    ? todos.filter((t) => `${t.text || ''} ${t.category || ''}`.toLowerCase().includes(q))
    : todos;
  const filteredRecipes = doSearch
    ? recipes.filter((r) => `${r.name || ''}\n${r.notes || ''}`.toLowerCase().includes(q))
    : recipes;

  const currentResultsCount =
    activeTab === 'notes' ? filteredNotes.length
    : activeTab === 'todos' ? filteredTodos.length
    : filteredRecipes.length;

  // ---- Render ----
  return (
    <div
      className={sidebarOpen ? 'keep-shell sidebar-open' : 'keep-shell'}
      style={{
        ...(sidebarWidth ? { '--sidebar-width': `${sidebarWidth}px` } : null),
        ...(topbarHeight ? { '--topbar-height': `${topbarHeight}px` } : null)
      }}
    >
      <header ref={topbarRef} className="keep-topbar">
        <div className="keep-topbar-left">
          <button
            className="keep-icon-btn"
            onClick={() => setSidebarOpen((v) => !v)}
            aria-label="Toggle menu"
            title="Menu"
          >
            ☰
          </button>
          {instance === 'dev' && <span className="instance-badge dev">DEV</span>}
          <div className="keep-title">{possessive} Notes &amp; Todos</div>
          {!isMobileDevice && (
            <button
              className="keep-icon-btn"
              title={viewMode === 'grid' ? 'List view' : 'Grid view'}
              onClick={() => setViewMode((m) => (m === 'grid' ? 'list' : 'grid'))}
            >
              {viewMode === 'grid' ? 'List' : 'Grid'}
            </button>
          )}
        </div>

        <div className="keep-search">
          <input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                if (searchQuery.trim()) setSearchActive(true);
              }
            }}
            placeholder="Search"
            aria-label="Search"
          />
          <button
            className="keep-icon-btn"
            onClick={() => { if (searchQuery.trim()) setSearchActive(true); }}
            title="Search"
          >
            Search
          </button>
        </div>

        <div className="keep-topbar-right">
          <button onClick={() => setShowAdminPanel(!showAdminPanel)} className="keep-icon-btn" title="Admin">
            Admin
          </button>
          <button onClick={() => setShowAbout(true)} className="keep-icon-btn" title="About">
            About
          </button>
          <button onClick={handleLogout} className="keep-icon-btn" title="Logout">
            Logout
          </button>
        </div>
      </header>

      <aside ref={sidebarRef} className={sidebarOpen ? 'keep-sidebar open' : 'keep-sidebar'}>
        <button
          className={activeTab === 'notes' ? 'keep-nav-item active' : 'keep-nav-item'}
          onClick={() => { setActiveTab('notes'); setSearchActive(false); setSearchQuery(''); }}
        >
          Notes
        </button>
        <button
          className={activeTab === 'todos' ? 'keep-nav-item active' : 'keep-nav-item'}
          onClick={() => { setActiveTab('todos'); setSearchActive(false); setSearchQuery(''); }}
        >
          Todos
        </button>
        <button
          className={activeTab === 'recipes' ? 'keep-nav-item active' : 'keep-nav-item'}
          onClick={() => { setActiveTab('recipes'); setSearchActive(false); setSearchQuery(''); }}
        >
          Recipes
        </button>
      </aside>

      <main className="keep-main">
        {doSearch && (
          <div className="keep-search-results-bar">
            <div className="keep-search-results-text">
              Showing {currentResultsCount} result{currentResultsCount === 1 ? '' : 's'} in {activeTab}
            </div>
            <button
              className="keep-link-btn"
              onClick={() => { setSearchActive(false); setSearchQuery(''); }}
            >
              View all
            </button>
          </div>
        )}

        {activeTab === 'notes' && (
          <NotesTab
            filteredNotes={filteredNotes}
            viewMode={viewMode}
            noteSort={noteSort}
            setNoteSort={setNoteSort}
            composerOpen={composerOpen}
            setComposerOpen={setComposerOpen}
            composerNote={composerNote}
            setComposerNote={setComposerNote}
            currentNote={currentNote}
            setCurrentNote={setCurrentNote}
            noteModalOpen={noteModalOpen}
            setNoteModalOpen={setNoteModalOpen}
            noteModalEditing={noteModalEditing}
            setNoteModalEditing={setNoteModalEditing}
            onCreateNote={createNote}
            onSaveNote={saveNote}
            onTogglePin={togglePin}
            onDeleteNote={deleteNote}
          />
        )}

        {activeTab === 'todos' && (
          <TodosTab
            filteredTodos={filteredTodos}
            todoCategories={todoCategories}
            activeTodoCategory={activeTodoCategory}
            setActiveTodoCategory={setActiveTodoCategory}
            newTodo={newTodo}
            setNewTodo={setNewTodo}
            todoCategoryDraft={todoCategoryDraft}
            setTodoCategoryDraft={setTodoCategoryDraft}
            todoCategoryUiError={todoCategoryUiError}
            setTodoCategoryUiError={setTodoCategoryUiError}
            todoCategoryAdding={todoCategoryAdding}
            setTodoCategoryAdding={setTodoCategoryAdding}
            confirmDeleteCategory={confirmDeleteCategory}
            setConfirmDeleteCategory={setConfirmDeleteCategory}
            onAddTodo={addTodo}
            onToggleTodo={toggleTodo}
            onDeleteTodo={deleteTodo}
            onAddTodoCategory={addTodoCategory}
            onRequestRemoveCategory={requestRemoveTodoCategory}
            onRemoveCategory={removeTodoCategory}
          />
        )}

        {activeTab === 'recipes' && (
          <RecipesTab
            recipes={recipes}
            filteredRecipes={filteredRecipes}
            recipeForm={recipeForm}
            setRecipeForm={setRecipeForm}
            recipePdfFile={recipePdfFile}
            setRecipePdfFile={setRecipePdfFile}
            viewingRecipe={viewingRecipe}
            setViewingRecipe={setViewingRecipe}
            removePdf={removePdf}
            setRemovePdf={setRemovePdf}
            recipeError={recipeError}
            setRecipeError={setRecipeError}
            recipeWorking={recipeWorking}
            recipeWorkingText={recipeWorkingText}
            features={features}
            isMobileDevice={isMobileDevice}
            onSaveRecipe={saveRecipe}
            onDeleteRecipe={deleteRecipe}
            onEditRecipe={editRecipe}
            onViewRecipe={viewRecipe}
            onCancelEdit={cancelRecipeEdit}
            onCreateIngredientTodos={createIngredientTodos}
          />
        )}

        {showAbout && (
          <AboutModal
            onClose={() => setShowAbout(false)}
            onOpenClaude={() => { setShowAbout(false); setShowClaudeProfile(true); }}
          />
        )}
        {showClaudeProfile && (
          <ClaudeModal onClose={() => setShowClaudeProfile(false)} />
        )}
        {showAdminPanel && (
          <AdminPanel
            onClose={() => setShowAdminPanel(false)}
            passwordReset={passwordReset}
            setPasswordReset={setPasswordReset}
            resetMessage={resetMessage}
            onPasswordReset={handlePasswordReset}
            apiKeys={apiKeys}
            newKeyName={newKeyName}
            setNewKeyName={setNewKeyName}
            newlyCreatedKey={newlyCreatedKey}
            copiedKeyId={copiedKeyId}
            onCreateKey={handleCreateKey}
            onDeleteKey={handleDeleteKey}
            onCopyToClipboard={copyToClipboard}
            anthropicKeyInfo={anthropicKeyInfo}
            anthropicKeyDraft={anthropicKeyDraft}
            setAnthropicKeyDraft={setAnthropicKeyDraft}
            anthropicKeyMessage={anthropicKeyMessage}
            anthropicKeySaving={anthropicKeySaving}
            onSaveAnthropicKey={handleSaveAnthropicKey}
            onDeleteAnthropicKey={handleDeleteAnthropicKey}
          />
        )}
      </main>
    </div>
  );
}

export default App;
