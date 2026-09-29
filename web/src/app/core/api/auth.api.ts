import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';

import type { Observable } from 'rxjs';

import { API_BASE_URL, SKIP_AUTH, SKIP_AUTH_REDIRECT, SKIP_ERROR_TOAST } from '../http/http-utils';
import type { AuthResponse, LoginRequest, RegisterRequest, UserResponse } from '../models';

/** `/api/v1/auth/*`. Login/register render errors inline, so they skip the toast and the 401 redirect. */
@Injectable({ providedIn: 'root' })
export class AuthApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${inject(API_BASE_URL)}/api/v1/auth`;

  private tokenEndpoint(): HttpContext {
    return new HttpContext().set(SKIP_ERROR_TOAST, true).set(SKIP_AUTH_REDIRECT, true).set(SKIP_AUTH, true);
  }

  login(body: LoginRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.base}/login`, body, { context: this.tokenEndpoint() });
  }

  register(body: RegisterRequest): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.base}/register`, body, { context: this.tokenEndpoint() });
  }

  /** Exchanges (and rotates) the refresh token. Never carries a bearer token, never retried. */
  refresh(refreshToken: string): Observable<AuthResponse> {
    return this.http.post<AuthResponse>(`${this.base}/refresh`, { refreshToken }, { context: this.tokenEndpoint() });
  }

  /** Revokes that refresh token (204). */
  logout(refreshToken: string): Observable<void> {
    return this.http.post<void>(`${this.base}/logout`, { refreshToken }, { context: this.tokenEndpoint() });
  }

  me(): Observable<UserResponse> {
    return this.http.get<UserResponse>(`${this.base}/me`);
  }
}
