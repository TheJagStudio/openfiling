import { inject, Injectable, InjectionToken } from '@angular/core';

import { catchError, concat, EMPTY, filter, map, Observable, of, switchMap, takeWhile, throwError, timer } from 'rxjs';

import { environment } from '../../../environments/environment';
import { DocumentsApi } from '../api/documents.api';
import { toProblem } from '../http/problem';
import {
  type DocumentCode,
  type DocumentResponse,
  isRedactionActive,
  type UploadInstruction,
  type Uuid,
  type Visibility,
} from '../models';

/** Factory for the XHR used for pre-signed PUTs (swapped for a mock in tests). */
export const XHR_FACTORY = new InjectionToken<() => XMLHttpRequest>('XHR_FACTORY', {
  providedIn: 'root',
  factory: () => () => new XMLHttpRequest(),
});

/** Poll interval for `GET /documents/{id}` while redaction is PENDING/PROCESSING. */
export const REDACTION_POLL_MS = new InjectionToken<number>('REDACTION_POLL_MS', {
  providedIn: 'root',
  factory: () => environment.redactionPollMs,
});

export type UploadPhase = 'create' | 'put' | 'complete';

/** A failed upload step, with a message that is safe to show to the user. */
export class UploadError extends Error {
  constructor(
    message: string,
    readonly phase: UploadPhase,
    readonly status?: number,
    readonly code?: string,
  ) {
    super(message);
    this.name = 'UploadError';
  }
}

export interface PutProgress {
  type: 'progress';
  loaded: number;
  total: number;
  percent: number;
}

export interface PutDone {
  type: 'done';
  etag: string | null;
}

export type DirectUploadEvent =
  | { type: 'created'; document: DocumentResponse }
  | PutProgress
  | { type: 'verifying' }
  | { type: 'completed'; document: DocumentResponse };

export interface DirectUploadMeta {
  docCode: DocumentCode;
  visibility?: Visibility;
  description?: string;
}

/** Headers the browser forbids scripts from setting — the XHR computes them itself. */
const FORBIDDEN_HEADERS = new Set(['host', 'content-length', 'connection', 'accept-encoding', 'origin', 'referer']);

/** Extracts `<Code>` / `<Message>` from an S3 XML error body. */
export function parseS3Error(body: string | null | undefined): { code: string | null; message: string | null } {
  if (!body) return { code: null, message: null };
  const pick = (tag: string) => {
    const m = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(body);
    return m?.[1]?.trim() || null;
  };
  return { code: pick('Code'), message: pick('Message') };
}

/** Readable message for a failed pre-signed PUT (S3 status + XML error body). */
export function describeS3Error(status: number, body: string | null | undefined): { message: string; code: string | null } {
  const { code, message } = parseS3Error(body);
  switch (code) {
    case 'AccessDenied':
      return {
        code,
        message: /expired/i.test(message ?? '')
          ? 'The secure upload link expired before the transfer finished. Retry to get a fresh link.'
          : 'Storage refused the upload (access denied). Retry to get a fresh upload link.',
      };
    case 'SignatureDoesNotMatch':
      return { code, message: 'The upload link signature was rejected by storage. Retry to request a new link.' };
    case 'InvalidAccessKeyId':
      return { code, message: 'Storage does not recognise the upload credentials. Please contact support.' };
    case 'EntityTooLarge':
      return { code, message: 'The file is larger than storage allows.' };
    case 'NoSuchBucket':
      return { code, message: 'The document storage bucket is unavailable. Please try again later.' };
    case 'NotImplemented':
      return { code, message: 'Storage does not support this upload mode.' };
    case 'SlowDown':
    case 'ServiceUnavailable':
    case 'InternalError':
      return { code, message: 'Storage is temporarily unavailable. Please retry in a moment.' };
  }
  if (code) return { code, message: message ? `${message} (${code})` : `Storage error ${code}.` };
  if (status >= 500) return { code: null, message: `Storage is temporarily unavailable (HTTP ${status}). Please retry.` };
  return { code: null, message: `Storage rejected the upload (HTTP ${status}).` };
}

function problemMessage(err: unknown, fallback: string): { message: string; status?: number } {
  const p = toProblem(err);
  const first = p.errors?.[0]?.message;
  return { message: first ?? p.detail ?? p.title ?? fallback, status: p.status };
}

