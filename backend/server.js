const express = require('express');
const cors = require('cors');
const bcrypt = require('bcryptjs');
const argon2 = require('argon2');
const crypto = require('crypto');
const jwt = require('jsonwebtoken');
const path = require('path');
const fs = require('fs');
const multer = require('multer');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');

const db = require('./db');
const { migrate } = require('./migrate');
const s3 = require('./s3');
const { encrypt, decrypt, hmacHash } = require('./crypto-utils');
const { convertImageToPdf, isImageMime, IMAGE_MIME_TYPES } = require('./image-to-pdf');

const app = express();
const PORT = parseInt(process.env.PORT || '3001', 10);
const NODE_ENV = process.env.NODE_ENV || 'development';
const RAW_ANTHROPIC_KEY = (process.env.ANTHROPIC_API_KEY || '').trim();
let ingredientAutomationEnabled = false;

async function verifyAnthropicKey(context = 'startup') {
  if (!RAW_ANTHROPIC_KEY) {
    ingredientAutomationEnabled = false;
    if (context === 'startup') {
      console.log('Ingredient automation disabled: no ANTHROPIC_API_KEY configured.');
    }
    return;
  }
  try {
    const resp = await fetch('https://api.anthropic.com/v1/models', {
      method: 'GET',
      headers: {
        'x-api-key': RAW_ANTHROPIC_KEY,
        'anthropic-version': '2023-06-01'
      }
    });
    if (!resp.ok) {
      throw new Error(`status ${resp.status}`);
    }
    ingredientAutomationEnabled = true;
    console.log(`[recipes] Ingredient automation enabled (${context}).`);
  } catch (err) {
    ingredientAutomationEnabled = false;
    console.warn(`[recipes] Ingredient automation disabled (${context}): ${err.message}`);
  }
}

verifyAnthropicKey('startup');

function isIngredientAutomationEnabled() {
  return ingredientAutomationEnabled;
}

// JWT secret - use env var for persistence across restarts, or generate random
const JWT_SECRET = process.env.JWT_SECRET || crypto.randomBytes(32).toString('hex');

// Uploads directory for recipe PDFs
const UPLOADS_DIR = path.join(__dirname, 'uploads', 'recipes');
if (!fs.existsSync(UPLOADS_DIR)) {
  fs.mkdirSync(UPLOADS_DIR, { recursive: true });
}

function resolveRecipeUploadPath(filename) {
  if (!filename || typeof filename !== 'string') {
    throw new Error('Invalid recipe filename');
  }
  const sanitized = filename.replace(/\0/g, '');
  const targetPath = path.resolve(UPLOADS_DIR, sanitized);
  const relative = path.relative(UPLOADS_DIR, targetPath);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    throw new Error('Recipe filename resolves outside uploads directory');
  }
  return targetPath;
}

function deleteLocalRecipePdf(filename) {
  if (!filename) return;
  try {
    const filePath = resolveRecipeUploadPath(filename);
    fs.unlink(filePath, () => {});
  } catch (err) {
    console.warn(`[recipes] Skipped deleting recipe PDF: ${err.message}`);
  }
}

// Multer config for recipe file uploads (PDF or image).
// Always use memory storage so we can convert images to PDF before writing to disk/S3.
const ALLOWED_MIME_TYPES = new Set(['application/pdf', ...IMAGE_MIME_TYPES]);

const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 20 * 1024 * 1024 }, // 20MB max
  fileFilter: (req, file, cb) => {
    if (ALLOWED_MIME_TYPES.has(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new Error('Only PDF and image files (JPEG, PNG, WEBP, HEIC) are allowed'));
    }
  }
});

// Middleware: convert uploaded images to PDF so downstream storage/viewing is always PDF.
const convertImageIfNeeded = async (req, res, next) => {
  if (!req.file || !isImageMime(req.file.mimetype)) return next();
  try {
    const pdfBuffer = await convertImageToPdf(req.file.buffer);
    const stem = path.basename(req.file.originalname, path.extname(req.file.originalname));
    req.file.buffer = pdfBuffer;
    req.file.mimetype = 'application/pdf';
    req.file.originalname = stem + '.pdf';
    req.file.size = pdfBuffer.length;
    next();
  } catch (err) {
    console.error('[image-to-pdf] conversion failed:', err.message);
    res.status(400).json({ error: 'Failed to convert image to PDF' });
  }
};

// Middleware
// In production (ECS/ALB) we typically terminate TLS at the load balancer.
// You can restrict CORS with CORS_ORIGIN="https://yourdomain".
const corsOrigin = process.env.CORS_ORIGIN;
app.use(
  cors(
    corsOrigin
      ? { origin: corsOrigin.split(',').map((s) => s.trim()), credentials: true }
      : undefined
  )
);
app.disable('x-powered-by');

// Security headers
app.use(
  helmet({
    // We set CSP explicitly below so we can tune it.
    contentSecurityPolicy: false
  })
);

app.use(
  helmet.contentSecurityPolicy({
    useDefaults: true,
    directives: {
      // Good default for a single-origin CRA SPA.
      "default-src": ["'self'"],
      "script-src": ["'self'"],
      // CRA may inject inline styles; keep for now.
      "style-src": ["'self'", "'unsafe-inline'"],
      "img-src": ["'self'", 'data:'],
      "font-src": ["'self'", 'data:'],
      "connect-src": ["'self'"],
      "frame-ancestors": ["'none'"]
    }
  })
);

// HSTS (only safe to enable in prod)
if (NODE_ENV === 'production') {
  app.use(
    helmet.hsts({
      maxAge: 15552000, // 180 days
      includeSubDomains: true,
      preload: false
    })
  );
}

// Helmet removed Permissions-Policy helper in v8; set header explicitly.
app.use((req, res, next) => {
  res.setHeader(
    'Permissions-Policy',
    'camera=(), microphone=(), geolocation=(), payment=(), usb=(), accelerometer=(), gyroscope=(), magnetometer=()'
  );
  next();
});

app.use(helmet.referrerPolicy({ policy: 'strict-origin-when-cross-origin' }));
// Clickjacking protection: default deny framing, but allow same-origin framing for the recipe PDF iframe.
app.use((req, res, next) => {
  const isRecipePdf = /^\/api\/recipes\/\d+\/pdf$/.test(req.path);
  if (isRecipePdf) {
    // Allow embedding within our own app (same-origin only).
    res.setHeader('X-Frame-Options', 'SAMEORIGIN');

    // We rely on X-Frame-Options for these endpoints and remove CSP for the response.
    // (CSP frame-ancestors would otherwise block same-origin iframing.)
    res.removeHeader('Content-Security-Policy');
  } else {
    res.setHeader('X-Frame-Options', 'DENY');
  }
  next();
});
app.use(helmet.noSniff());

