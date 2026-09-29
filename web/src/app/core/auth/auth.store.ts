import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';

import type { AuthResponse, Role, UserResponse } from '../models';

export const AUTH_STORAGE_KEY = 'openfiling.auth';

/** Clock abstraction so expiry logic is testable. */
export const NOW = new InjectionToken<() => number>('NOW', {
  providedIn: 'root',
  factory: () => () => Date.now(),
});

/** Storage abstraction (defaults to `localStorage`, `null` when unavailable). */
export const AUTH_STORAGE = new InjectionToken<Storage | null>('AUTH_STORAGE', {
  providedIn: 'root',
  factory: () => {
    try {
      return typeof localStorage === 'undefined' ? null : localStorage;
    } catch {
      return null;
    }
  },
});

export interface AuthSession {
  accessToken: string;
  /** Epoch millis. */
  expiresAt: number;
  refreshToken: string;
  /** Epoch millis. */
  refreshExpiresAt: number;
  user: UserResponse;
}

/** Treat an access token as expired this long before its real expiry so no request leaves with a dying token. */
export const EXPIRY_SKEW_MS = 10_000;

function isSession(value: unknown): value is AuthSession {
  if (typeof value !== 'object' || value === null) return false;
  const v = value as Partial<AuthSession>;
  return (
    typeof v.accessToken === 'string' &&
    typeof v.expiresAt === 'number' &&
    typeof v.refreshToken === 'string' &&
    typeof v.refreshExpiresAt === 'number' &&
    typeof v.user === 'object' &&
    v.user !== null &&
    typeof v.user.email === 'string' &&
    Array.isArray(v.user.roles)
  );
}

/**
 * Signal store for the signed-in session (access token + rotating refresh token).
 *
 * - The session lives as long as the **refresh** token: an expired access token alone does not sign
 *   the user out — `SessionService` / the auth interceptor exchange the refresh token for a new pair.
 * - Persisted to localStorage so a reload keeps the user signed in; a session whose refresh token
 *   expired (or a corrupted entry) is discarded on load.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly storage = inject(AUTH_STORAGE);
  private readonly now = inject(NOW);
  private readonly session = signal<AuthSession | null>(null);

  readonly user = computed(() => this.session()?.user ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);
  readonly roles = computed<Role[]>(() => this.session()?.user.roles ?? []);
  readonly isAdmin = computed(() => this.roles().includes('ADMIN'));
  /** Examiner capabilities: EXAMINER or ADMIN (admins may perform examiner actions). */
  readonly isExaminer = computed(() => this.roles().includes('EXAMINER') || this.roles().includes('ADMIN'));
  readonly expiresAt = computed(() => this.session()?.expiresAt ?? null);
  readonly initials = computed(() => {
    const u = this.user();
    if (!u) return '';
    const source = u.displayName?.trim() || u.email;
    const parts = source.split(/[\s@._-]+/).filter(Boolean);
    return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?';
  });

  constructor() {
    this.restore();
    // Keep tabs in sync: a refresh (token rotation), sign-in or sign-out in another tab is adopted here,
    // so two tabs never present the same rotated refresh token (which the API treats as reuse).
    if (typeof window !== 'undefined' && this.storage === (globalThis as { localStorage?: Storage }).localStorage) {
      const onStorage = (e: StorageEvent) => {
        if (e.key === AUTH_STORAGE_KEY || e.key === null) this.adoptFromStorage(e.newValue);
      };
      window.addEventListener('storage', onStorage);
      inject(DestroyRef).onDestroy(() => window.removeEventListener('storage', onStorage));
    }
  }

  private adoptFromStorage(raw: string | null): void {
    if (!raw) {
      this.session.set(null);
      return;
    }
    try {
      const parsed: unknown = JSON.parse(raw);
      if (isSession(parsed) && parsed.refreshExpiresAt > this.now()) this.session.set(parsed);
    } catch {
      /* ignore malformed writes from other tabs */
    }
  }

  /** Stores a fresh session from a login / register / refresh response. */
  signIn(response: AuthResponse): void {
    const now = this.now();
    const session: AuthSession = {
      accessToken: response.accessToken,
      expiresAt: now + Math.max(0, response.expiresIn) * 1000,
      refreshToken: response.refreshToken,
      refreshExpiresAt: now + Math.max(0, response.refreshExpiresIn) * 1000,
      user: response.user,
    };
    this.session.set(session);
    this.persist(session);
  }

  /** Replaces the cached user profile (e.g. after `PATCH /account/profile`). */
  updateUser(user: UserResponse): void {
    const current = this.session();
    if (!current) return;
    const next = { ...current, user };
    this.session.set(next);
    this.persist(next);
  }

  /** Local sign-out: forgets tokens. (The server-side revoke is `SessionService.logout()`.) */
  logout(): void {
    this.session.set(null);
    try {
      this.storage?.removeItem(AUTH_STORAGE_KEY);
    } catch {
      /* storage unavailable — nothing to clear */
    }
  }

  /** The access token if still valid, else null (the session itself may still be refreshable). */
  token(): string | null {
    const s = this.session();
    if (!s || this.accessExpired(s)) return null;
    return s.accessToken;
  }

  /** The refresh token while it is valid; drops the whole session once it has expired. */
  refreshToken(): string | null {
    const s = this.session();
    if (!s) return null;
    if (s.refreshExpiresAt <= this.now()) {
      this.logout();
      return null;
    }
    return s.refreshToken;
  }

  /** True when a signed-in session exists and its refresh token has not expired. */
  hasValidSession(): boolean {
    return this.refreshToken() !== null;
  }

  hasRole(role: Role): boolean {
    return this.roles().includes(role);
  }

  private accessExpired(s: AuthSession): boolean {
    return s.expiresAt - EXPIRY_SKEW_MS <= this.now();
  }

  private restore(): void {
    let raw: string | null = null;
    try {
      raw = this.storage?.getItem(AUTH_STORAGE_KEY) ?? null;
    } catch {
      raw = null;
    }
    if (!raw) return;
    try {
      const parsed: unknown = JSON.parse(raw);
      if (isSession(parsed) && parsed.refreshExpiresAt > this.now()) {
        this.session.set(parsed);
        return;
      }
    } catch {
      /* corrupted entry — fall through and remove it */
    }
    this.logout();
  }

  private persist(session: AuthSession): void {
    try {
      this.storage?.setItem(AUTH_STORAGE_KEY, JSON.stringify(session));
    } catch {
      /* quota / private mode — session stays in memory only */
    }
  }
}
