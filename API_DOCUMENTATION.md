# REST API Documentation

## Overview

This API provides CRUD operations for notes and todos with multi-user support. Each user's data is isolated -- API keys can only access data belonging to the user who created them.

**Base URL:** `http://localhost:3001/api`

## Authentication

There are two authentication methods:

### 1. JWT Token (Web UI endpoints)

All `/api/*` endpoints (except `/api/login` and `/api/register`) require a JWT token:

```
Authorization: Bearer YOUR_JWT_TOKEN
```

Obtain a token by calling `POST /api/login` or `POST /api/register`.

### 2. API Key (External API endpoints)

All `/api/v1/*` endpoints require an API key:

```
x-api-key: YOUR_API_KEY
```

Create API keys from the Account tab in the web UI, or via `POST /api/keys` with a JWT token.

API keys are scoped to the user who created them. All data access through an API key is limited to that user's notes and todos.

---

## Auth Endpoints

### Register

```bash
POST /api/register
Content-Type: application/json

{
  "username": "myuser",
  "password": "mypassword"
}
```

**Required fields:**
- `username` (string, min 3 characters)
- `password` (string, min 6 characters)

**Response (201 Created):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "username": "myuser"
}
```

### Login

```bash
POST /api/login
Content-Type: application/json

{
  "username": "myuser",
  "password": "mypassword"
}
```

**Response:**
```json
{
  "token": "eyJhbGciOiJIUzI1NiIs...",
  "username": "myuser"
}
```

### Change Password

```bash
POST /api/reset-password
Authorization: Bearer YOUR_JWT_TOKEN
Content-Type: application/json

{
  "currentPassword": "oldpassword",
  "newPassword": "newpassword"
}
```

**Required fields:**
- `currentPassword` (string)
- `newPassword` (string, min 6 characters)

**Response:**
```json
{
  "success": true,
  "message": "Password updated successfully"
}
```

---

## API Key Management

All key management endpoints require JWT authentication.

### List your API keys

```bash
GET /api/keys
Authorization: Bearer YOUR_JWT_TOKEN
```

**Response:**
```json
[
  {
    "id": 1,
    "key": "abc123...",
    "name": "CLI script",
    "active": 1,
    "created_at": "2024-01-01 12:00:00"
  }
]
```

### Create new API key

```bash
POST /api/keys
Authorization: Bearer YOUR_JWT_TOKEN
Content-Type: application/json

{
  "name": "My New API Key"
}
```

**Optional fields:**
- `name` (string, defaults to "Unnamed Key")

**Response (201 Created):**
```json
{
  "id": 2,
  "key": "def456...",
  "name": "My New API Key"
}
```

### Delete API key

```bash
DELETE /api/keys/:id
Authorization: Bearer YOUR_JWT_TOKEN
```

**Response:**
```json
{
  "success": true
}
```

---

## Notes API

### List all notes

```bash
GET /api/v1/notes
x-api-key: YOUR_API_KEY
```

**Query Parameters:**
- `search` (optional) - Search in title and content

**Example:**
```bash
curl -H "x-api-key: YOUR_API_KEY" \
  "http://localhost:3001/api/v1/notes?search=meeting"
```

**Response:**
```json
[
  {
    "id": 1,
    "title": "Meeting Notes",
    "content": "Discussed project timeline...",
    "user_id": 1,
    "created_at": "2024-01-01 10:00:00",
    "updated_at": "2024-01-01 10:00:00"
  }
]
```

### Get single note

```bash
GET /api/v1/notes/:id
x-api-key: YOUR_API_KEY
```

**Example:**
```bash
curl -H "x-api-key: YOUR_API_KEY" \
  http://localhost:3001/api/v1/notes/1
```

### Create note

```bash
POST /api/v1/notes
x-api-key: YOUR_API_KEY
Content-Type: application/json

{
  "title": "New Note",
  "content": "Note content here..."
}
```

**Required fields:**
- `title` (string)

**Optional fields:**
- `content` (string)

**Response (201 Created):**
```json
{
  "id": 2,
  "title": "New Note",
  "content": "Note content here...",
  "created_at": "2024-01-01T12:00:00.000Z",
  "updated_at": "2024-01-01T12:00:00.000Z"
}
```

### Update note

```bash
PUT /api/v1/notes/:id
x-api-key: YOUR_API_KEY
Content-Type: application/json

