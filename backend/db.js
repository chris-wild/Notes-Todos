const { Pool } = require('pg');

const DATABASE_URL = process.env.DATABASE_URL;
if (!DATABASE_URL) {
  throw new Error('DATABASE_URL is required (e.g. postgres://user:pass@host:5432/db)');
}

const shouldUseSSL =
  process.env.PGSSL === '1' ||
  process.env.PGSSL === 'true' ||
  process.env.PGSSL === 'require';

const pool = new Pool({
  connectionString: DATABASE_URL,
  // Dev: self-signed cert bypass. Prod: PGSSL=true set in ECS taskdef
  // nosemgrep
  ssl: shouldUseSSL ? { rejectUnauthorized: false } : undefined
});

async function query(text, params) {
  return pool.query(text, params);
}

module.exports = {
  pool,
  query
};
