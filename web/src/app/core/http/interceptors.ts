import { HttpErrorResponse, type HttpInterceptorFn, type HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';

import { catchError, of, switchMap, throwError } from 'rxjs';

import { isPublicUrl } from '../auth/public-routes';
import { AuthStore } from '../auth/auth.store';
import { SessionService } from '../auth/session.service';
import { Notifier } from '../notify/notifier.service';
import { API_BASE_URL, isOwnApi, SKIP_AUTH, SKIP_AUTH_REDIRECT, SKIP_ERROR_TOAST } from './http-utils';
import { retryAfterSeconds, toProblem } from './problem';

function newRequestId(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `${Date.now().toString(16)}-${Math.random().toString(16).slice(2, 10)}`;
}

/** Adds a correlation id to every own-API request (filing-api echoes it and logs it in MDC/audit). */
export const requestIdInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isOwnApi(req.url, inject(API_BASE_URL)) || req.headers.has('X-Request-Id')) return next(req);
  return next(req.clone({ setHeaders: { 'X-Request-Id': newRequestId() } }));
};

function withBearer<T>(req: HttpRequest<T>, token: string | null): HttpRequest<T> {
  return token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
}

/**
 * Bearer token + transparent refresh, for our own API only (never for pre-signed S3 URLs).
 *
 * 1. If the access token has expired but the refresh token is valid, refresh first.
 * 2. On a 401, perform **one** single-flight refresh (`SessionService.refresh()`) and retry the
 *    request **once** with the new token. If another request already rotated the token meanwhile,
 *    just retry with the current one.
 * 3. If the refresh fails, the original 401 propagates to `errorInterceptor`, which signs the user
 *    out and redirects to `/login?returnUrl=…`.
 *
 * Must run *inside* `errorInterceptor` (registered after it) so the error handler only sees the
 * final outcome.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isOwnApi(req.url, inject(API_BASE_URL)) || req.context.get(SKIP_AUTH) || req.headers.has('Authorization')) {
    return next(req);
  }
  const auth = inject(AuthStore);
  const session = inject(SessionService);

  const current = auth.token();
  const first$ =
    current || !auth.refreshToken()
      ? of(current)
      : session.refresh().pipe(catchError(() => of(null as string | null)));

  return first$.pipe(
    switchMap((sent) =>
      next(withBearer(req, sent)).pipe(
        catchError((error: unknown) => {
          if (!(error instanceof HttpErrorResponse) || error.status !== 401 || !auth.refreshToken()) {
            return throwError(() => error);
          }
          const latest = auth.token();
          const retryToken$ = latest && latest !== sent ? of(latest) : session.refresh();
          return retryToken$.pipe(
            catchError(() => throwError(() => error)),
            switchMap((token) => next(withBearer(req, token))),
          );
        }),
      ),
    ),
  );
};

/**
 * Global error handling (sees the final outcome, after any refresh + retry):
 * - 401 → drop the session and send the user to /login?returnUrl=… (unless opted out)
 * - 429 → toast with the Retry-After delay
 * - anything else → toast with ProblemDetail title/detail + requestId (unless opted out)
 * The error is always re-thrown so callers can still react.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthStore);
  const router = inject(Router);
  const notifier = inject(Notifier);
  // Captured up-front: a failed refresh inside authInterceptor may already have cleared the session.
  const hadSession = auth.isAuthenticated();

  return next(req).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) return throwError(() => error);
      const problem = toProblem(error);

      if (error.status === 401 && !req.context.get(SKIP_AUTH_REDIRECT)) {
        auth.logout();
        const current = router.url;
        if (!isPublicUrl(current)) {
          void router.navigate(['/login'], { queryParams: { returnUrl: current } });
        }
        if (hadSession) {
          notifier.warning('Your session has ended', { description: 'Please sign in again to continue.' });
        } else if (!req.context.get(SKIP_ERROR_TOAST)) {
          notifier.info('Sign in required', { description: problem.detail });
        }
        return throwError(() => error);
      }

      if (error.status === 429 && !req.context.get(SKIP_ERROR_TOAST)) {
        const seconds = retryAfterSeconds(error.headers?.get('Retry-After') ?? null);
        notifier.warning('Too many requests', {
          description:
            seconds !== null
              ? `Please wait ${seconds} second${seconds === 1 ? '' : 's'} and try again.`
              : 'Please slow down and try again shortly.',
          duration: 8000,
        });
        return throwError(() => error);
      }

      if (!req.context.get(SKIP_ERROR_TOAST)) {
        notifier.problem(problem);
      }
      return throwError(() => error);
    }),
  );
};