app.use(express.json());

// Password hashing
// - New hashes: Argon2id
// - Legacy hashes: bcrypt ($2a$/$2b$/$2y$) verified with bcryptjs
const ARGON2_OPTS = {
  type: argon2.argon2id,
  // Reasonable interactive parameters for a small app.
  // (memoryCost is KiB) ~64 MiB, timeCost 3, parallelism 1
  memoryCost: 64 * 1024,
  timeCost: 3,
  parallelism: 1
};

function isBcryptHash(h) {
  return typeof h === 'string' && /^\$2[aby]\$/.test(h);
}

function isArgon2Hash(h) {
  return typeof h === 'string' && /^\$argon2id\$/.test(h);
}

async function verifyPassword(password, storedHash) {
  if (isArgon2Hash(storedHash)) {
    return argon2.verify(storedHash, password);
  }
  if (isBcryptHash(storedHash)) {
    return bcrypt.compare(password, storedHash);
  }
  return false;
}

async function hashPassword(password) {
  return argon2.hash(password, ARGON2_OPTS);
}

// Basic health check (useful for ALB target group health checks)
app.get('/healthz', (req, res) => {
  res.setHeader('Cache-Control', 'no-store');
  res.json({ ok: true });
});

// Cache-control hardening: never cache API responses.
app.use('/api', (req, res, next) => {
  res.setHeader('Cache-Control', 'no-store');
  next();
});

// NOTE: Do NOT expose uploaded recipe PDFs via an unauthenticated static route.
// Always serve PDFs via the authenticated `/api/recipes/:id/pdf` endpoint.
// (This prevents anyone from fetching a PDF by guessing a filename/path.)

// In production, serve the built React app from ../build (single-container deploy)
// IMPORTANT: don't let the SPA fallback swallow API routes.
if (NODE_ENV === 'production') {
  const buildDir = path.join(__dirname, '..', 'build');
  if (fs.existsSync(buildDir)) {
    app.use(express.static(buildDir));

    // Don't cache the HTML app shell (it can contain user-specific bootstrap state)
    app.get('/', (req, res, next) => {
      res.setHeader('Cache-Control', 'no-store');
      return next();
    });

    // SPA fallback (exclude API + health + uploads)
    app.get('*', (req, res, next) => {
      if (
        req.path.startsWith('/api') ||
        req.path === '/healthz' ||
        req.path.startsWith('/uploads')
      ) {
        return next();
      }
      return res.sendFile(path.join(buildDir, 'index.html'));
    });
  }
}

// JWT authentication middleware for web UI endpoints
const authenticateToken = (req, res, next) => {
  const authHeader = req.headers['authorization'];
  const token = authHeader && authHeader.split(' ')[1];

  if (!token) {
    return res.status(401).json({ error: 'Authentication required' });
  }

  jwt.verify(token, JWT_SECRET, (err, payload) => {
    if (err) {
      return res.status(401).json({ error: 'Invalid or expired token' });
    }
    req.userId = payload.userId;
    req.username = payload.username;
    next();
  });
};

// API Key middleware - resolves to a user
const authenticateAPIKey = async (req, res, next) => {
  const apiKey = req.headers['x-api-key'];

  if (!apiKey) {
    return res.status(401).json({ error: 'API key required' });
  }

  try {
    const result = await db.query(
      'SELECT ak.*, u.username FROM api_keys ak JOIN users u ON ak.user_id = u.id WHERE ak.key_hash = $1 AND ak.active = TRUE',
      [hmacHash(apiKey)]
    );

    const row = result.rows[0];
    if (!row) {
      return res.status(401).json({ error: 'Invalid API key' });
    }

    req.userId = row.user_id;
    req.username = row.username;
    next();
  } catch (err) {
    return res.status(401).json({ error: 'Invalid API key' });
  }
};

function generateToken(user) {
  return jwt.sign({ userId: user.id, username: user.username }, JWT_SECRET, { expiresIn: '7d' });
}

// ============================================================
// Auth endpoints
// ============================================================

const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 20,
  standardHeaders: 'draft-7',
  legacyHeaders: false
});

const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  limit: 300,
  standardHeaders: 'draft-7',
  legacyHeaders: false
});

app.use('/api', apiLimiter);

app.post('/api/register', authLimiter, async (req, res) => {
  const { username, password } = req.body;

  if (!username || !password) {
    return res.status(400).json({ error: 'Username and password required' });
  }
  if (username.length < 3) {
    return res.status(400).json({ error: 'Username must be at least 3 characters' });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: 'Password must be at least 6 characters' });
  }

  const normalizedUsername = username.toLowerCase().trim();

  try {
    const existing = await db.query('SELECT id FROM users WHERE username = $1', [normalizedUsername]);
    if (existing.rows[0]) {
      return res.status(409).json({ error: 'Username already taken' });
    }

    const hash = await hashPassword(password);
    const inserted = await db.query(
      'INSERT INTO users (username, password_hash) VALUES ($1, $2) RETURNING id',
      [normalizedUsername, hash]
    );

    const id = inserted.rows[0].id;
    const token = generateToken({ id, username: normalizedUsername });
    res.status(201).json({ token, username: normalizedUsername });
  } catch (err) {
    res.status(500).json({ error: 'Failed to create user' });
  }
});

