const sharp = require('sharp');
const { PDFDocument } = require('pdf-lib');

const MAX_DIMENSION = 4000; // resize images larger than this (longest side)

const IMAGE_MIME_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/heic',
  'image/heif'
]);

function isImageMime(mime) {
  return IMAGE_MIME_TYPES.has(mime);
}

/**
 * Convert an image buffer (JPEG, PNG, WEBP, HEIC, etc.) to a single-page PDF.
 * The page is sized to match the image dimensions (in points, 1px = 1pt).
 */
async function convertImageToPdf(imageBuffer) {
  // Normalize to PNG via sharp (handles all input formats including HEIC)
  let pipeline = sharp(imageBuffer);
  const metadata = await pipeline.metadata();

  // Resize if too large
  const longest = Math.max(metadata.width || 0, metadata.height || 0);
  if (longest > MAX_DIMENSION) {
    pipeline = pipeline.resize({
      width: metadata.width >= metadata.height ? MAX_DIMENSION : undefined,
      height: metadata.height > metadata.width ? MAX_DIMENSION : undefined,
      fit: 'inside',
      withoutEnlargement: true
    });
  }

  const pngBuffer = await pipeline.png().toBuffer();
  const pngMeta = await sharp(pngBuffer).metadata();

  const width = pngMeta.width || 800;
  const height = pngMeta.height || 600;

  // Create PDF with a single page sized to the image
  const pdf = await PDFDocument.create();
  const pngImage = await pdf.embedPng(pngBuffer);
  const page = pdf.addPage([width, height]);
  page.drawImage(pngImage, { x: 0, y: 0, width, height });

  return Buffer.from(await pdf.save());
}

module.exports = { convertImageToPdf, isImageMime, IMAGE_MIME_TYPES };
