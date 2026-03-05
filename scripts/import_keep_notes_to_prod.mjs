#!/usr/bin/env node
/**
 * Import Google Keep Takeout HTML notes into Notes & Todos app (prod) via JWT login.
 *
 * Usage:
 *   KEEP_PASSWORD='...' node scripts/import_keep_notes_to_prod.mjs \
 *     --dir "/Users/chris/Downloads/Takeout/Keep" \
 *     --username chris \
 *     --baseUrl https://todo.promptbuilt.co.uk \
 *     --limit 10        # optional
 *     --dryRun          # optional
 *
 * Notes:
 * - Does NOT persist password.
 * - Creates notes only (non-destructive).
 */

import fs from 'node:fs/promises';
import path from 'node:path';

const args = process.argv.slice(2);
const getArg = (name, def = undefined) => {
  const idx = args.indexOf(name);
  if (idx === -1) return def;
  const val = args[idx + 1];
  if (!val || val.startsWith('--')) return def;
  return val;
};
const hasFlag = (name) => args.includes(name);

const dir = getArg('--dir');
const username = (getArg('--username', 'chris') || 'chris');
const baseUrl = (getArg('--baseUrl', 'https://todo.promptbuilt.co.uk') || 'https://todo.promptbuilt.co.uk').replace(/\/$/, '');
const limit = parseInt(getArg('--limit', '0'), 10) || 0;
const dryRun = hasFlag('--dryRun');
const password = process.env.KEEP_PASSWORD;

if (!dir) {
  console.error('Missing --dir');
  process.exit(1);
}
if (!password && !dryRun) {
  console.error('Missing KEEP_PASSWORD env var (required unless --dryRun)');
  process.exit(1);
}

const decodeHtml = (s) =>
  s
    .replaceAll('&nbsp;', ' ')
    .replaceAll('&amp;', '&')
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&#39;', "'");

function stripTags(html) {
  // Keep line breaks: convert <br> and <div> boundaries to newlines first.
  let s = html;
  s = s.replace(/<\s*br\s*\/?>/gi, '\n');
  s = s.replace(/<\s*\/div\s*>/gi, '\n');
  s = s.replace(/<\s*div[^>]*>/gi, '');
  s = s.replace(/<\s*\/p\s*>/gi, '\n');
  s = s.replace(/<\s*p[^>]*>/gi, '');
  s = s.replace(/<[^>]+>/g, '');
  s = decodeHtml(s);
  // normalize whitespace
  s = s.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  s = s.replace(/\n{3,}/g, '\n\n');
  return s.trim();
}

function extractBetween(html, startRe, endRe) {
  const start = html.search(startRe);
  if (start === -1) return null;
  const slice = html.slice(start);
  const startMatch = slice.match(startRe);
  if (!startMatch) return null;
  const afterStart = slice.slice(startMatch.index + startMatch[0].length);
  const end = afterStart.search(endRe);
  if (end === -1) return afterStart;
  return afterStart.slice(0, end);
}

function extractTitle(html) {
  const m1 = html.match(/<div\s+class="title"[^>]*>([\s\S]*?)<\/div>/i);
  if (m1) return stripTags(m1[1]);
  const m2 = html.match(/<title>([\s\S]*?)<\/title>/i);
  if (m2) return stripTags(m2[1]);
  return '';
}

function extractContent(html) {
  const m = html.match(/<div\s+class="content"[^>]*>([\s\S]*?)<\/div>/i);
  if (m) return stripTags(m[1]);
  // fallback: try to locate note body
  const maybe = extractBetween(html, /<body[^>]*>/i, /<\/body>/i);
  return maybe ? stripTags(maybe) : '';
}

function saneTitle({ fileName, htmlTitle, contentText }) {
  const t = (htmlTitle || '').trim();
  const base = path.basename(fileName, path.extname(fileName));

  const looksLikeTimestamp = /^\d{4}-\d{2}-\d{2}T\d{2}_\d{2}_\d{2}\./.test(fileName) || /^\d{4}-\d{2}-\d{2}T/.test(base);

  if (t && !(looksLikeTimestamp && t === base)) return t;

  const firstLine = (contentText || '').split('\n').map(s => s.trim()).find(Boolean);
  if (firstLine) return firstLine.slice(0, 80);

  if (t) return t;
  return looksLikeTimestamp ? `Keep note ${base.slice(0, 10)}` : base;
}

async function login() {
  if (dryRun) return 'DRY_RUN_TOKEN';
  const res = await fetch(`${baseUrl}/api/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`Login failed: ${res.status} ${data.error || ''}`.trim());
  }
  return data.token;
}

async function createNote(token, title, content) {
  if (dryRun) {
    console.log(`[dryRun] would create note: ${title}`);
    return;
  }
  const res = await fetch(`${baseUrl}/api/notes`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`
    },
    body: JSON.stringify({ title, content })
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(`Create note failed (${res.status}): ${data.error || JSON.stringify(data)}`);
  }
}

async function main() {
  const entries = await fs.readdir(dir);
  const files = entries
    .filter((f) => f.toLowerCase().endsWith('.html'))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' }));

  const selected = limit ? files.slice(0, limit) : files;
  console.log(`Found ${files.length} HTML files, processing ${selected.length}${limit ? ` (limit ${limit})` : ''}`);

  const token = await login();

  let ok = 0;
  for (const f of selected) {
    const full = path.join(dir, f);
    const html = await fs.readFile(full, 'utf8');
    const htmlTitle = extractTitle(html);
    const contentText = extractContent(html);

    const title = saneTitle({ fileName: f, htmlTitle, contentText });
    const content = contentText;

    if (!title && !content) {
      console.log(`Skipping empty note: ${f}`);
      continue;
    }

    await createNote(token, title || '(untitled)', content || '');
    ok++;
    if (ok % 25 === 0) console.log(`Created ${ok} notes...`);
  }

  console.log(`Done. Created ${ok} notes.`);
}

main().catch((err) => {
  console.error(err.stack || err.message || String(err));
  process.exit(1);
});
