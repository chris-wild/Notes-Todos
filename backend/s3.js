const { S3Client, PutObjectCommand, DeleteObjectCommand, GetObjectCommand, HeadObjectCommand } = require('@aws-sdk/client-s3');
const crypto = require('crypto');

function boolish(v) {
  return v === '1' || v === 'true' || v === 'yes' || v === 'require';
}

function isEnabled() {
  return !!process.env.S3_BUCKET;
}

function getClient() {
  // Prefer ECS task role (no explicit keys). If you do set keys, SDK will pick them up from env.
  const region = process.env.AWS_REGION || process.env.AWS_DEFAULT_REGION || 'eu-west-2';
  return new S3Client({ region });
}

function sanitizeFilename(name) {
  return (name || 'file.pdf')
    .replace(/[^a-zA-Z0-9._-]+/g, '_')
    .slice(0, 120);
}

function makeKey({ userId, originalName }) {
  const safe = sanitizeFilename(originalName);
  const id = crypto.randomUUID();
  return `recipes/${userId}/${id}-${safe}`;
}

async function putPdf({ userId, buffer, contentType, originalName }) {
  const Bucket = process.env.S3_BUCKET;
  if (!Bucket) throw new Error('S3_BUCKET is not set');

  const Key = makeKey({ userId, originalName });
  const client = getClient();

  await client.send(
    new PutObjectCommand({
      Bucket,
      Key,
      Body: buffer,
      ContentType: contentType || 'application/pdf'
    })
  );

  return { key: Key };
}

async function deleteObject(key) {
  const Bucket = process.env.S3_BUCKET;
  if (!Bucket) throw new Error('S3_BUCKET is not set');
  const client = getClient();
  await client.send(new DeleteObjectCommand({ Bucket, Key: key }));
}

async function getPdfStream(key) {
  const Bucket = process.env.S3_BUCKET;
  if (!Bucket) throw new Error('S3_BUCKET is not set');
  const client = getClient();
  const out = await client.send(new GetObjectCommand({ Bucket, Key: key }));
  return out.Body; // Node.js readable stream
}

async function exists(key) {
  const Bucket = process.env.S3_BUCKET;
  if (!Bucket) throw new Error('S3_BUCKET is not set');
  const client = getClient();
  try {
    await client.send(new HeadObjectCommand({ Bucket, Key: key }));
    return true;
  } catch {
    return false;
  }
}

module.exports = { isEnabled, boolish, putPdf, deleteObject, getPdfStream, exists };