{
  "title": "Updated Title",
  "content": "Updated content..."
}
```

**Required fields:**
- `title` (string)

**Response:**
```json
{
  "success": true,
  "id": "1",
  "title": "Updated Title",
  "content": "Updated content..."
}
```

### Delete note

```bash
DELETE /api/v1/notes/:id
x-api-key: YOUR_API_KEY
```

**Response:**
```json
{
  "success": true
}
```

---

## Todos API

### List all todos

```bash
GET /api/v1/todos
x-api-key: YOUR_API_KEY
```

**Query Parameters:**
- `search` (optional) - Search in todo text
- `completed` (optional) - Filter by completion status (`true` or `false`)

**Examples:**
```bash
# All todos
curl -H "x-api-key: YOUR_API_KEY" \
  http://localhost:3001/api/v1/todos

# Only completed todos
curl -H "x-api-key: YOUR_API_KEY" \
  "http://localhost:3001/api/v1/todos?completed=true"

# Search todos
curl -H "x-api-key: YOUR_API_KEY" \
  "http://localhost:3001/api/v1/todos?search=groceries"
```

**Response:**
```json
[
  {
    "id": 1,
    "text": "Buy groceries",
    "completed": 0,
    "user_id": 1,
    "created_at": "2024-01-01 10:00:00"
  }
]
```

### Get single todo

```bash
GET /api/v1/todos/:id
x-api-key: YOUR_API_KEY
```

### Create todo

```bash
POST /api/v1/todos
x-api-key: YOUR_API_KEY
Content-Type: application/json

{
  "text": "New todo item",
  "completed": false
}
```

**Required fields:**
- `text` (string)

**Optional fields:**
- `completed` (boolean, defaults to false)

**Response (201 Created):**
```json
{
  "id": 3,
  "text": "New todo item",
  "completed": false,
  "created_at": "2024-01-01T12:00:00.000Z"
}
```

### Update todo

```bash
PUT /api/v1/todos/:id
x-api-key: YOUR_API_KEY
Content-Type: application/json

{
  "text": "Updated text",
  "completed": true
}
```

**Optional fields:**
- `text` (string)
- `completed` (boolean)

### Mark todo as complete

```bash
PATCH /api/v1/todos/:id/complete
x-api-key: YOUR_API_KEY
```

### Mark todo as incomplete

```bash
PATCH /api/v1/todos/:id/incomplete
x-api-key: YOUR_API_KEY
```

### Delete todo

```bash
DELETE /api/v1/todos/:id
x-api-key: YOUR_API_KEY
```

---

## Error Responses

All endpoints return errors in this format:

```json
{
  "error": "Error message here"
}
```

**Common HTTP status codes:**
- `200` - Success
- `201` - Created
- `400` - Bad Request (validation error)
- `401` - Unauthorized (invalid/missing token or API key)
- `404` - Not Found
- `409` - Conflict (e.g., username already taken)
- `500` - Internal Server Error

---

## Testing Examples

### Using curl

```bash
# Register a user
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass123"}' \
  http://localhost:3001/api/register

# Login and save token
TOKEN=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass123"}' \
  http://localhost:3001/api/login | jq -r '.token')

# Create an API key
API_KEY=$(curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Test Key"}' \
  http://localhost:3001/api/keys | jq -r '.key')

# Use the API key
curl -H "x-api-key: $API_KEY" \
  http://localhost:3001/api/v1/notes
```

---

## Important Notes

1. **User isolation**: Each user can only see and modify their own notes, todos, and API keys. This applies to both the web UI and the REST API.

2. **API Key Security**:
   - Keep your API keys secret
   - Don't commit them to version control
   - Rotate keys regularly
   - Delete unused keys from the Account tab

3. **JWT Tokens**: Tokens expire after 7 days. Set the `JWT_SECRET` environment variable to persist tokens across server restarts.

4. **Rate Limiting**: Currently no rate limiting is implemented. Consider adding it for production use.

5. **CORS**: The server accepts requests from any origin in development. Configure this appropriately for production.
