# Notes & Todos API -- Agent Reference

This document describes the REST API for agents that need to create notes, todos,
and recipes programmatically. All endpoints use API key authentication.

---

## Authentication

Every request must include an `x-api-key` header.

```
x-api-key: YOUR_API_KEY
```

API keys are created through the web UI (Settings > API Keys). Each key is
scoped to a single user account -- all resources created through the API
belong to that user.

| Status | Body | Meaning |
|--------|------|---------|
| 401 | `{"error":"API key required"}` | Header missing |
| 401 | `{"error":"Invalid API key"}` | Key not found or inactive |

---

## Base URL

```
https://<your-host>/api/v1
```

All endpoints below are relative to this base.

---

## Notes

### List notes

```
GET /notes
GET /notes?search=keyword
```

Returns a JSON array of note objects, ordered by most recently updated.

### Get a note

```
GET /notes/:id
```

### Create a note

```
POST /notes
Content-Type: application/json

{
  "title": "Shopping list",      // required
  "content": "Eggs, milk, bread" // optional
}
```

Returns `201` with the created note.

### Update a note

```
PUT /notes/:id
Content-Type: application/json

{
  "title": "Updated title",  // required
  "content": "New content"   // optional
}
```

### Delete a note

```
DELETE /notes/:id
```

---

## Todos

### List todos

```
GET /todos
GET /todos?search=keyword
GET /todos?completed=true
GET /todos?category=groceries
```

Returns a JSON array of todo objects.

### Get a todo

```
GET /todos/:id
```

### Create a todo

```
POST /todos
Content-Type: application/json

{
  "text": "Buy ingredients",  // required
  "completed": false,         // optional, defaults to false
  "category": "groceries"     // optional
}
```

Returns `201` with the created todo.

### Update a todo

```
PUT /todos/:id
Content-Type: application/json

{
  "text": "Updated text",
  "completed": true,
  "category": "groceries"
}
```

### Mark complete / incomplete

```
PATCH /todos/:id/complete
PATCH /todos/:id/incomplete
```

### Delete a todo

```
DELETE /todos/:id
```

---

## Recipes

Recipes support optional file attachments (images or PDFs). When you upload
an image (JPEG, PNG, WebP, HEIC), the server automatically converts it to
PDF before storing. This means the stored attachment is always a PDF regardless
of what format you uploaded.

### List recipes

```
GET /recipes
GET /recipes?search=keyword
```

Returns a JSON array of recipe objects, ordered by most recently updated.

### Get a recipe

```
GET /recipes/:id
```

### Create a recipe

```
POST /recipes
Content-Type: multipart/form-data

Fields:
  name    (string, required) -- the recipe name
  notes   (string, optional) -- free-text notes or description
  pdf     (file,   optional) -- an image or PDF file
```

Returns `201` with:

```json
{
  "id": "42",
  "name": "Chicken Curry",
  "notes": "Family recipe",
  "pdf_filename": "1709...-chicken-curry.pdf",
  "pdf_original_name": "chicken-curry.pdf"
}
```

### Update a recipe

```
PUT /recipes/:id
Content-Type: multipart/form-data

Fields:
  name        (string, required)
  notes       (string, optional)
  pdf         (file,   optional) -- replaces the existing file
  remove_pdf  ("true", optional) -- removes the existing file without replacement
```

### Delete a recipe

```
DELETE /recipes/:id
```

Returns `{"success": true}`. The attached file (if any) is also deleted.

---

## File Upload Details

| Setting | Value |
|---------|-------|
| Form field name | `pdf` |
| Max file size | 20 MB |
| Accepted types | `application/pdf`, `image/jpeg`, `image/png`, `image/webp`, `image/heic`, `image/heif` |

Images are converted to PDF server-side. The `pdf_original_name` in the
response reflects the converted filename (e.g. `photo.jpg` becomes `photo.pdf`).

---

## Examples

### Create a recipe with a photo

```bash
curl -X POST https://HOST/api/v1/recipes \
  -H "x-api-key: YOUR_KEY" \
  -F "name=Chicken Curry" \
  -F "notes=Mum's recipe, serves 4" \
  -F "pdf=@/path/to/recipe-photo.jpg"
```

### Create a recipe with a PDF

```bash
curl -X POST https://HOST/api/v1/recipes \
  -H "x-api-key: YOUR_KEY" \
  -F "name=Pasta Carbonara" \
  -F "pdf=@/path/to/recipe.pdf"
```

### Create a recipe without a file

```bash
curl -X POST https://HOST/api/v1/recipes \
  -H "x-api-key: YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"name": "Quick Omelette", "notes": "3 eggs, cheese, herbs"}'
```

### Update a recipe -- replace the file

```bash
curl -X PUT https://HOST/api/v1/recipes/42 \
  -H "x-api-key: YOUR_KEY" \
  -F "name=Chicken Curry (updated)" \
  -F "notes=Added more spice" \
  -F "pdf=@/path/to/better-photo.jpg"
```

### Remove a recipe's file

```bash
curl -X PUT https://HOST/api/v1/recipes/42 \
  -H "x-api-key: YOUR_KEY" \
  -F "name=Chicken Curry" \
  -F "remove_pdf=true"
```

### Create a note

```bash
curl -X POST https://HOST/api/v1/notes \
  -H "x-api-key: YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"title": "Meeting notes", "content": "Discuss Q2 roadmap"}'
```

### Create a todo

```bash
curl -X POST https://HOST/api/v1/todos \
  -H "x-api-key: YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"text": "Buy chicken for curry"}'
```

---

## Error Handling

All errors return JSON with an `error` field:

```json
{"error": "Recipe name is required"}
```

| Status | Meaning |
|--------|---------|
| 400 | Validation error (missing required field, invalid file type) |
| 401 | Authentication failed (missing or invalid API key) |
| 404 | Resource not found or not owned by the authenticated user |
| 500 | Internal server error |

---

## Response Conventions

| Operation | Status | Response shape |
|-----------|--------|----------------|
| Create | `201` | The created object |
| List | `200` | JSON array |
| Get | `200` | JSON object |
| Update | `200` | `{"success": true, ...updated fields}` |
| Delete | `200` | `{"success": true}` |
