import { HttpContextToken, HttpParams } from '@angular/common/http';
import { InjectionToken } from '@angular/core';

import { environment } from '../../../environments/environment';
import type { Page } from '../models';

/** Prefix for every API URL (empty = same origin, which the dev proxy forwards to filing-api). */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => environment.apiBaseUrl,
});

/**
 * Opt a request out of the global error toast — for callers that render the error inline
 * (form validation, "not found" pages, status polling…). 401 handling still applies.
 */
export const SKIP_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

/** Opt a request out of the 401 → refresh → logout → login redirect (e.g. the login call itself). */
export const SKIP_AUTH_REDIRECT = new HttpContextToken<boolean>(() => false);

/** Marks token endpoints (`/auth/refresh`, `/auth/logout`): no bearer header, never refreshed/retried. */
export const SKIP_AUTH = new HttpContextToken<boolean>(() => false);

type ParamValue = string | number | boolean | null | undefined | readonly string[];

/**
 * Builds `HttpParams` from a flat object, dropping `null`, `undefined`, blank strings and empty
 * arrays so URLs stay clean. Arrays are joined with commas (`status=A,B`, as the contract expects).
 */
export function toHttpParams(source: object): HttpParams {
  let params = new HttpParams();
  for (const [key, raw] of Object.entries(source) as [string, ParamValue][]) {
    if (raw === null || raw === undefined) continue;
    const value = Array.isArray(raw)
      ? raw.map((v) => String(v).trim()).filter(Boolean).join(',')
      : typeof raw === 'string'
        ? raw.trim()
        : String(raw);
    if (value === '') continue;
    params = params.set(key, value);
  }
  return params;
}

/**
 * Normalizes a page envelope defensively (missing arrays/counters become safe defaults) so
 * templates never have to null-check. Numbers are clamped to non-negative integers.
 */
export function toPage<T>(raw: Partial<Page<T>> | null | undefined): Page<T> {
  const content = Array.isArray(raw?.content) ? raw.content : [];
  const size = Math.max(1, Math.trunc(raw?.size ?? (content.length || 20)));
  const totalElements = Math.max(0, Math.trunc(raw?.totalElements ?? content.length));
  const totalPages = Math.max(0, Math.trunc(raw?.totalPages ?? Math.ceil(totalElements / size)));
  const page = Math.max(0, Math.trunc(raw?.page ?? 0));
  return { content, page, size, totalElements, totalPages };
}

/**
 * True for calls to our own API (relative, same-origin, or under an absolute `apiBaseUrl`).
 * Pre-signed S3 URLs (e.g. `http://localhost:9000/...`) are never "own API", so they never get a
 * bearer token or request id.
 */
export function isOwnApi(url: string, apiBaseUrl = ''): boolean {
  if (apiBaseUrl && /^https?:\/\//i.test(apiBaseUrl) && url.startsWith(apiBaseUrl.replace(/\/+$/, '') + '/')) return true;
  if (url.startsWith('/')) return !url.startsWith('//');
  try {
    return typeof location !== 'undefined' && new URL(url).origin === location.origin;
  } catch {
    return false;
  }
}