app.post('/api/login', authLimiter, async (req, res) => {
  const { username, password } = req.body;

  if (!username || !password) {
    return res.status(400).json({ error: 'Username and password required' });
  }

  const normalizedUsername = username.toLowerCase().trim();

  try {
    const result = await db.query('SELECT * FROM users WHERE username = $1', [normalizedUsername]);
    const row = result.rows[0];

    if (!row) {
      return res.status(401).json({ error: 'Invalid username or password' });
    }

    const match = await verifyPassword(password, row.password_hash);
    if (!match) {
      return res.status(401).json({ error: 'Invalid username or password' });
    }

    // Opportunistic upgrade: if this was a legacy bcrypt hash, upgrade to Argon2id on successful login.
    if (isBcryptHash(row.password_hash)) {
      try {
        const upgraded = await hashPassword(password);
        await db.query('UPDATE users SET password_hash = $1 WHERE id = $2', [upgraded, row.id]);
      } catch (e) {
        // Best-effort: do not fail login if rehash fails.
      }
    }

    const token = generateToken({ id: row.id, username: row.username });
    res.json({ token, username: row.username });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/reset-password', authLimiter, authenticateToken, async (req, res) => {
  const { currentPassword, newPassword } = req.body;

  if (!currentPassword || !newPassword) {
    return res.status(400).json({ error: 'Current and new passwords required' });
  }

  if (newPassword.length < 6) {
    return res.status(400).json({ error: 'New password must be at least 6 characters' });
  }

  try {
    const result = await db.query('SELECT password_hash FROM users WHERE id = $1', [req.userId]);
    const row = result.rows[0];
    if (!row) {
      return res.status(500).json({ error: err.message });
    }

    const match = await verifyPassword(currentPassword, row.password_hash);
    if (!match) {
      return res.status(401).json({ error: 'Current password is incorrect' });
    }

    const newHash = await hashPassword(newPassword);
    await db.query('UPDATE users SET password_hash = $1 WHERE id = $2', [newHash, req.userId]);
    res.json({ success: true, message: 'Password updated successfully' });
  } catch (err) {
    res.status(500).json({ error: 'Failed to update password' });
  }
});

// ============================================================
// API Key management (JWT protected)
// ============================================================

app.get('/api/keys', authenticateToken, async (req, res) => {
  try {
    const result = await db.query(
      'SELECT id, key, name, active, created_at FROM api_keys WHERE user_id = $1 ORDER BY created_at DESC',
      [req.userId]
    );
    const rows = result.rows.map((row) => ({ ...row, key: decrypt(row.key) }));
    res.json(rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/keys', authenticateToken, async (req, res) => {
  const { name } = req.body;
  const newKey = crypto.randomBytes(32).toString('hex');

  try {
    const result = await db.query(
      'INSERT INTO api_keys (key, key_hash, name, user_id) VALUES ($1, $2, $3, $4) RETURNING id',
      [encrypt(newKey), hmacHash(newKey), name || 'Unnamed Key', req.userId]
    );

    res.status(201).json({ id: result.rows[0].id, key: newKey, name: name || 'Unnamed Key' });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/keys/:id', authenticateToken, async (req, res) => {
  try {
    const result = await db.query('DELETE FROM api_keys WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'API key not found' });
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ============================================================
// Per-user Anthropic API key management (JWT protected)
// ============================================================

function maskAnthropicKey(key) {
  if (!key || key.length < 8) return '****';
  return key.slice(0, 7) + '...' + key.slice(-4);
}

app.get('/api/anthropic-key', authenticateToken, async (req, res) => {
  try {
    const result = await db.query('SELECT anthropic_api_key FROM users WHERE id = $1', [req.userId]);
    const raw = result.rows[0]?.anthropic_api_key;
    const key = raw ? decrypt(raw) : null;
    res.json({ hasKey: !!key, maskedKey: key ? maskAnthropicKey(key) : null });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/anthropic-key', authenticateToken, async (req, res) => {
  const { key } = req.body;
  if (!key || typeof key !== 'string' || !key.trim()) {
    return res.status(400).json({ error: 'API key is required' });
  }

  const trimmed = key.trim();

  // Validate the key against Anthropic API
  try {
    const resp = await fetch('https://api.anthropic.com/v1/models', {
      method: 'GET',
      headers: { 'x-api-key': trimmed, 'anthropic-version': '2023-06-01' }
    });
    if (!resp.ok) {
      return res.status(400).json({ error: 'Invalid Anthropic API key — verification failed' });
    }
  } catch (err) {
    return res.status(400).json({ error: 'Could not verify key — check your internet connection' });
  }

  try {
    await db.query('UPDATE users SET anthropic_api_key = $1 WHERE id = $2', [encrypt(trimmed), req.userId]);
    res.json({ success: true, maskedKey: maskAnthropicKey(trimmed) });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/anthropic-key', authenticateToken, async (req, res) => {
  try {
    await db.query('UPDATE users SET anthropic_api_key = NULL WHERE id = $1', [req.userId]);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ============================================================
// Web UI Notes endpoints (JWT protected, user-scoped)
// ============================================================

app.get('/api/notes', authenticateToken, async (req, res) => {
  try {
    const result = await db.query(
      'SELECT * FROM notes WHERE user_id = $1 ORDER BY pinned DESC, sort_order DESC, updated_at DESC',
      [req.userId]
    );
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/notes', authenticateToken, async (req, res) => {
  const { title, content } = req.body;

  try {
    // Use timestamp ms as a default sort order so newer notes naturally float up.
    const result = await db.query(
      'INSERT INTO notes (title, content, user_id, sort_order) VALUES ($1, $2, $3, (EXTRACT(EPOCH FROM NOW()) * 1000)::bigint) RETURNING id, pinned, sort_order',
      [title, content, req.userId]
    );
    res.json({ id: result.rows[0].id, title, content, pinned: result.rows[0].pinned, sort_order: result.rows[0].sort_order });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/notes/:id', authenticateToken, async (req, res) => {
  const { title, content, pinned, sort_order } = req.body;

  try {
    const result = await db.query(
      `UPDATE notes
       SET title = $1,
           content = $2,
           pinned = COALESCE($3, pinned),
           sort_order = COALESCE($4, sort_order),
           updated_at = NOW()
       WHERE id = $5 AND user_id = $6`,
      [title, content, pinned ?? null, sort_order ?? null, req.params.id, req.userId]
    );
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Note not found' });
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/notes/:id', authenticateToken, async (req, res) => {
  try {
    const result = await db.query('DELETE FROM notes WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Note not found' });
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ============================================================
// Web UI Todos endpoints (JWT protected, user-scoped)
// ============================================================

app.get('/api/todos', authenticateToken, async (req, res) => {
  const { category } = req.query;

  try {
    if (category) {
      const result = await db.query(
        'SELECT * FROM todos WHERE user_id = $1 AND category = $2 ORDER BY created_at DESC',
        [req.userId, category]
      );
      return res.json(result.rows);
    }

    const result = await db.query('SELECT * FROM todos WHERE user_id = $1 ORDER BY created_at DESC', [req.userId]);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

const DEFAULT_TODO_CATEGORIES = ['General', 'Shopping List'];

const normalizeTodoCategory = (name) => (name || '').toLowerCase().trim();

app.get('/api/todo-categories', authenticateToken, async (req, res) => {
  try {
    // 1) Categories the user has explicitly created
    const stored = await db.query(
      'SELECT name FROM todo_categories WHERE user_id = $1 ORDER BY name',
      [req.userId]
    );

    // 2) Categories already in-use by existing todos (backward compatibility)
    const used = await db.query(
      'SELECT DISTINCT category FROM todos WHERE user_id = $1 ORDER BY category',
      [req.userId]
    );

    const storedNames = stored.rows.map((r) => r.name).filter(Boolean);
    const usedNames = used.rows.map((r) => r.category).filter(Boolean);

    // Always include defaults; then union everything unique.
    const all = [...new Set([...DEFAULT_TODO_CATEGORIES, ...storedNames, ...usedNames])]
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));

    // Keep defaults first, then the rest alpha.
    const defaultsFirst = [
      ...DEFAULT_TODO_CATEGORIES,
      ...all.filter((c) => !DEFAULT_TODO_CATEGORIES.includes(c))
    ];

    res.json(defaultsFirst);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/todo-categories', authenticateToken, async (req, res) => {
  const { name } = req.body;
  const trimmed = (name || '').trim();
  const normalized = normalizeTodoCategory(trimmed);

  if (!trimmed) return res.status(400).json({ error: 'Category name is required' });
  if (DEFAULT_TODO_CATEGORIES.map(normalizeTodoCategory).includes(normalized)) {
    return res.status(400).json({ error: 'This category already exists' });
  }

  try {
    await db.query(
      'INSERT INTO todo_categories (name, normalized_name, user_id) VALUES ($1, $2, $3) ON CONFLICT (user_id, normalized_name) DO NOTHING',
      [trimmed, normalized, req.userId]
    );
    res.json({ success: true, name: trimmed });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/todo-categories', authenticateToken, async (req, res) => {
  // Deleting by name keeps the UI simple.
  const { name } = req.body;
  const trimmed = (name || '').trim();
  const normalized = normalizeTodoCategory(trimmed);

  if (!trimmed) return res.status(400).json({ error: 'Category name is required' });
  if (DEFAULT_TODO_CATEGORIES.map(normalizeTodoCategory).includes(normalized)) {
    return res.status(400).json({ error: 'Default categories cannot be deleted' });
  }

  try {
    await db.query('BEGIN');

    // Re-link todos in that category back to General.
    await db.query(
      "UPDATE todos SET category = 'General' WHERE user_id = $1 AND lower(category) = $2",
      [req.userId, normalized]
    );

    // Delete stored category (if it exists).
    await db.query(
      'DELETE FROM todo_categories WHERE user_id = $1 AND normalized_name = $2',
      [req.userId, normalized]
    );

    await db.query('COMMIT');
    res.json({ success: true });
  } catch (err) {
    await db.query('ROLLBACK');
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/todos', authenticateToken, async (req, res) => {
  const { text, category } = req.body;
  const cat = category || 'General';

  try {
    const result = await db.query(
      'INSERT INTO todos (text, category, user_id) VALUES ($1, $2, $3) RETURNING id',
      [text, cat, req.userId]
    );
    res.json({ id: result.rows[0].id, text, completed: false, category: cat });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/todos/:id', authenticateToken, async (req, res) => {
  const { completed } = req.body;

  try {
    const result = await db.query('UPDATE todos SET completed = $1 WHERE id = $2 AND user_id = $3', [
      completed ? true : false,
      req.params.id,
      req.userId
    ]);

    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Todo not found' });
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/todos/:id', authenticateToken, async (req, res) => {
  try {
    const result = await db.query('DELETE FROM todos WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Todo not found' });
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ============================================================
// Web UI Recipes endpoints (JWT protected, user-scoped)
// ============================================================

app.get('/api/recipes', authenticateToken, async (req, res) => {
  try {
    const result = await db.query('SELECT * FROM recipes WHERE user_id = $1 ORDER BY updated_at DESC', [req.userId]);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

function streamToBuffer(stream) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    stream.on('data', (c) => chunks.push(Buffer.isBuffer(c) ? c : Buffer.from(c)));
    stream.on('end', () => resolve(Buffer.concat(chunks)));
    stream.on('error', reject);
  });
}

async function getRecipePdfBuffer({ userId, recipeId }) {
  const result = await db.query(
    'SELECT pdf_filename, pdf_original_name FROM recipes WHERE id = $1 AND user_id = $2',
    [recipeId, userId]
  );
  const row = result.rows[0];
  if (!row || !row.pdf_filename) return null;

  if (s3.isEnabled()) {
    const stream = await s3.getPdfStream(row.pdf_filename);
    const buf = await streamToBuffer(stream);
    return { buffer: buf, originalName: row.pdf_original_name || 'recipe.pdf' };
  }

  let filePath;
  try {
    filePath = resolveRecipeUploadPath(row.pdf_filename);
  } catch (err) {
    console.warn(`[recipes] Invalid local PDF path for recipe ${recipeId}: ${err.message}`);
    return null;
  }
  if (!fs.existsSync(filePath)) return null;
  return { buffer: fs.readFileSync(filePath), originalName: row.pdf_original_name || 'recipe.pdf' };
}

async function claudeExtractIngredientsFromText({ text, recipeName, apiKey }) {
  if (!apiKey) throw new Error('No Anthropic API key available');

  const model = process.env.OCR_MODEL || 'claude-haiku-4-5-20251001';
  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model,
      max_tokens: 2048,
      system:
        'You extract recipe ingredient lists. Return ONLY JSON. ' +
        'Return {"ingredients": ["..."]}. ' +
        'Keep quantities/units. Do not include method steps.',
      messages: [
        {
          role: 'user',
          content: `Recipe name: ${recipeName}.\n\nText to extract from:\n${(text || '').toString()}`
        },
        // Prefill forces Claude to emit raw JSON with no prose or code fences.
        { role: 'assistant', content: '{' }
      ]
    })
  });

  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    const msg = data?.error?.message || JSON.stringify(data);
    const err = new Error(`Claude extract failed: ${resp.status} ${msg}`);
    if (resp.status === 401 || resp.status === 403) err.code = 'AI_UNAUTHORIZED';
    throw err;
  }

  // API returns the completion after our prefill '{', so restore it.
  const outText = '{' + (data?.content?.[0]?.text || '');

  let parsed;
  try {
    parsed = JSON.parse(outText);
  } catch {
    const match = outText.match(/\{[\s\S]*\}/);
    if (match) {
      try { parsed = JSON.parse(match[0]); } catch {}
    }
  }

  if (Array.isArray(parsed?.ingredients)) {
    return parsed.ingredients
      .map((s) => (typeof s === 'string' ? s.trim() : ''))
      .filter(Boolean)
      .slice(0, 200);
  }

  throw new Error('Claude extract failed: could not parse ingredients JSON');
}

async function claudeExtractIngredientsFromPdf({ pdfBuffer, recipeName, originalName, apiKey }) {
  if (!apiKey) throw new Error('No Anthropic API key available');

  const model = process.env.OCR_MODEL || 'claude-haiku-4-5-20251001';
  const base64 = pdfBuffer.toString('base64');

  const resp = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'x-api-key': apiKey,
      'anthropic-version': '2023-06-01',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      model,
      max_tokens: 2048,
      system:
        'You extract recipe ingredient lists. Return ONLY JSON. ' +
        'If there is a single recipe, return {"ingredients": ["..."]}. ' +
        'If there are multiple recipes, return {"recipes": [{"name": "...", "ingredients": ["..."]}]}. ' +
        'Keep quantities/units. Do not include method steps.',
      messages: [
        {
          role: 'user',
          content: [
            {
              type: 'document',
              source: {
                type: 'base64',
                media_type: 'application/pdf',
                data: base64
              }
            },
            {
              type: 'text',
              text: `Extract the ingredient list(s) for: ${recipeName}. Return JSON only.`
            }
          ]
        },
        // Prefill forces Claude to emit raw JSON with no prose or code fences.
        { role: 'assistant', content: '{' }
      ]
    })
  });

  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    const msg = data?.error?.message || JSON.stringify(data);
    const err = new Error(`Claude extract failed: ${resp.status} ${msg}`);
    if (resp.status === 401 || resp.status === 403) err.code = 'AI_UNAUTHORIZED';
    throw err;
  }

  // API returns the completion after our prefill '{', so restore it.
  const outText = '{' + (data?.content?.[0]?.text || '');

  let parsed;
  try {
    parsed = JSON.parse(outText);
  } catch {
    const match = outText.match(/\{[\s\S]*\}/);
    if (match) {
      try { parsed = JSON.parse(match[0]); } catch {}
    }
  }

  // Single recipe
  if (Array.isArray(parsed?.ingredients)) {
    return parsed.ingredients
      .map((s) => (typeof s === 'string' ? s.trim() : ''))
      .filter(Boolean)
      .slice(0, 200);
  }

  // Multi recipe: flatten with headings to keep the todo list readable.
  if (Array.isArray(parsed?.recipes)) {
    const out = [];
    for (const r of parsed.recipes) {
      const name = (r?.name || '').toString().trim();
      const ings = Array.isArray(r?.ingredients) ? r.ingredients : [];
      if (!name && !ings.length) continue;
      if (name) out.push(`— ${name} —`);
      for (const ing of ings) {
        const t = (typeof ing === 'string' ? ing.trim() : '');
        if (t) out.push(t);
      }
    }
    return out.filter(Boolean).slice(0, 400);
  }

  throw new Error('Claude extract failed: could not parse ingredients JSON');
}

app.post('/api/recipes', authenticateToken, upload.single('pdf'), convertImageIfNeeded, async (req, res) => {
  const { name, notes } = req.body;

  if (!name) {
    return res.status(400).json({ error: 'Recipe name is required' });
  }

  let pdfKey = null;
  const pdfOriginalName = req.file ? req.file.originalname : null;

  try {
    if (req.file) {
      if (s3.isEnabled()) {
        const uploaded = await s3.putPdf({
          userId: req.userId,
          buffer: req.file.buffer,
          contentType: req.file.mimetype,
          originalName: req.file.originalname
        });
        pdfKey = uploaded.key;
      } else {
        // Write buffer to local disk
        const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
        const filename = uniqueSuffix + '-' + req.file.originalname;
        fs.writeFileSync(path.join(UPLOADS_DIR, filename), req.file.buffer);
        pdfKey = filename;
      }
    }

    const result = await db.query(
      'INSERT INTO recipes (name, notes, pdf_filename, pdf_original_name, user_id) VALUES ($1, $2, $3, $4, $5) RETURNING id',
      [name, notes || '', pdfKey, pdfOriginalName, req.userId]
    );

    res.json({
      id: result.rows[0].id,
      name,
      notes: notes || '',
      pdf_filename: pdfKey,
      pdf_original_name: pdfOriginalName
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/recipes/:id', authenticateToken, upload.single('pdf'), convertImageIfNeeded, async (req, res) => {
  const { name, notes, remove_pdf } = req.body;

  if (!name) {
    return res.status(400).json({ error: 'Recipe name is required' });
  }

  try {
    // First, get the existing recipe to handle PDF cleanup
    const existingResult = await db.query('SELECT * FROM recipes WHERE id = $1 AND user_id = $2', [
      req.params.id,
      req.userId
    ]);

    const existing = existingResult.rows[0];
    if (!existing) {
      return res.status(404).json({ error: 'Recipe not found' });
    }

    let pdfKey = existing.pdf_filename;
    let pdfOriginalName = existing.pdf_original_name;

    // If a new PDF was uploaded, delete the old one and store the new one
    if (req.file) {
      if (existing.pdf_filename) {
        if (s3.isEnabled()) {
          // best-effort cleanup
          s3.deleteObject(existing.pdf_filename).catch(() => {});
        } else {
          deleteLocalRecipePdf(existing.pdf_filename);
        }
      }

      if (s3.isEnabled()) {
        const uploaded = await s3.putPdf({
          userId: req.userId,
          buffer: req.file.buffer,
          contentType: req.file.mimetype,
          originalName: req.file.originalname
        });
        pdfKey = uploaded.key;
      } else {
        const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
        const filename = uniqueSuffix + '-' + req.file.originalname;
        fs.writeFileSync(path.join(UPLOADS_DIR, filename), req.file.buffer);
        pdfKey = filename;
      }

      pdfOriginalName = req.file.originalname;
    }

    // If remove_pdf flag is set, delete the existing PDF
    if (remove_pdf === 'true' && !req.file) {
      if (existing.pdf_filename) {
        if (s3.isEnabled()) s3.deleteObject(existing.pdf_filename).catch(() => {});
        else deleteLocalRecipePdf(existing.pdf_filename);
      }
      pdfKey = null;
      pdfOriginalName = null;
    }

    // Check if we need to clear cached ingredients
    // Clear if: new PDF uploaded OR PDF removed
    const pdfChanged = req.file || (remove_pdf === 'true' && existing.pdf_filename);

    // Update recipe
    await db.query(
      'UPDATE recipes SET name = $1, notes = $2, pdf_filename = $3, pdf_original_name = $4, updated_at = NOW() WHERE id = $5 AND user_id = $6',
      [name, notes || '', pdfKey, pdfOriginalName, req.params.id, req.userId]
    );

    // Clear cached ingredients if PDF changed (forces re-OCR on next create-ingredient-todos)
    if (pdfChanged) {
      await db.query('DELETE FROM ingredients WHERE recipe_id = $1', [req.params.id]);
      // Also clear the recipe pointer to todos
      await db.query(
        'UPDATE recipes SET ingredient_todo_category = NULL, ingredient_todos_count = NULL, ingredient_todos_created_at = NULL, updated_at = NOW() WHERE id = $1 AND user_id = $2',
        [req.params.id, req.userId]
      );
    }

    res.json({
      success: true,
      id: req.params.id,
      name,
      notes: notes || '',
      pdf_filename: pdfKey,
      pdf_original_name: pdfOriginalName
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/recipes/:id', authenticateToken, async (req, res) => {
  try {
    // First get the recipe to clean up the PDF file
    const rowResult = await db.query('SELECT pdf_filename FROM recipes WHERE id = $1 AND user_id = $2', [
      req.params.id,
      req.userId
    ]);

    const row = rowResult.rows[0];
    if (!row) {
      return res.status(404).json({ error: 'Recipe not found' });
    }

    // Delete the PDF file if it exists
    if (row.pdf_filename) {
      if (s3.isEnabled()) {
        s3.deleteObject(row.pdf_filename).catch(() => {});
      } else {
        deleteLocalRecipePdf(row.pdf_filename);
      }
    }

    await db.query('DELETE FROM recipes WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// Serve recipe PDF with auth check (supports token via query param for iframe usage)
app.get(
  '/api/recipes/:id/pdf',
  (req, res, next) => {
    // Allow token via query parameter for iframe embedding
    if (!req.headers['authorization'] && req.query.token) {
      req.headers['authorization'] = `Bearer ${req.query.token}`;
    }
    authenticateToken(req, res, next);
  },
  async (req, res) => {
    try {
      const result = await db.query(
        'SELECT pdf_filename, pdf_original_name FROM recipes WHERE id = $1 AND user_id = $2',
        [req.params.id, req.userId]
      );

      const row = result.rows[0];
      if (!row || !row.pdf_filename) {
        return res.status(404).json({ error: 'PDF not found' });
      }

      res.setHeader('Content-Type', 'application/pdf');
      res.setHeader('Cache-Control', 'no-store');
      res.setHeader(
        'Content-Disposition',
        `inline; filename="${(row.pdf_original_name || 'recipe.pdf').replace(/"/g, '')}"`
      );

      if (s3.isEnabled()) {
        const stream = await s3.getPdfStream(row.pdf_filename);
        stream.pipe(res);
      } else {
        let filePath;
        try {
          filePath = resolveRecipeUploadPath(row.pdf_filename);
        } catch (err) {
          return res.status(404).json({ error: 'PDF file not found on disk' });
        }
        if (!fs.existsSync(filePath)) {
          return res.status(404).json({ error: 'PDF file not found on disk' });
        }
        fs.createReadStream(filePath).pipe(res);
      }
    } catch (err) {
      res.status(500).json({ error: err.message });
    }
  }
);

// Create ingredient todo list from a recipe PDF (JWT protected)
// Flow: Check DB first → if cached, use it (skip OCR) → else OCR → save to DB → create todos
app.post('/api/recipes/:id/create-ingredient-todos', authenticateToken, async (req, res) => {
  try {
    // Resolve Anthropic API key: per-user key takes priority, then global env var
    const userRow = await db.query('SELECT anthropic_api_key FROM users WHERE id = $1', [req.userId]);
    const rawUserKey = userRow.rows[0]?.anthropic_api_key;
    const userAnthropicKey = rawUserKey ? decrypt(rawUserKey) : null;
    const resolvedApiKey = userAnthropicKey || RAW_ANTHROPIC_KEY;

    if (!resolvedApiKey) {
      return res.status(403).json({ error: 'No Anthropic API key configured. Add one in Admin settings.' });
    }

    const recipeId = req.params.id;

    const recipeRes = await db.query('SELECT * FROM recipes WHERE id = $1 AND user_id = $2', [recipeId, req.userId]);
    const recipe = recipeRes.rows[0];
    if (!recipe) return res.status(404).json({ error: 'Recipe not found' });

    // STEP 1: Check if we already have cached ingredients in the DB
    const cachedIngredientsRes = await db.query(
      'SELECT name, quantity FROM ingredients WHERE recipe_id = $1 ORDER BY id ASC',
      [recipeId]
    );
    const hasCachedIngredients = cachedIngredientsRes.rows.length > 0;

    let ingredients = [];

    if (hasCachedIngredients) {
      // Use cached ingredients - skip expensive OCR API call
      ingredients = cachedIngredientsRes.rows.map((row) => {
        // Return name only, or "quantity name" if quantity exists
        if (row.quantity && row.quantity.trim()) {
          return `${row.quantity} ${row.name}`.trim();
        }
        return row.name;
      });
    } else {
      // STEP 2: No cached ingredients - need to extract via OCR
      if (recipe.pdf_filename) {
        const pdf = await getRecipePdfBuffer({ userId: req.userId, recipeId });
        if (!pdf) return res.status(404).json({ error: 'PDF not found' });

        // Extract ingredients via OCR API
        ingredients = await claudeExtractIngredientsFromPdf({
          pdfBuffer: pdf.buffer,
          recipeName: recipe.name,
          originalName: pdf.originalName,
          apiKey: resolvedApiKey
        });
      } else {
        // No PDF: assume ingredients live in recipe notes.
        const text = (recipe.notes || '').trim();
        if (!text) {
          return res.status(400).json({ error: 'Recipe has no PDF and no notes to extract ingredients from' });
        }
        ingredients = await claudeExtractIngredientsFromText({ text, recipeName: recipe.name, apiKey: resolvedApiKey });
      }

      // STEP 3: Save extracted ingredients to DB (for future caching)
      if (ingredients.length > 0) {
        await db.query('DELETE FROM ingredients WHERE recipe_id = $1', [recipeId]); // Clear any stale entries
        for (const ing of ingredients) {
          // Try to parse "quantity name" format, default to name only
          const match = ing.match(/^([\d\s\.\/]+(?:g|kg|ml|l|tsp|tbsp|cup|pinch|piece|slice|clove)?\s+)(.+)$/i);
          const quantity = match ? match[1].trim() : null;
          const name = match ? match[2].trim() : ing;
          await db.query(
            'INSERT INTO ingredients (recipe_id, name, quantity) VALUES ($1, $2, $3)',
            [recipeId, name, quantity]
          );
        }
      }
    }

    // STEP 4: Create todo items from ingredients
    const categoryName = (recipe.name || 'Recipe').trim();
    const normalized = categoryName.toLowerCase().trim();

    await db.query('BEGIN');

    // Ensure category exists
    await db.query(
      'INSERT INTO todo_categories (name, normalized_name, user_id) VALUES ($1, $2, $3) ON CONFLICT (user_id, normalized_name) DO NOTHING',
      [categoryName, normalized, req.userId]
    );

    const existingTodosRes = await db.query(
      'SELECT COUNT(*)::int AS count FROM todos WHERE user_id = $1 AND category = $2',
      [req.userId, categoryName]
    );
    const existingTodos = parseInt(existingTodosRes.rows[0]?.count || '0', 10);

    let inserted = existingTodos;

    if (hasCachedIngredients || existingTodos === 0) {
      // Delete any existing todos for this category (so deleted items get restored from DB)
      await db.query('DELETE FROM todos WHERE user_id = $1 AND category = $2', [req.userId, categoryName]);

      // Create fresh todos from ingredients
      inserted = 0;
      for (const ing of ingredients) {
        await db.query('INSERT INTO todos (text, category, user_id) VALUES ($1, $2, $3)', [ing, categoryName, req.userId]);
        inserted++;
      }

      // Update the count pointer
      await db.query(
        'UPDATE recipes SET ingredient_todos_count = $2 WHERE id = $1 AND user_id = $3',
        [recipeId, inserted, req.userId]
      );
    }

    await db.query('COMMIT');

    res.json({ ok: true, alreadyCreated: existingTodos > 0, category: categoryName, count: inserted });
  } catch (err) {
    try { await db.query('ROLLBACK'); } catch {}
    if (err && err.code === 'AI_UNAUTHORIZED') {
      ingredientAutomationEnabled = false;
      console.warn('Ingredient automation disabled due to Anthropic authorization failure.');
      return res.status(503).json({ error: 'Ingredient automation is currently disabled' });
    }
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/features', authenticateToken, async (req, res) => {
  try {
    const userRow = await db.query('SELECT anthropic_api_key FROM users WHERE id = $1', [req.userId]);
    const hasUserKey = !!userRow.rows[0]?.anthropic_api_key;
    res.json({
      ingredientAutomation: hasUserKey || isIngredientAutomationEnabled()
    });
  } catch (err) {
    res.json({ ingredientAutomation: isIngredientAutomationEnabled() });
  }
});

// ============================================================
// API v1 Notes endpoints (API key protected, user-scoped)
// ============================================================

app.get('/api/v1/notes', authenticateAPIKey, async (req, res) => {
  const { search } = req.query;

  try {
    if (search) {
      const result = await db.query(
        'SELECT * FROM notes WHERE user_id = $1 AND (title ILIKE $2 OR content ILIKE $2) ORDER BY updated_at DESC',
        [req.userId, `%${search}%`]
      );
      return res.json(result.rows);
    }

    const result = await db.query('SELECT * FROM notes WHERE user_id = $1 ORDER BY updated_at DESC', [req.userId]);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/v1/notes/:id', authenticateAPIKey, async (req, res) => {
  try {
    const result = await db.query('SELECT * FROM notes WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    const row = result.rows[0];
    if (!row) {
      return res.status(404).json({ error: 'Note not found' });
    }
    res.json(row);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/v1/notes', authenticateAPIKey, async (req, res) => {
  const { title, content } = req.body;

  if (!title) {
    return res.status(400).json({ error: 'Title is required' });
  }

  try {
    const result = await db.query(
      'INSERT INTO notes (title, content, user_id) VALUES ($1, $2, $3) RETURNING id',
      [title, content || '', req.userId]
    );

    res.status(201).json({
      id: result.rows[0].id,
      title,
      content: content || '',
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString()
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/v1/notes/:id', authenticateAPIKey, async (req, res) => {
  const { title, content } = req.body;

  if (!title) {
    return res.status(400).json({ error: 'Title is required' });
  }

  try {
    const result = await db.query(
      'UPDATE notes SET title = $1, content = $2, updated_at = NOW() WHERE id = $3 AND user_id = $4',
      [title, content || '', req.params.id, req.userId]
    );
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Note not found' });
    }
    res.json({ success: true, id: req.params.id, title, content });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/v1/notes/:id', authenticateAPIKey, async (req, res) => {
  try {
    const result = await db.query('DELETE FROM notes WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Note not found' });
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ============================================================
// API v1 Todos endpoints (API key protected, user-scoped)
// ============================================================

app.get('/api/v1/todos', authenticateAPIKey, async (req, res) => {
  const { search, completed } = req.query;

  try {
    const params = [req.userId];
    const where = ['user_id = $1'];

    if (search) {
      params.push(`%${search}%`);
      where.push(`text ILIKE $${params.length}`);
    }

    if (completed !== undefined) {
      params.push(completed === 'true');
      where.push(`completed = $${params.length}`);
    }

    const sql = `SELECT * FROM todos WHERE ${where.join(' AND ')} ORDER BY created_at DESC`;
    const result = await db.query(sql, params);
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/v1/todos/:id', authenticateAPIKey, async (req, res) => {
  try {
    const result = await db.query('SELECT * FROM todos WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    const row = result.rows[0];
    if (!row) {
      return res.status(404).json({ error: 'Todo not found' });
    }
    res.json(row);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/v1/todos', authenticateAPIKey, async (req, res) => {
  const { text, completed } = req.body;

  if (!text) {
    return res.status(400).json({ error: 'Text is required' });
  }

  try {
    const result = await db.query(
      'INSERT INTO todos (text, completed, user_id) VALUES ($1, $2, $3) RETURNING id',
      [text, completed ? true : false, req.userId]
    );

    res.status(201).json({
      id: result.rows[0].id,
      text,
      completed: completed ? true : false,
      created_at: new Date().toISOString()
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/v1/todos/:id', authenticateAPIKey, async (req, res) => {
  const { text, completed } = req.body;

  const updates = [];
  const params = [];

  if (text !== undefined) {
    params.push(text);
    updates.push(`text = $${params.length}`);
  }

  if (completed !== undefined) {
    params.push(completed ? true : false);
    updates.push(`completed = $${params.length}`);
  }

  if (updates.length === 0) {
    return res.status(400).json({ error: 'No fields to update' });
  }

  params.push(req.params.id);
  params.push(req.userId);

  try {
    const result = await db.query(
      `UPDATE todos SET ${updates.join(', ')} WHERE id = $${params.length - 1} AND user_id = $${params.length}`,
      params
    );

    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Todo not found' });
    }

    res.json({ success: true, id: req.params.id });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.patch('/api/v1/todos/:id/complete', authenticateAPIKey, async (req, res) => {
  try {
    const result = await db.query('UPDATE todos SET completed = TRUE WHERE id = $1 AND user_id = $2', [
      req.params.id,
      req.userId
    ]);
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Todo not found' });
    }
    res.json({ success: true, id: req.params.id, completed: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.patch('/api/v1/todos/:id/incomplete', authenticateAPIKey, async (req, res) => {
  try {
    const result = await db.query('UPDATE todos SET completed = FALSE WHERE id = $1 AND user_id = $2', [
      req.params.id,
      req.userId
    ]);
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Todo not found' });
    }
    res.json({ success: true, id: req.params.id, completed: false });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/v1/todos/:id', authenticateAPIKey, async (req, res) => {
  try {
    const result = await db.query('DELETE FROM todos WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Todo not found' });
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ============================================================
// API v1 Recipes endpoints (API key protected, user-scoped)
// ============================================================

app.get('/api/v1/recipes', authenticateAPIKey, async (req, res) => {
  const { search } = req.query;
  try {
    if (search) {
      const result = await db.query(
        'SELECT * FROM recipes WHERE user_id = $1 AND (name ILIKE $2 OR notes ILIKE $2) ORDER BY updated_at DESC',
        [req.userId, `%${search}%`]
      );
      return res.json(result.rows);
    }
    const result = await db.query(
      'SELECT * FROM recipes WHERE user_id = $1 ORDER BY updated_at DESC',
      [req.userId]
    );
    res.json(result.rows);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get('/api/v1/recipes/:id', authenticateAPIKey, async (req, res) => {
  try {
    const result = await db.query(
      'SELECT * FROM recipes WHERE id = $1 AND user_id = $2',
      [req.params.id, req.userId]
    );
    const row = result.rows[0];
    if (!row) {
      return res.status(404).json({ error: 'Recipe not found' });
    }
    res.json(row);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post('/api/v1/recipes', authenticateAPIKey, upload.single('pdf'), convertImageIfNeeded, async (req, res) => {
  const { name, notes } = req.body;

  if (!name) {
    return res.status(400).json({ error: 'Recipe name is required' });
  }

  let pdfKey = null;
  const pdfOriginalName = req.file ? req.file.originalname : null;

  try {
    if (req.file) {
      if (s3.isEnabled()) {
        const uploaded = await s3.putPdf({
          userId: req.userId,
          buffer: req.file.buffer,
          contentType: req.file.mimetype,
          originalName: req.file.originalname
        });
        pdfKey = uploaded.key;
      } else {
        const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
        const filename = uniqueSuffix + '-' + req.file.originalname;
        fs.writeFileSync(path.join(UPLOADS_DIR, filename), req.file.buffer);
        pdfKey = filename;
      }
    }

    const result = await db.query(
      'INSERT INTO recipes (name, notes, pdf_filename, pdf_original_name, user_id) VALUES ($1, $2, $3, $4, $5) RETURNING id',
      [name, notes || '', pdfKey, pdfOriginalName, req.userId]
    );

    res.status(201).json({
      id: result.rows[0].id,
      name,
      notes: notes || '',
      pdf_filename: pdfKey,
      pdf_original_name: pdfOriginalName
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.put('/api/v1/recipes/:id', authenticateAPIKey, upload.single('pdf'), convertImageIfNeeded, async (req, res) => {
  const { name, notes, remove_pdf } = req.body;

  if (!name) {
    return res.status(400).json({ error: 'Recipe name is required' });
  }

  try {
    const existingResult = await db.query('SELECT * FROM recipes WHERE id = $1 AND user_id = $2', [
      req.params.id,
      req.userId
    ]);

    const existing = existingResult.rows[0];
    if (!existing) {
      return res.status(404).json({ error: 'Recipe not found' });
    }

    let pdfKey = existing.pdf_filename;
    let pdfOriginalName = existing.pdf_original_name;

    // If a new file was uploaded, delete the old one and store the new one
    if (req.file) {
      if (existing.pdf_filename) {
        if (s3.isEnabled()) {
          s3.deleteObject(existing.pdf_filename).catch(() => {});
        } else {
          deleteLocalRecipePdf(existing.pdf_filename);
        }
      }

      if (s3.isEnabled()) {
        const uploaded = await s3.putPdf({
          userId: req.userId,
          buffer: req.file.buffer,
          contentType: req.file.mimetype,
          originalName: req.file.originalname
        });
        pdfKey = uploaded.key;
      } else {
        const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
        const filename = uniqueSuffix + '-' + req.file.originalname;
        fs.writeFileSync(path.join(UPLOADS_DIR, filename), req.file.buffer);
        pdfKey = filename;
      }

      pdfOriginalName = req.file.originalname;
    }

    // If remove_pdf flag is set, delete the existing file
    if (remove_pdf === 'true' && !req.file) {
      if (existing.pdf_filename) {
        if (s3.isEnabled()) s3.deleteObject(existing.pdf_filename).catch(() => {});
        else deleteLocalRecipePdf(existing.pdf_filename);
      }
      pdfKey = null;
      pdfOriginalName = null;
    }

    const pdfChanged = req.file || (remove_pdf === 'true' && existing.pdf_filename);

    await db.query(
      'UPDATE recipes SET name = $1, notes = $2, pdf_filename = $3, pdf_original_name = $4, updated_at = NOW() WHERE id = $5 AND user_id = $6',
      [name, notes || '', pdfKey, pdfOriginalName, req.params.id, req.userId]
    );

    // Clear cached ingredients if PDF changed (forces re-OCR on next create-ingredient-todos)
    if (pdfChanged) {
      await db.query('DELETE FROM ingredients WHERE recipe_id = $1', [req.params.id]);
      await db.query(
        'UPDATE recipes SET ingredient_todo_category = NULL, ingredient_todos_count = NULL, ingredient_todos_created_at = NULL, updated_at = NOW() WHERE id = $1 AND user_id = $2',
        [req.params.id, req.userId]
      );
    }

    res.json({
      success: true,
      id: req.params.id,
      name,
      notes: notes || '',
      pdf_filename: pdfKey,
      pdf_original_name: pdfOriginalName
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.delete('/api/v1/recipes/:id', authenticateAPIKey, async (req, res) => {
  try {
    const rowResult = await db.query('SELECT pdf_filename FROM recipes WHERE id = $1 AND user_id = $2', [
      req.params.id,
      req.userId
    ]);

    const row = rowResult.rows[0];
    if (!row) {
      return res.status(404).json({ error: 'Recipe not found' });
    }

    if (row.pdf_filename) {
      if (s3.isEnabled()) {
        s3.deleteObject(row.pdf_filename).catch(() => {});
      } else {
        deleteLocalRecipePdf(row.pdf_filename);
      }
    }

    await db.query('DELETE FROM recipes WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

async function start() {
  await migrate();

  app.listen(PORT, () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

start().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
