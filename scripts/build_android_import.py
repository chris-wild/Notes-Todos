#!/usr/bin/env python3
"""Build the Android app's import zip from a raw `aws s3 sync` export.

Usage: python3 scripts/build_android_import.py [export_dir] [out_zip] [username]

Filters every collection to the given user (default: chris), drops the
server-only collections (users, api-keys) and user_id fields, keeps
ingredients whose recipe belongs to the user, and rewrites recipe PDF
references from `recipes/<userId>/<name>.pdf` to `recipes/<name>.pdf`.
The output zip matches android BackupCodec's format exactly.
"""
import json
import sys
import zipfile
from pathlib import Path

export_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "export")
out_zip = Path(sys.argv[2] if len(sys.argv) > 2 else "export/android-import.zip")
username = sys.argv[3] if len(sys.argv) > 3 else "chris"


def load(name):
    return json.loads((export_dir / "data" / f"{name}.json").read_text())


users = load("users")
user_ids = {str(u["id"]) for u in users if u["username"] == username}
if not user_ids:
    sys.exit(f"No user named {username!r} in users.json")
print(f"user {username!r} -> id(s) {sorted(user_ids)}")


def mine(rows):
    return [r for r in rows if str(r.get("user_id")) in user_ids]


def strip_user(row):
    return {k: v for k, v in row.items() if k != "user_id"}


notes = [strip_user(r) for r in mine(load("notes"))]
todos = [strip_user(r) for r in mine(load("todos"))]
categories = [strip_user(r) for r in mine(load("todo-categories"))]
recipes = [strip_user(r) for r in mine(load("recipes"))]

recipe_ids = {str(r["id"]) for r in recipes}
ingredients = [r for r in load("ingredients") if str(r.get("recipe_id")) in recipe_ids]

pdfs = {}  # zip entry name -> source path
missing = []
for r in recipes:
    key = r.get("pdf_filename")
    if not key:
        continue
    src = export_dir / key
    basename = key.rsplit("/", 1)[-1]
    if not src.exists():
        missing.append((r["id"], r["name"], key))
        r["pdf_filename"] = None
        r["pdf_original_name"] = None
        continue
    r["pdf_filename"] = f"recipes/{basename}"
    pdfs[f"recipes/{basename}"] = src

with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("data/notes.json", json.dumps(notes))
    z.writestr("data/todos.json", json.dumps(todos))
    z.writestr("data/todo-categories.json", json.dumps(categories))
    z.writestr("data/recipes.json", json.dumps(recipes))
    z.writestr("data/ingredients.json", json.dumps(ingredients))
    for entry, src in pdfs.items():
        z.write(src, entry)

print(f"notes={len(notes)} todos={len(todos)} categories={len(categories)} "
      f"recipes={len(recipes)} ingredients={len(ingredients)} pdfs={len(pdfs)}")
if missing:
    print(f"WARNING: {len(missing)} recipe(s) reference missing PDFs:")
    for rid, name, key in missing:
        print(f"  recipe {rid} {name!r}: {key}")
print(f"wrote {out_zip} ({out_zip.stat().st_size / 1e6:.1f} MB)")
