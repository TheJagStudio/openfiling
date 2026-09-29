import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import { map, type Observable } from 'rxjs';

import { API_BASE_URL, SKIP_ERROR_TOAST, toHttpParams } from '../http/http-utils';
import type { CreateDocumentRequest, CreateDocumentResponse, DocumentResponse, DownloadCopy, DownloadResponse, Uuid } from '../models';

const inline = () => new HttpContext().set(SKIP_ERROR_TOAST, true);

/** Document metadata + pre-signed URL issuance. File bytes never go through the API. */
@Injectable({ providedIn: 'root' })
export class DocumentsApi {
  private readonly http = inject(HttpClient);
  private readonly root = `${inject(API_BASE_URL)}/api/v1`;

  list(applicationId: Uuid): Observable<DocumentResponse[]> {
    return this.http
      .get<DocumentResponse[]>(`${this.root}/applications/${encodeURIComponent(applicationId)}/documents`)
      .pipe(map((d) => d ?? []));
  }

  /** → 201 `{ document, upload }` with a pre-signed PUT (errors shown per file by the upload queue). */
  create(applicationId: Uuid, body: CreateDocumentRequest): Observable<CreateDocumentResponse> {
    return this.http.post<CreateDocumentResponse>(`${this.root}/applications/${encodeURIComponent(applicationId)}/documents`, body, {
      context: inline(),
    });
  }

  /** Server verifies size + `%PDF-` magic; 422 + REJECTED otherwise. Idempotent. */
  complete(documentId: Uuid): Observable<DocumentResponse> {
    return this.http.post<DocumentResponse>(`${this.root}/documents/${encodeURIComponent(documentId)}/complete`, null, {
      context: inline(),
    });
  }

  get(documentId: Uuid, quiet = false): Observable<DocumentResponse> {
    return this.http.get<DocumentResponse>(`${this.root}/documents/${encodeURIComponent(documentId)}`, {
      context: new HttpContext().set(SKIP_ERROR_TOAST, quiet),
    });
  }

  /** Pre-signed GET (5 min) for the original or the sanitized public copy. */
  download(documentId: Uuid, copy: DownloadCopy = 'original'): Observable<DownloadResponse> {
    return this.http.get<DownloadResponse>(`${this.root}/documents/${encodeURIComponent(documentId)}/download`, {
      params: toHttpParams({ copy }),
    });
  }

  remove(documentId: Uuid): Observable<void> {
    return this.http.delete<void>(`${this.root}/documents/${encodeURIComponent(documentId)}`);
  }

  /** Examiner: re-invokes the redaction Lambda. */
  retryRedaction(documentId: Uuid): Observable<DocumentResponse> {
    return this.http.post<DocumentResponse>(`${this.root}/documents/${encodeURIComponent(documentId)}/redaction/retry`, null);
  }
}
