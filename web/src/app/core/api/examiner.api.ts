import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import { map, type Observable } from 'rxjs';

import { API_BASE_URL, toHttpParams, toPage } from '../http/http-utils';
import type { ApplicationSummary, ExaminerQueueQuery, ExaminerStats, Page } from '../models';

/** `/api/v1/examiner/*` (EXAMINER or ADMIN). */
@Injectable({ providedIn: 'root' })
export class ExaminerApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/api/v1/examiner`;

  /** Every filed application; default sort `filingDate,asc` (oldest first). */
  queue(query: ExaminerQueueQuery = {}): Observable<Page<ApplicationSummary>> {
    return this.http.get<Page<ApplicationSummary>>(`${this.base}/queue`, { params: toHttpParams(query) }).pipe(map((p) => toPage(p)));
  }

  stats(): Observable<ExaminerStats> {
    return this.http.get<ExaminerStats>(`${this.base}/stats`);
  }
}
