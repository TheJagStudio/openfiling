import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SKIP_AUTH, SKIP_AUTH_REDIRECT, SKIP_ERROR_TOAST } from '../http/http-utils';
import { AccountApi } from './account.api';
import { AdminApi } from './admin.api';
import { ApplicationsApi } from './applications.api';
import { AuthApi } from './auth.api';
import { DocumentsApi } from './documents.api';
import { ExaminerApi } from './examiner.api';
import { NotificationsApi } from './notifications.api';
import { PublicApi } from './public.api';
import { ReferenceApi, ReferenceStore } from './reference.api';

describe('API services', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('ApplicationsApi.list sends q, comma-joined status, type, sort and paging; normalizes the page', () => {
    let total = -1;
    TestBed.inject(ApplicationsApi)
      .list({ q: ' battery ', status: 'DRAFT,INCOMPLETE', type: 'UTILITY', sort: 'title,asc', page: 2, size: 20 })
      .subscribe((p) => (total = p.totalElements));
    const req = http.expectOne((r) => r.url === '/api/v1/applications');
    expect(req.request.params.get('q')).toBe('battery');
    expect(req.request.params.get('status')).toBe('DRAFT,INCOMPLETE');
    expect(req.request.params.get('type')).toBe('UTILITY');
    expect(req.request.params.get('sort')).toBe('title,asc');
    expect(req.request.params.get('page')).toBe('2');
    req.flush({ content: [], page: 2, size: 20 });
    expect(total).toBe(0);
  });

  it('ApplicationsApi submit/actions/history/receipt/dashboard hit the contract paths', () => {
    const api = TestBed.inject(ApplicationsApi);
    api.submit('a 1', { version: 3, certify: true, signature: '/Ada Lovelace/' }).subscribe();
    const submit = http.expectOne('/api/v1/applications/a%201/submit');
    expect(submit.request.method).toBe('POST');
    expect(submit.request.body).toEqual({ version: 3, certify: true, signature: '/Ada Lovelace/' });
    expect(submit.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    submit.flush({});

    api.action('a1', { action: 'DOCKET', artUnit: '2100', version: 4 }).subscribe();
    const act = http.expectOne('/api/v1/applications/a1/actions');
    expect(act.request.body).toEqual({ action: 'DOCKET', artUnit: '2100', version: 4 });
    act.flush({});

    api.history('a1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/v1/applications/a1/history' }).flush(null);
    api.receipt('a1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/v1/applications/a1/receipt' }).flush({});
    api.dashboard().subscribe();
    http.expectOne({ method: 'GET', url: '/api/v1/dashboard' }).flush({});
  });

  it('ApplicationsApi.update PUTs with version (409 rendered inline)', () => {
    TestBed.inject(ApplicationsApi)
      .update('a1', { title: 'T', applicationType: 'UTILITY', entityStatus: 'SMALL', abstractText: null, technologyField: null, attorneyDocketNumber: null, inventors: [], version: 7 })
      .subscribe({ error: () => undefined });
    const req = http.expectOne('/api/v1/applications/a1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.version).toBe(7);
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({}, { status: 409, statusText: 'Conflict' });
  });

  it('DocumentsApi: create, complete, download(copy), retry and delete', () => {
    const api = TestBed.inject(DocumentsApi);
    api.create('a1', { docCode: 'SPEC', fileName: 's.pdf', contentType: 'application/pdf', sizeBytes: 10 }).subscribe();
    expect(http.expectOne('/api/v1/applications/a1/documents').request.method).toBe('POST');

    api.complete('d1').subscribe();
    expect(http.expectOne('/api/v1/documents/d1/complete').request.method).toBe('POST');

    api.download('d1', 'public').subscribe();
    const dl = http.expectOne((r) => r.url === '/api/v1/documents/d1/download');
    expect(dl.request.params.get('copy')).toBe('public');
    dl.flush({ url: 'http://localhost:9000/x', expiresAt: '', fileName: 's.pdf' });

    api.retryRedaction('d1').subscribe();
    expect(http.expectOne('/api/v1/documents/d1/redaction/retry').request.method).toBe('POST');
    api.remove('d1').subscribe();
    expect(http.expectOne('/api/v1/documents/d1').request.method).toBe('DELETE');
    http.match(() => true).forEach((r) => r.flush({}));
  });

  it('ExaminerApi.queue passes assigned/artUnit/filed range and drops blanks', () => {
    TestBed.inject(ExaminerApi)
      .queue({ q: '', assigned: 'unassigned', artUnit: '2100', filedFrom: '2026-01-01', filedTo: '2026-06-30', sort: 'filingDate,asc', page: 0 })
      .subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/examiner/queue');
    expect(req.request.params.has('q')).toBe(false);
    expect(req.request.params.get('assigned')).toBe('unassigned');
    expect(req.request.params.get('artUnit')).toBe('2100');
    expect(req.request.params.get('filedFrom')).toBe('2026-01-01');
    expect(req.request.params.get('filedTo')).toBe('2026-06-30');
    expect(req.request.params.get('sort')).toBe('filingDate,asc');
    req.flush({ content: [] });
  });

  it('NotificationsApi.unreadCount maps {count} and never toasts', () => {
    let count = -1;
    TestBed.inject(NotificationsApi).unreadCount().subscribe((c) => (count = c));
    const req = http.expectOne('/api/v1/notifications/unread-count');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({ count: 4 });
    expect(count).toBe(4);
  });

  it('AdminApi.audit and PATCH users', () => {
    const api = TestBed.inject(AdminApi);
    api.audit({ actor: 'ada@x', action: 'AUTH_LOCKED', resourceType: 'USER', page: 1, size: 25 }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/admin/audit');
    expect(req.request.params.get('actor')).toBe('ada@x');
    expect(req.request.params.get('action')).toBe('AUTH_LOCKED');
    expect(req.request.params.get('size')).toBe('25');
    req.flush({ content: [] });

    api.updateUser('u1', { roles: ['EXAMINER'], unlock: true }).subscribe();
    const patch = http.expectOne('/api/v1/admin/users/u1');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ roles: ['EXAMINER'], unlock: true });
    patch.flush({});
  });

  it('PublicApi encodes display-form application numbers in the path', () => {
    TestBed.inject(PublicApi).application('19/000,123').subscribe();
    http.expectOne('/api/v1/public/applications/19%2F000%2C123').flush({});
  });

  it('AuthApi token endpoints skip the bearer, the toast and the 401 redirect', () => {
    const api = TestBed.inject(AuthApi);
    api.refresh('r1').subscribe();
    const req = http.expectOne('/api/v1/auth/refresh');
    expect(req.request.body).toEqual({ refreshToken: 'r1' });
    expect(req.request.context.get(SKIP_AUTH)).toBe(true);
    expect(req.request.context.get(SKIP_AUTH_REDIRECT)).toBe(true);
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({});
  });

  it('AccountApi sessions endpoints', () => {
    const api = TestBed.inject(AccountApi);
    api.revokeSession('s1').subscribe();
    expect(http.expectOne('/api/v1/account/sessions/s1').request.method).toBe('DELETE');
    api.revokeAll().subscribe();
    expect(http.expectOne('/api/v1/account/sessions/revoke-all').request.method).toBe('POST');
    http.match(() => true).forEach((r) => r.flush(null));
  });

  it('ReferenceApi caches /reference; ReferenceStore falls back to contract defaults', () => {
    const api = TestBed.inject(ReferenceApi);
    api.load().subscribe();
    api.load().subscribe();
    const store = TestBed.inject(ReferenceStore);
    expect(store.limits().maxUploadBytes).toBe(500 * 1024 * 1024);
    expect(store.defaultVisibility('ADS')).toBe('RESTRICTED');
    http
      .expectOne('/api/v1/reference')
      .flush({ statuses: [], documentCodes: [], applicationTypes: [], entityStatuses: [], limits: { maxUploadBytes: 1024, maxDocumentsPerApplication: 60, allowedContentTypes: ['application/pdf'] } });
    expect(store.limits().maxUploadBytes).toBe(1024);
  });
});
