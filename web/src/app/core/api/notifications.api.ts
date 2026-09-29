import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import { map, type Observable } from 'rxjs';

import { API_BASE_URL, SKIP_ERROR_TOAST, toHttpParams, toPage } from '../http/http-utils';
import type { NotificationQuery, NotificationResponse, Page, UnreadCount, Uuid } from '../models';

@Injectable({ providedIn: 'root' })
export class NotificationsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/api/v1/notifications`;

  list(query: NotificationQuery = {}, quiet = false): Observable<Page<NotificationResponse>> {
    return this.http
      .get<Page<NotificationResponse>>(this.base, { params: toHttpParams(query), context: new HttpContext().set(SKIP_ERROR_TOAST, quiet) })
      .pipe(map((p) => toPage(p)));
  }

  /** Background poll — never toasts. */
  unreadCount(): Observable<number> {
    return this.http
      .get<UnreadCount>(`${this.base}/unread-count`, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) })
      .pipe(map((r) => Math.max(0, Math.trunc(r?.count ?? 0))));
  }

  markRead(id: Uuid): Observable<void> {
    return this.http.post<void>(`${this.base}/${encodeURIComponent(id)}/read`, null);
  }

  markAllRead(): Observable<void> {
    return this.http.post<void>(`${this.base}/read-all`, null);
  }
}
