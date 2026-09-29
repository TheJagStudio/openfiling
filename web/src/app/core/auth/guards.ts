import { inject } from '@angular/core';
import { type CanActivateFn, Router } from '@angular/router';

import { Notifier } from '../notify/notifier.service';
import { AuthStore } from './auth.store';

function toLogin(url: string) {
  return inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: url } });
}

/**
 * Requires a signed-in session (a valid refresh token — an expired access token is refreshed
 * transparently by the interceptor); otherwise redirects to /login?returnUrl=<target>.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  return inject(AuthStore).hasValidSession() ? true : toLogin(state.url);
};

/** EXAMINER or ADMIN. Anonymous → login; signed-in inventors → dashboard with a warning. */
export const examinerGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthStore);
  if (!auth.hasValidSession()) return toLogin(state.url);
  if (auth.isExaminer()) return true;
  inject(Notifier).warning('Examiners only', { description: 'The examiner queue requires the EXAMINER role.' });
  return inject(Router).createUrlTree(['/dashboard']);
};

/** ADMIN only. Anonymous → login; other signed-in users → dashboard with a warning. */
export const adminGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthStore);
  if (!auth.hasValidSession()) return toLogin(state.url);
  if (auth.isAdmin()) return true;
  inject(Notifier).warning('Administrators only', { description: 'Your account does not have access to that page.' });
  return inject(Router).createUrlTree(['/dashboard']);
};

/** Keeps signed-in users away from /login and /register. */
export const guestGuard: CanActivateFn = () => {
  return inject(AuthStore).hasValidSession() ? inject(Router).createUrlTree(['/dashboard']) : true;
};
