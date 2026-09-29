import { HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, effect, inject, Injectable, Injector } from '@angular/core';
import { Router } from '@angular/router';

import { catchError, finalize, map, type Observable, shareReplay, tap, throwError } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuthApi } from '../api/auth.api';
import { Notifier } from '../notify/notifier.service';
import { AuthStore, NOW } from './auth.store';
import { isPublicUrl } from './public-routes';

/** Lead time before access-token expiry at which the proactive refresh fires. */
export const REFRESH_LEAD_MS = environment.refreshLeadMs;

/**
 * Token lifecycle around {@link AuthStore}:
 *
 * - `refresh()` — **single-flight** exchange of the refresh token (`POST /auth/refresh`). Concurrent
 *   callers share one request; a rejected refresh (400/401/403: the token was rotated, reused or
 *   expired) signs the user out locally.
 * - `start()` — proactively refreshes shortly before the access token expires, so active users
 *   never hit a 401 in the first place.
 * - `logout()` — best-effort server revoke of the refresh token, then local sign-out.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly auth = inject(AuthStore);
  private readonly api = inject(AuthApi);
  private readonly router = inject(Router);
  private readonly notifier = inject(Notifier);
  private readonly now = inject(NOW);
  private readonly injector = inject(Injector);

  private inflight: Observable<string> | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private started = false;
  /** Visible for tests. */
  jitterMs = 5000;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.clearTimer());
  }

  /** Emits the new access token. Errors when there is no refresh token or the server rejects it. */
  refresh(): Observable<string> {
    if (this.inflight) return this.inflight;
    const refreshToken = this.auth.refreshToken();
    if (!refreshToken) {
      return throwError(() => new HttpErrorResponse({ status: 401, statusText: 'No refresh token' }));
    }
    this.inflight = this.api.refresh(refreshToken).pipe(
      tap((res) => this.auth.signIn(res)),
      map((res) => res.accessToken),
      catchError((err: unknown) => {
        // Definitive rejection (expired, revoked, reused) → the session is over. Network blips keep it.
        if (err instanceof HttpErrorResponse && [400, 401, 403].includes(err.status)) this.auth.logout();
        return throwError(() => err);
      }),
      finalize(() => (this.inflight = null)),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.inflight;
  }

  /** Revokes the refresh token server-side (best effort, never blocks) and clears the local session. */
  logout(): void {
    const refreshToken = this.auth.refreshToken();
    this.clearTimer();
    this.auth.logout();
    if (refreshToken) {
      this.api.logout(refreshToken).subscribe({ error: () => undefined });
    }
  }

  /** Schedules proactive refreshes for the lifetime of the app. Idempotent. */
  start(): void {
    if (this.started) return;
    this.started = true;
    effect(
      () => {
        const expiresAt = this.auth.expiresAt();
        this.clearTimer();
        if (expiresAt === null) return;
        this.schedule(expiresAt);
      },
      { injector: this.injector },
    );
  }

  /** Visible for tests: ms until the proactive refresh for a token expiring at `expiresAt`. */
  delayFor(expiresAt: number): number {
    return Math.max(0, expiresAt - REFRESH_LEAD_MS - this.now());
  }

  private schedule(expiresAt: number): void {
    // Small jitter so several open tabs don't race; the first refresh is shared via storage events.
    const delay = this.delayFor(expiresAt) + (this.delayFor(expiresAt) > 0 ? Math.floor(Math.random() * this.jitterMs) : 0);
    if (delay >= 2 ** 31 - 1) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      if (!this.auth.isAuthenticated()) return;
      // Another tab may already have refreshed (adopted via the storage event).
      const current = this.auth.expiresAt();
      if (current !== null && this.delayFor(current) > 0) return;
      this.refresh().subscribe({
        error: () => {
          if (this.auth.isAuthenticated()) return; // transient failure: the interceptor retries on demand
          this.notifier.warning('Your session has ended', { description: 'Please sign in again to continue.' });
          const current = this.router.url;
          if (!isPublicUrl(current)) {
            void this.router.navigate(['/login'], { queryParams: { returnUrl: current } });
          }
        },
      });
    }, delay);
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }
}
