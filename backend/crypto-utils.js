const crypto = require('crypto');

const ALGORITHM = 'aes-256-gcm';
const IV_LENGTH = 12;
const AUTH_TAG_LENGTH = 16;
const ENC_PREFIX = 'enc:';

let _encryptionKey = null;

/**
 * Derive a 32-byte encryption key from ENCRYPTION_KEY env var,
 * or fall back to deriving from JWT_SECRET via HKDF.
 */
function getEncryptionKey() {
  if (_encryptionKey) return _encryptionKey;

  const raw = (process.env.ENCRYPTION_KEY || '').trim();
  if (raw) {
    _encryptionKey = crypto.hkdfSync('sha256', raw, '', 'notes-todos-encryption', 32);
    return _encryptionKey;
  }

  const jwtSecret = (process.env.JWT_SECRET || '').trim();
  if (jwtSecret) {
    _encryptionKey = crypto.hkdfSync('sha256', jwtSecret, 'key-encryption-salt', 'notes-todos-encryption', 32);
    return _encryptionKey;
  }

  throw new Error('Neither ENCRYPTION_KEY nor JWT_SECRET is set — cannot encrypt keys');
}

/**
 * Encrypt plaintext → "enc:<iv>:<authTag>:<ciphertext>" (base64 parts)
 */
function encrypt(plaintext) {
  const key = getEncryptionKey();
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, Buffer.from(key), iv, { authTagLength: AUTH_TAG_LENGTH });

  let encrypted = cipher.update(plaintext, 'utf8');
  encrypted = Buffer.concat([encrypted, cipher.final()]);
  const authTag = cipher.getAuthTag();

  return ENC_PREFIX + [
    iv.toString('base64'),
    authTag.toString('base64'),
    encrypted.toString('base64')
  ].join(':');
}

/**
 * Decrypt a stored value. If it doesn't start with "enc:", treat as legacy
 * plaintext and return as-is (for migration compatibility).
 */
function decrypt(stored) {
  if (!stored) return stored;
  if (!stored.startsWith(ENC_PREFIX)) return stored; // legacy plaintext

  const key = getEncryptionKey();
  const parts = stored.slice(ENC_PREFIX.length).split(':');
  if (parts.length !== 3) throw new Error('Malformed encrypted value');

  const iv = Buffer.from(parts[0], 'base64');
  const authTag = Buffer.from(parts[1], 'base64');
  const ciphertext = Buffer.from(parts[2], 'base64');

  const decipher = crypto.createDecipheriv(ALGORITHM, Buffer.from(key), iv, { authTagLength: AUTH_TAG_LENGTH });
  decipher.setAuthTag(authTag);

  let decrypted = decipher.update(ciphertext);
  decrypted = Buffer.concat([decrypted, decipher.final()]);
  return decrypted.toString('utf8');
}

/**
 * Deterministic HMAC-SHA256 hash for fast DB lookups.
 */
function hmacHash(value) {
  const key = getEncryptionKey();
  return crypto.createHmac('sha256', Buffer.from(key)).update(value).digest('hex');
}

module.exports = { encrypt, decrypt, hmacHash };
