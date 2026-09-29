import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import { map, type Observable } from 'rxjs';

import { API_BASE_URL, SKIP_ERROR_TOAST, toHttpParams, toPage } from '../http/http-utils';
import type {
  ApplicationDetail,
  ApplicationQuery,
  ApplicationSummary,
  CreateApplicationRequest,
  DashboardResponse,
  FilingReceipt,
  Page,
  StatusEventResponse,
  SubmitApplicationRequest,
  UpdateApplicationRequest,
  Uuid,
  WorkflowActionRequest,
} from '../models';

const inline = () => new HttpContext().set(SKIP_ERROR_TOAST, true);

/** `/api/v1/applications/*` and `/api/v1/dashboard`. */
@Injectable({ providedIn: 'root' })
export class ApplicationsApi {
  private readonly http = inject(HttpClient);
  private readonly root = inject(API_BASE_URL);
  private readonly base = `${this.root}/api/v1/applications`;

  private url(id: Uuid, suffix = ''): string {
    return `${this.base}/${encodeURIComponent(id)}${suffix}`;
  }

  /** Caller's own applications. `status` is a comma-separated list. */
  list(query: ApplicationQuery = {}): Observable<Page<ApplicationSummary>> {
    return this.http.get<Page<ApplicationSummary>>(this.base, { params: toHttpParams(query) }).pipe(map((p) => toPage(p)));
  }

  /** → 201 DRAFT. Validation errors are rendered inline by the wizard. */
  create(body: CreateApplicationRequest): Observable<ApplicationDetail> {
    return this.http.post<ApplicationDetail>(this.base, body, { context: inline() });
  }

  /** 404 is rendered inline by the detail page. */
  get(id: Uuid): Observable<ApplicationDetail> {
    return this.http.get<ApplicationDetail>(this.url(id), { context: inline() });
  }

  /** 409 on a stale `version` (rendered inline). */
  update(id: Uuid, body: UpdateApplicationRequest): Observable<ApplicationDetail> {
    return this.http.put<ApplicationDetail>(this.url(id), body, { context: inline() });
  }

  remove(id: Uuid): Observable<void> {
    return this.http.delete<void>(this.url(id));
  }

  /** DRAFT → SUBMITTED; 422 with `errors` when incomplete (rendered inline). */
  submit(id: Uuid, body: SubmitApplicationRequest): Observable<ApplicationDetail> {
    return this.http.post<ApplicationDetail>(this.url(id, '/submit'), body, { context: inline() });
  }

  /** Workflow transition; 403 / 409 / 422 rendered inline by the action dialog. */
  action(id: Uuid, body: WorkflowActionRequest): Observable<ApplicationDetail> {
    return this.http.post<ApplicationDetail>(this.url(id, '/actions'), body, { context: inline() });
  }

  history(id: Uuid): Observable<StatusEventResponse[]> {
    return this.http.get<StatusEventResponse[]>(this.url(id, '/history')).pipe(map((h) => h ?? []));
  }

  /** 409 if not filed (rendered inline). */
  receipt(id: Uuid): Observable<FilingReceipt> {
    return this.http.get<FilingReceipt>(this.url(id, '/receipt'), { context: inline() });
  }

  dashboard(): Observable<DashboardResponse> {
    return this.http.get<DashboardResponse>(`${this.root}/api/v1/dashboard`);
  }
}