/**
 * Direct-to-S3 upload flow (the API never sees file bytes):
 *
 * 1. `POST /applications/{id}/documents` → document (PENDING_UPLOAD) + pre-signed PUT
 * 2. `XMLHttpRequest` PUT of the file to the pre-signed URL with **exactly** the returned headers
 *    (never an `Authorization` header), reporting progress; unsubscribing aborts the transfer
 * 3. `POST /documents/{id}/complete` → UPLOADED (or 422 REJECTED)
 * 4. optionally `pollDocument()` every 3 s while the redaction is PENDING/PROCESSING
 */
@Injectable({ providedIn: 'root' })
export class DirectUploadService {
  private readonly api = inject(DocumentsApi);
  private readonly xhrFactory = inject(XHR_FACTORY);
  private readonly pollMs = inject(REDACTION_POLL_MS);

  upload(applicationId: Uuid, file: File, meta: DirectUploadMeta): Observable<DirectUploadEvent> {
    const body = {
      docCode: meta.docCode,
      fileName: file.name,
      contentType: 'application/pdf',
      sizeBytes: file.size,
      ...(meta.visibility ? { visibility: meta.visibility } : {}),
      ...(meta.description?.trim() ? { description: meta.description.trim() } : {}),
    };
    return this.api.create(applicationId, body).pipe(
      catchError((err: unknown) => {
        const { message, status } = problemMessage(err, 'The upload could not be started.');
        return throwError(() => new UploadError(message, 'create', status));
      }),
      switchMap(({ document, upload }) =>
        concat(
          of<DirectUploadEvent>({ type: 'created', document }),
          this.put(upload, file).pipe(filter((e): e is PutProgress => e.type === 'progress')),
          of<DirectUploadEvent>({ type: 'verifying' }),
          this.api.complete(document.id).pipe(
            map((d): DirectUploadEvent => ({ type: 'completed', document: d })),
            catchError((err: unknown) => {
              const { message, status } = problemMessage(err, 'The uploaded file could not be verified.');
              return throwError(() => new UploadError(message, 'complete', status));
            }),
          ),
        ),
      ),
    );
  }

  /** PUTs `file` to a pre-signed URL. Emits progress, then `done`. Unsubscribe → `xhr.abort()`. */
  put(instruction: UploadInstruction, file: Blob): Observable<PutProgress | PutDone> {
    return new Observable<PutProgress | PutDone>((subscriber) => {
      const xhr = this.xhrFactory();
      let finished = false;
      xhr.open(instruction.method || 'PUT', instruction.url, true);
      for (const [name, value] of Object.entries(instruction.headers ?? {})) {
        if (!FORBIDDEN_HEADERS.has(name.toLowerCase())) xhr.setRequestHeader(name, value);
      }
      xhr.upload.onprogress = (e: ProgressEvent) => {
        const total = e.lengthComputable && e.total > 0 ? e.total : file.size;
        const percent = total > 0 ? Math.min(100, Math.round((e.loaded / total) * 100)) : 0;
        subscriber.next({ type: 'progress', loaded: e.loaded, total, percent });
      };
      xhr.onload = () => {
        finished = true;
        if (xhr.status >= 200 && xhr.status < 300) {
          subscriber.next({ type: 'progress', loaded: file.size, total: file.size, percent: 100 });
          subscriber.next({ type: 'done', etag: xhr.getResponseHeader('ETag') });
          subscriber.complete();
        } else {
          const { message, code } = describeS3Error(xhr.status, xhr.responseText);
          subscriber.error(new UploadError(message, 'put', xhr.status, code ?? undefined));
        }
      };
      xhr.onerror = () => {
        finished = true;
        subscriber.error(
          new UploadError('Network error while uploading to storage. Check your connection (or the storage CORS settings) and retry.', 'put', 0),
        );
      };
      xhr.ontimeout = () => {
        finished = true;
        subscriber.error(new UploadError('The upload timed out. Please retry.', 'put', 0));
      };
      xhr.send(file);
      return () => {
        if (!finished) xhr.abort();
      };
    });
  }

  /** Polls the document every 3 s while its redaction is PENDING/PROCESSING; completes after the final state. */
  pollDocument(documentId: Uuid): Observable<DocumentResponse> {
    return timer(this.pollMs, this.pollMs).pipe(
      switchMap(() => this.api.get(documentId, true)),
      takeWhile((d) => isRedactionActive(d), true),
      catchError(() => EMPTY),
    );
  }
}
