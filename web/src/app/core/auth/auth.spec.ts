import { TestBed } from '@angular/core/testing';
import { type ActivatedRouteSnapshot, provideRouter, Router, type RouterStateSnapshot, UrlTree } from '@angular/router';

import { authResponse } from '../../../testing/test-data';
import { Notifier } from '../notify/notifier.service';
import { AUTH_STORAGE_KEY, AuthStore, NOW } from './auth.store';
import { adminGuard, authGuard, examinerGuard, guestGuard } from './guards';

describe('AuthStore', () => {
  let now = 1_000_000;

  function configure() {
    TestBed.configureTestingModule({ providers: [{ provide: NOW, useValue: () => now }] });
    return TestBed.inject(AuthStore);
  }

  beforeEach(() => {
    localStorage.clear();
    now = 1_000_000;
  });

  it('signIn stores access + refresh tokens with absolute expiries and persists them', () => {
    const store = configure();
    store.signIn(authResponse(['INVENTOR']));

    expect(store.isAuthenticated()).toBe(true);
    expect(store.token()).toBe('access-1');
    expect(store.refreshToken()).toBe('refresh-1');
    expect(store.initials()).toBe('AL');
    const saved = JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY)!);
    expect(saved.expiresAt).toBe(1_000_000 + 900_000);
    expect(saved.refreshExpiresAt).toBe(1_000_000 + 604_800_000);
  });

  it('restores a persisted session whose access token expired but refresh token is still valid', () => {
    localStorage.setItem(
      AUTH_STORAGE_KEY,
      JSON.stringify({ accessToken: 'old', expiresAt: now - 1, refreshToken: 'r', refreshExpiresAt: now + 60_000, user: authResponse().user }),
    );
    const store = configure();
    expect(store.isAuthenticated()).toBe(true);
    expect(store.token()).toBeNull(); // must be refreshed before use
    expect(store.refreshToken()).toBe('r');
    expect(store.hasValidSession()).toBe(true);
  });

  it('discards a persisted session whose refresh token has expired', () => {
    localStorage.setItem(
      AUTH_STORAGE_KEY,
      JSON.stringify({ accessToken: 'a', expiresAt: now + 60_000, refreshToken: 'r', refreshExpiresAt: now - 1, user: authResponse().user }),
    );
    const store = configure();
    expect(store.isAuthenticated()).toBe(false);
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it('discards corrupted or legacy (access-only) entries', () => {
    localStorage.setItem(AUTH_STORAGE_KEY, '{not json');
    expect(configure().isAuthenticated()).toBe(false);

    TestBed.resetTestingModule();
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({ accessToken: 'a', expiresAt: now + 1000, user: authResponse().user }));
    expect(configure().isAuthenticated()).toBe(false);
  });

  it('token() returns null within the expiry skew while the session stays refreshable', () => {
    const store = configure();
    store.signIn(authResponse());
    now += 895_000; // 5 s before expiry, inside the 10 s skew
    expect(store.token()).toBeNull();
    expect(store.isAuthenticated()).toBe(true);
  });

  it('refreshToken() drops the session once the refresh token has expired', () => {
    const store = configure();
    store.signIn(authResponse());
    now += 604_800_001;
    expect(store.refreshToken()).toBeNull();
    expect(store.isAuthenticated()).toBe(false);
  });

  it('isExaminer is true for EXAMINER and ADMIN; isAdmin only for ADMIN', () => {
    const store = configure();
    store.signIn(authResponse(['INVENTOR']));
    expect([store.isExaminer(), store.isAdmin()]).toEqual([false, false]);
    store.signIn(authResponse(['EXAMINER']));
    expect([store.isExaminer(), store.isAdmin()]).toEqual([true, false]);
    store.signIn(authResponse(['ADMIN']));
    expect([store.isExaminer(), store.isAdmin()]).toEqual([true, true]);
  });

  it('adopts sign-in, token rotation and sign-out made in another tab (storage event)', () => {
    const store = configure();
    const other = { accessToken: 'a2', expiresAt: now + 900_000, refreshToken: 'r2', refreshExpiresAt: now + 60_000, user: authResponse().user };
    window.dispatchEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEY, newValue: JSON.stringify(other) }));
    expect(store.refreshToken()).toBe('r2');
    window.dispatchEvent(new StorageEvent('storage', { key: AUTH_STORAGE_KEY, newValue: null }));
    expect(store.isAuthenticated()).toBe(false);
  });

  it('updateUser replaces the profile; logout clears state and storage', () => {
    const store = configure();
    store.signIn(authResponse());
    store.updateUser({ ...authResponse().user, displayName: 'Grace Hopper' });
    expect(store.user()?.displayName).toBe('Grace Hopper');
    expect(JSON.parse(localStorage.getItem(AUTH_STORAGE_KEY)!).user.displayName).toBe('Grace Hopper');
    store.logout();
    expect(store.user()).toBeNull();
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });
});

describe('route guards', () => {
  const state = { url: '/applications/42?tab=documents' } as RouterStateSnapshot;
  const route = {} as ActivatedRouteSnapshot;
  let notifier: { warning: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    localStorage.clear();
    notifier = { warning: vi.fn() };
    TestBed.configureTestingModule({ providers: [provideRouter([]), { provide: Notifier, useValue: notifier }] });
  });

  afterEach(() => TestBed.inject(AuthStore).logout());

  const run = (guard: typeof authGuard) => TestBed.runInInjectionContext(() => guard(route, state));
  const serialize = (result: unknown) => TestBed.inject(Router).serializeUrl(result as UrlTree);

  it('authGuard redirects anonymous users to /login with returnUrl', () => {
    const result = run(authGuard);
    expect(result).toBeInstanceOf(UrlTree);
    expect(serialize(result)).toBe('/login?returnUrl=%2Fapplications%2F42%3Ftab%3Ddocuments');
  });

  it('authGuard allows a session whose access token expired but can be refreshed', () => {
    TestBed.inject(AuthStore).signIn(authResponse(['INVENTOR'], { expiresIn: 0 }));
    expect(run(authGuard)).toBe(true);
  });

  it('examinerGuard sends inventors to the dashboard with a warning; examiners and admins pass', () => {
    const store = TestBed.inject(AuthStore);
    store.signIn(authResponse(['INVENTOR']));
    expect(serialize(run(examinerGuard))).toBe('/dashboard');
    expect(notifier.warning).toHaveBeenCalled();

    store.signIn(authResponse(['EXAMINER']));
    expect(run(examinerGuard)).toBe(true);
    store.signIn(authResponse(['ADMIN']));
    expect(run(examinerGuard)).toBe(true);
  });

  it('adminGuard: anonymous → login, examiner → dashboard, admin → allowed', () => {
    expect(serialize(run(adminGuard))).toContain('/login?returnUrl=');
    const store = TestBed.inject(AuthStore);
    store.signIn(authResponse(['EXAMINER']));
    expect(serialize(run(adminGuard))).toBe('/dashboard');
    store.signIn(authResponse(['ADMIN', 'EXAMINER']));
    expect(run(adminGuard)).toBe(true);
  });

  it('guestGuard keeps signed-in users off the login page', () => {
    expect(run(guestGuard)).toBe(true);
    TestBed.inject(AuthStore).signIn(authResponse());
    expect(serialize(run(guestGuard))).toBe('/dashboard');
  });
});
