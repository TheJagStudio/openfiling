import type { IsoInstant, Uuid } from './common.models';

export type Role = 'INVENTOR' | 'EXAMINER' | 'ADMIN';

export const ROLES: readonly Role[] = ['INVENTOR', 'EXAMINER', 'ADMIN'];

export const ROLE_LABELS: Record<Role, string> = {
  INVENTOR: 'Inventor',
  EXAMINER: 'Examiner',
  ADMIN: 'Administrator',
};

/** `UserResponse` */
export interface UserResponse {
  id: Uuid;
  email: string;
  displayName: string;
  roles: Role[];
  /** e.g. `2100`; examiners only. */
  artUnit: string | null;
  createdAt: IsoInstant;
  lastLoginAt: IsoInstant | null;
}

/** `AuthResponse` — returned by register, login and refresh (refresh token rotated on every use). */
export interface AuthResponse {
  accessToken: string;
  /** Seconds until the access token expires (15 min). */
  expiresIn: number;
  /** Opaque 256-bit token. */
  refreshToken: string;
  /** Seconds until the refresh token expires (7 days). */
  refreshExpiresIn: number;
  tokenType: 'Bearer';
  user: UserResponse;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface RefreshRequest {
  refreshToken: string;
}

export interface UpdateProfileRequest {
  displayName: string;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/** Row of `GET /account/sessions` (an active refresh-token family). */
export interface SessionResponse {
  id: Uuid;
  createdAt: IsoInstant;
  lastUsedAt: IsoInstant | null;
  expiresAt: IsoInstant;
  userAgent: string | null;
  ip: string | null;
}

/** Password policy enforced by filing-api: 10–128 chars, at least one letter and one digit. */
export const PASSWORD_RULE = {
  minLength: 10,
  maxLength: 128,
  pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
} as const;

export interface PasswordCheck {
  label: string;
  ok: boolean;
}

/** Individual policy checks, for live hints next to password fields. */
export function passwordChecks(pw: string): PasswordCheck[] {
  return [
    { label: `${PASSWORD_RULE.minLength}–${PASSWORD_RULE.maxLength} characters`, ok: pw.length >= PASSWORD_RULE.minLength && pw.length <= PASSWORD_RULE.maxLength },
    { label: 'Contains a letter', ok: /[A-Za-z]/.test(pw) },
    { label: 'Contains a digit', ok: /\d/.test(pw) },
  ];
}
