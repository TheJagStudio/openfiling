import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import { map, type Observable } from 'rxjs';

import { API_BASE_URL, SKIP_ERROR_TOAST, toHttpParams, toPage } from '../http/http-utils';
import type { DownloadResponse, Page, PublicApplicationDetail, PublicApplicationSummary, PublicSearchQuery, PublicStats, Uuid } from '../models';

/** `/api/v1/public/*` — anonymous; published applications and sanitized public copies only. */
@Injectable({ providedIn: 'root' })
export class PublicApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/api/v1/public`;

  search(query: PublicSearchQuery = {}): Observable<Page<PublicApplicationSummary>> {
    return this.http
      .get<Page<PublicApplicationSummary>>(`${this.base}/applications`, { params: toHttpParams(query) })
      .pipe(map((p) => toPage(p)));
  }

  /** Accepts `19000123` or `19/000,123`; 404 unless published (rendered inline). */
  application(applicationNumber: string): Observable<PublicApplicationDetail> {
    return this.http.get<PublicApplicationDetail>(`${this.base}/applications/${encodeURIComponent(applicationNumber)}`, {
      context: new HttpContext().set(SKIP_ERROR_TOAST, true),
    });
  }

  downloadDocument(documentId: Uuid): Observable<DownloadResponse> {
    return this.http.get<DownloadResponse>(`${this.base}/documents/${encodeURIComponent(documentId)}/download`);
  }

  stats(): Observable<PublicStats> {
    return this.http.get<PublicStats>(`${this.base}/stats`, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) });
  }
}
