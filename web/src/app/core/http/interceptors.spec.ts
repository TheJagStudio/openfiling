import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { authResponse } from '../../../testing/test-data';
import { AuthStore, NOW } from '../auth/auth.store';
import { Notifier } from '../notify/notifier.service';
import { SKIP_ERROR_TOAST } from './http-utils';
import { authInterceptor, errorInterceptor, requestIdInterceptor } from './interceptors';

const S3_URL =
  'http://localhost:9000/openfiling-documents/public/a/d/spec.pdf?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=abc';

describe('HTTP interceptors', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthStore;
  let now: number;
  let notifier: Record<'success' | 'info' | 'warning' | 'error' | 'problem', ReturnType<typeof vi.fn>>;
  let router: { url: string; navigate: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    localStorage.clear();
    now = 5_000_000;
    notifier = { success: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn(), problem: vi.fn() };
    router = { url: '/applications/a-1?tab=documents', navigate: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      providers: [
        // Same order as app.config.ts.
        provideHttpClient(withInterceptors([requestIdInterceptor, errorInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        { provide: Notifier, useValue: notifier },
        { provide: Router, useValue: router },
        { provide: NOW, useValue: () => now },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthStore);
  });

  afterEach(() => {
    backend.verify();
    auth.logout();
  });

  const refreshed = () => authResponse(['INVENTOR'], { accessToken: 'access-2', refreshToken: 'refresh-2' });
  const unauthorized = { status: 401, statusText: 'Unauthorized' };

  it('adds Authorization: Bearer and X-Request-Id to own API calls', () => {
    auth.signIn(authResponse());
    http.get('/api/v1/auth/me').subscribe();
    const req = backend.expectOne('/api/v1/auth/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-1');
    expect(req.request.headers.get('X-Request-Id')).toBeTruthy();
    req.flush({});
  });

  it('never sends the bearer token or request id to the pre-signed S3 URL', () => {
    auth.signIn(authResponse());
    http.put(S3_URL, new Blob(['%PDF-'])).subscribe();
    const req = backend.expectOne(S3_URL);
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(req.request.headers.has('X-Request-Id')).toBe(false);
    req.flush(null);
  });

  it('sends no Authorization header when signed out', () => {
    http.get('/api/v1/public/stats').subscribe();
    const req = backend.expectOne('/api/v1/public/stats');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('401 → refreshes once with the refresh token, then retries the request with the new access token', () => {
    auth.signIn(authResponse());
    let body: unknown;
    http.get('/api/v1/dashboard').subscribe((b) => (body = b));

    backend.expectOne('/api/v1/dashboard').flush({ title: 'Unauthorized' }, unauthorized);

    const refresh = backend.expectOne('/api/v1/auth/refresh');
    expect(refresh.request.method).toBe('POST');
    expect(refresh.request.body).toEqual({ refreshToken: 'refresh-1' });
    expect(refresh.request.headers.has('Authorization')).toBe(false);
    refresh.flush(refreshed());

    const retry = backend.expectOne('/api/v1/dashboard');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer access-2');
    retry.flush({ ok: true });

    expect(body).toEqual({ ok: true });
    expect(auth.refreshToken()).toBe('refresh-2');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('concurrent 401s share a single refresh request (single-flight)', () => {
    auth.signIn(authResponse());
    const results: unknown[] = [];
    http.get('/api/v1/applications').subscribe((b) => results.push(b));
    http.get('/api/v1/notifications/unread-count').subscribe((b) => results.push(b));

    backend.expectOne('/api/v1/applications').flush({}, unauthorized);
    backend.expectOne('/api/v1/notifications/unread-count').flush({}, unauthorized);

    const refreshes = backend.match('/api/v1/auth/refresh');
    expect(refreshes.length).toBe(1);
    refreshes[0]!.flush(refreshed());

    backend.expectOne('/api/v1/applications').flush({ a: 1 });
    backend.expectOne('/api/v1/notifications/unread-count').flush({ count: 2 });
    expect(results).toHaveLength(2);
  });

  it('retries only once: a second 401 after refreshing signs the user out', () => {
    auth.signIn(authResponse());
    let failed = false;
    http.get('/api/v1/dashboard').subscribe({ error: () => (failed = true) });

    backend.expectOne('/api/v1/dashboard').flush({}, unauthorized);
    backend.expectOne('/api/v1/auth/refresh').flush(refreshed());
    backend.expectOne('/api/v1/dashboard').flush({}, unauthorized);

    backend.expectNone('/api/v1/auth/refresh');
    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/applications/a-1?tab=documents' } });
  });

  it('refresh failure → logout, redirect to /login?returnUrl and a "session ended" toast', () => {
    auth.signIn(authResponse());
    let failed = false;
    http.get('/api/v1/dashboard').subscribe({ error: () => (failed = true) });

    backend.expectOne('/api/v1/dashboard').flush({}, unauthorized);
    backend.expectOne('/api/v1/auth/refresh').flush({ title: 'Invalid refresh token' }, unauthorized);

    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/applications/a-1?tab=documents' } });
    expect(notifier.warning).toHaveBeenCalledWith('Your session has ended', expect.anything());
    expect(notifier.problem).not.toHaveBeenCalled();
  });

  it('refreshes before sending when the access token already expired but the refresh token is valid', () => {
    auth.signIn(authResponse());
    now += 16 * 60_000; // access token (15 min) expired
    http.get('/api/v1/dashboard').subscribe();

    backend.expectOne('/api/v1/auth/refresh').flush(refreshed());
    const req = backend.expectOne('/api/v1/dashboard');
    expect(req.request.headers.get('Authorization')).toBe('Bearer access-2');
    req.flush({});
  });

  it('does not try to refresh the login call itself (bad credentials stay inline)', () => {
    let failed = false;
    http
      .post('/api/v1/auth/login', {}, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) })
      .subscribe({ error: () => (failed = true) });
    backend.expectOne('/api/v1/auth/login').flush({ title: 'Bad credentials' }, unauthorized);
    backend.expectNone('/api/v1/auth/refresh');
    expect(failed).toBe(true);
  });

  it('429 → warning toast with the Retry-After delay', () => {
    http.get('/api/v1/public/stats').subscribe({ error: () => undefined });
    backend
      .expectOne('/api/v1/public/stats')
      .flush({ title: 'Too Many Requests', status: 429 }, { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '42' } });
    expect(notifier.warning).toHaveBeenCalledWith('Too many requests', expect.objectContaining({ description: 'Please wait 42 seconds and try again.' }));
  });

  it('other errors → ProblemDetail toast unless SKIP_ERROR_TOAST', () => {
    http.get('/api/v1/examiner/stats').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/examiner/stats').flush({ title: 'Boom', detail: 'db down', status: 503, requestId: 'req-1' }, { status: 503, statusText: 'Unavailable' });
    expect(notifier.problem).toHaveBeenCalledWith(expect.objectContaining({ title: 'Boom', requestId: 'req-1' }));

    notifier.problem.mockClear();
    http.get('/api/v1/applications/x', { context: new HttpContext().set(SKIP_ERROR_TOAST, true) }).subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/applications/x').flush({ title: 'Not Found', status: 404 }, { status: 404, statusText: 'Not Found' });
    expect(notifier.problem).not.toHaveBeenCalled();
  });
});
