# Notes & Todos

A client-only native Android app for notes, todos, and recipes. All data lives on the
device — Room/SQLite plus app-private files — with no server component at all. The only
network call the app ever makes is to `api.anthropic.com` for the optional
recipe-ingredient extraction feature, using an API key stored in the Android Keystore.

The app lives in [`android/`](android/README.md) — see that README for the stack,
build instructions, and backup model.

## History

This project began as a React SPA with an Express backend, deployed on AWS
(CloudFront + Lambda + S3), reachable at todo.promptbuilt.co.uk. In September 2026 it
was rewritten as this native Android app, the production data was migrated onto the
device, and the entire AWS stack was decommissioned. The web-era code was retired in
the same change that introduced this README; it remains in git history.

## Repo layout

- `android/` — the app (Kotlin / Jetpack Compose / Room)
- `scripts/build_android_import.py` — one-time converter from the raw S3 data export
  to the app's backup/import zip format (kept for reference; the S3 bucket no longer exists)
- `test_data/Recipe.jpg` — fixture for manually testing the image→PDF attachment path
- `export/` (gitignored, local only) — the final archive of the server-era data
