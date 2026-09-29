import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import { map, type Observable } from 'rxjs';

import { API_BASE_URL, SKIP_ERROR_TOAST, toHttpParams, toPage } from '../http/http-utils';
import type { AdminUser, AdminUserQuery, AuditEvent, AuditQuery, CreateUserRequest, Page, SystemStatus, UpdateUserRequest, Uuid } from '../models';

/** `/api/v1/admin/*` (ADMIN). */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/api/v1/admin`;

  users(query: AdminUserQuery = {}): Observable<Page<AdminUser>> {
    return this.http.get<Page<AdminUser>>(`${this.base}/users`, { params: toHttpParams(query) }).pipe(map((p) => toPage(p)));
  }

  /** Errors (409 email taken, 400 validation) rendered inline by the dialog. */
  createUser(body: CreateUserRequest): Observable<AdminUser> {
    return this.http.post<AdminUser>(`${this.base}/users`, body, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) });
  }

  /** 422 when removing your own ADMIN role or disabling yourself (rendered inline). */
  updateUser(id: Uuid, body: UpdateUserRequest): Observable<AdminUser> {
    return this.http.patch<AdminUser>(`${this.base}/users/${encodeURIComponent(id)}`, body, {
      context: new HttpContext().set(SKIP_ERROR_TOAST, true),
    });
  }

  audit(query: AuditQuery = {}): Observable<Page<AuditEvent>> {
    return this.http.get<Page<AuditEvent>>(`${this.base}/audit`, { params: toHttpParams(query) }).pipe(map((p) => toPage(p)));
  }

  /** Polled every 15 s — errors are rendered inline. */
  system(): Observable<SystemStatus> {
    return this.http.get<SystemStatus>(`${this.base}/system`, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) });
  }
}
