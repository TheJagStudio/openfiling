import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import type { Observable } from 'rxjs';

import { API_BASE_URL, SKIP_ERROR_TOAST } from '../http/http-utils';
import type { ChangePasswordRequest, SessionResponse, UpdateProfileRequest, UserResponse, Uuid } from '../models';

/** `/api/v1/account/*` — profile, password and active sessions (refresh-token families). */
@Injectable({ providedIn: 'root' })
export class AccountApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/api/v1/account`;

  updateProfile(body: UpdateProfileRequest): Observable<UserResponse> {
    return this.http.patch<UserResponse>(`${this.base}/profile`, body, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) });
  }

  /** 204; the server revokes every refresh token of the user. */
  changePassword(body: ChangePasswordRequest): Observable<void> {
    return this.http.post<void>(`${this.base}/password`, body, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) });
  }

  sessions(): Observable<SessionResponse[]> {
    return this.http.get<SessionResponse[]>(`${this.base}/sessions`);
  }

  revokeSession(id: Uuid): Observable<void> {
    return this.http.delete<void>(`${this.base}/sessions/${encodeURIComponent(id)}`);
  }

  revokeAll(): Observable<void> {
    return this.http.post<void>(`${this.base}/sessions/revoke-all`, null);
  }
}
