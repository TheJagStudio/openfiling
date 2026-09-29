import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { authResponse } from '../../../testing/test-data';
import { Notifier } from '../notify/notifier.service';
import { AuthStore, NOW } from './auth.store';
import { REFRESH_LEAD_MS, SessionService } from './session.service';

describe('SessionService', () => {
  let backend: HttpTestingController;
  let auth: AuthStore;
  let session: SessionService;
  let now: number;
  let router: { url: string; navigate: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    localStorage.clear();
    now = 1_000_000;
    router = { url: '/dashboard', navigate: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NOW, useValue: () => now },
        { provide: Router, useValue: router },
        { provide: Notifier, useValue: { warning: vi.fn(), info: vi.fn(), success: vi.fn(), error: vi.fn(), problem: vi.fn() } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthStore);
    session = TestBed.inject(SessionService);
  });

  afterEach(() => {
    backend.verify();
    vi.useRealTimers();
  });

  it('logout() revokes the refresh token server-side and clears the local session', () => {
    auth.signIn(authResponse());
    session.logout();
    expect(auth.isAuthenticated()).toBe(false);
    const req = backend.expectOne('/api/v1/auth/logout');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ refreshToken: 'refresh-1' });
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(null, { status: 500, statusText: 'Oops' }); // best effort — errors are swallowed
  });

  it('refresh() is single-flight and stores the rotated pair', () => {
    auth.signIn(authResponse());
    const tokens: string[] = [];
    session.refresh().subscribe((t) => tokens.push(t));
    session.refresh().subscribe((t) => tokens.push(t));
    backend.expectOne('/api/v1/auth/refresh').flush(authResponse(['INVENTOR'], { accessToken: 'access-2', refreshToken: 'refresh-2' }));
    expect(tokens).toEqual(['access-2', 'access-2']);
    expect(auth.refreshToken()).toBe('refresh-2');
  });

  it('refresh() failure logs out locally', () => {
    auth.signIn(authResponse());
    let failed = false;
    session.refresh().subscribe({ error: () => (failed = true) });
    backend.expectOne('/api/v1/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(failed).toBe(true);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('schedules the proactive refresh shortly before the access token expires', () => {
    vi.useFakeTimers();
    auth.signIn(authResponse()); // expires in 900 s
    session.jitterMs = 0;
    session.start();
    TestBed.tick();

    const delay = session.delayFor(auth.expiresAt()!);
    expect(delay).toBe(900_000 - REFRESH_LEAD_MS);

    vi.advanceTimersByTime(delay - 1);
    backend.expectNone('/api/v1/auth/refresh');
    now += delay; // the injected clock moves with the timers
    vi.advanceTimersByTime(1);
    backend.expectOne('/api/v1/auth/refresh').flush(authResponse(['INVENTOR'], { accessToken: 'access-2', refreshToken: 'refresh-2' }));
    expect(auth.token()).toBe('access-2');
  });
});
