import type { DownloadResponse } from '../core/models';

/**
 * Starts a browser download from a pre-signed GET URL. The URL already carries
 * `response-content-disposition=attachment`, so storage answers with a download (no new tab, no
 * bearer token — the signature is the credential).
 */
export function startDownload(res: DownloadResponse, doc: Document = document): void {
  const a = doc.createElement('a');
  a.href = res.url;
  a.download = res.fileName || '';
  a.rel = 'noopener';
  a.style.display = 'none';
  doc.body.appendChild(a);
  a.click();
  a.remove();
}

/** Saves a Blob as a file via a temporary object URL. */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
