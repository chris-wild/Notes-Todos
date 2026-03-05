#!/usr/bin/env node
/*
Imports PDFs from a Google Drive folder into prod Recipes.

Usage:
  GOG_ACCOUNT=you@gmail.com \
  PROD_BASE=https://todo.promptbuilt.co.uk \
  PROD_USERNAME=Chris PROD_PASSWORD='...' \
  DRIVE_FOLDER_ID=<id> \
  node deploy/import_recipes_from_drive_to_prod.mjs
*/

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const {
  GOG_ACCOUNT,
  PROD_BASE = 'https://todo.promptbuilt.co.uk',
  PROD_USERNAME,
  PROD_PASSWORD,
  DRIVE_FOLDER_ID,
} = process.env;

if (!GOG_ACCOUNT) throw new Error('GOG_ACCOUNT is required');
if (!PROD_USERNAME) throw new Error('PROD_USERNAME is required');
if (!PROD_PASSWORD) throw new Error('PROD_PASSWORD is required');
if (!DRIVE_FOLDER_ID) throw new Error('DRIVE_FOLDER_ID is required');

function gogJson(args) {
  const out = execFileSync('gog', args, {
    encoding: 'utf8',
    env: { ...process.env, GOG_ACCOUNT },
  });
  return JSON.parse(out);
}

async function login() {
  const res = await fetch(`${PROD_BASE}/api/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: PROD_USERNAME, password: PROD_PASSWORD }),
  });
  if (!res.ok) throw new Error(`Prod login failed: ${res.status} ${await res.text()}`);
  return res.json();
}

async function getExistingRecipes(token) {
  const res = await fetch(`${PROD_BASE}/api/recipes`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`Fetch existing recipes failed: ${res.status} ${await res.text()}`);
  const rows = await res.json();
  const names = new Set(rows.map((r) => (r.name || '').trim()).filter(Boolean));
  return { rows, names };
}

function listDrivePdfs() {
  // Paginate gog drive ls
  let page = undefined;
  const files = [];
  while (true) {
    const args = ['drive', 'ls', '--json', '--parent', DRIVE_FOLDER_ID, '--max', '200'];
    if (page) args.push('--page', page);
    // query only PDFs
    args.push('--query', "mimeType='application/pdf' and trashed=false");

    const data = gogJson(args);
    if (data?.files?.length) files.push(...data.files);
    page = data?.nextPageToken;
    if (!page) break;
  }
  return files;
}

function recipeNameFromFilename(filename) {
  return filename.replace(/\.pdf$/i, '').trim();
}

async function createRecipeWithPdf(token, name, pdfPath) {
  const fd = new FormData();
  fd.set('name', name);
  fd.set('notes', '');

  const buf = fs.readFileSync(pdfPath);
  const blob = new Blob([buf], { type: 'application/pdf' });
  fd.set('pdf', blob, path.basename(pdfPath));

  const res = await fetch(`${PROD_BASE}/api/recipes`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: fd,
  });

  if (!res.ok) throw new Error(`Create recipe failed (${name}): ${res.status} ${await res.text()}`);
  return res.json();
}

function downloadDriveFile(fileId, outPath) {
  execFileSync('gog', ['drive', 'download', fileId, '--out', outPath], {
    stdio: 'inherit',
    env: { ...process.env, GOG_ACCOUNT },
  });
}

const run = async () => {
  console.log(`Prod: ${PROD_BASE}`);
  console.log(`Drive folder: ${DRIVE_FOLDER_ID}`);

  const { token } = await login();
  const existing = await getExistingRecipes(token);

  const driveFiles = listDrivePdfs();
  console.log(`Found ${driveFiles.length} PDF(s) in Drive folder.`);

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'recipes-import-'));

  let created = 0;
  let skipped = 0;
  let failed = 0;

  for (const f of driveFiles) {
    const name = recipeNameFromFilename(f.name);
    if (!name) continue;

    if (existing.names.has(name)) {
      console.log(`SKIP (already exists): ${name}`);
      skipped++;
      continue;
    }

    const outPath = path.join(tmpDir, f.name);
    try {
      console.log(`Downloading: ${f.name}`);
      downloadDriveFile(f.id, outPath);

      console.log(`Creating recipe: ${name}`);
      await createRecipeWithPdf(token, name, outPath);

      existing.names.add(name);
      created++;
    } catch (e) {
      failed++;
      console.error(`FAILED: ${name}`);
      console.error(e?.stack || String(e));
    } finally {
      try { fs.unlinkSync(outPath); } catch {}
    }
  }

  console.log('---');
  console.log(`Created: ${created}`);
  console.log(`Skipped: ${skipped}`);
  console.log(`Failed:  ${failed}`);
  console.log(`Temp dir: ${tmpDir}`);
};

run().catch((e) => {
  console.error(e?.stack || String(e));
  process.exit(1);
});
