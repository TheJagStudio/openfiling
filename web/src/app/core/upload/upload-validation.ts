import { formatBytes } from '../format';

export interface UploadLimits {
  maxUploadBytes: number;
  allowedContentTypes: readonly string[];
}

/** The first five bytes of every PDF. The server re-checks this with a ranged GET on `/complete`. */
export const PDF_MAGIC = '%PDF-';

export interface FileLike {
  name: string;
  size: number;
  type: string;
}

/**
 * Synchronous checks mirroring the contract: PDF only (`application/pdf`, or an empty browser MIME
 * type), name ends `.pdf`, not empty, size ≤ `limits.maxUploadBytes`. Returns an error message or null.
 */
export function checkPdfMeta(file: FileLike, limits: UploadLimits): string | null {
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    return 'Only PDF files are accepted (the file name must end in .pdf).';
  }
  const allowed = limits.allowedContentTypes.length ? limits.allowedContentTypes : ['application/pdf'];
  // Some OSes report no MIME type for PDFs; the name + magic-byte checks still apply.
  if (file.type && !allowed.includes(file.type)) {
    return `This file is ${file.type}, not a PDF.`;
  }
  if (file.size <= 0) {
    return 'The file is empty.';
  }
  if (file.size > limits.maxUploadBytes) {
    return `${formatBytes(file.size)} exceeds the ${formatBytes(limits.maxUploadBytes)} upload limit.`;
  }
  return null;
}

/** Reads the first `n` bytes of a Blob (uses `Blob.arrayBuffer`, falling back to `FileReader`). */
export function readHead(blob: Blob, n = PDF_MAGIC.length): Promise<Uint8Array> {
  const slice = blob.slice(0, n);
  if (typeof slice.arrayBuffer === 'function') {
    return slice.arrayBuffer().then((buf) => new Uint8Array(buf));
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.onerror = () => reject(reader.error ?? new Error('Could not read file'));
    reader.readAsArrayBuffer(slice);
  });
}

export function hasPdfMagic(head: Uint8Array): boolean {
  if (head.length < PDF_MAGIC.length) return false;
  for (let i = 0; i < PDF_MAGIC.length; i++) {
    if (head[i] !== PDF_MAGIC.charCodeAt(i)) return false;
  }
  return true;
}

/** Full client-side validation: metadata checks, then `File.slice(0, 5)` must be `%PDF-`. */
export async function validatePdf(file: File, limits: UploadLimits): Promise<string | null> {
  const meta = checkPdfMeta(file, limits);
  if (meta) return meta;
  try {
    const head = await readHead(file);
    return hasPdfMagic(head) ? null : 'This file does not look like a PDF (missing the %PDF- header).';
  } catch {
    return 'The file could not be read. Check that it is not open in another program.';
  }
}
