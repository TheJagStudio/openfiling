import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { documentResponse, pdfFile } from '../../../testing/test-data';
import type { DocumentResponse, UploadInstruction } from '../models';
import { type DirectUploadEvent, DirectUploadService, parseS3Error, REDACTION_POLL_MS, UploadError, XHR_FACTORY } from './direct-upload.service';

/** Minimal XMLHttpRequest double: records what was sent and lets the test drive progress / completion. */
class MockXhr {
  static instances: MockXhr[] = [];
  method = '';
  url = '';
  async: boolean | undefined;
  headers: Record<string, string> = {};
  body: unknown = null;
  status = 0;
  responseText = '';
  aborted = false;
  responseHeaders: Record<string, string> = {};
  upload: { onprogress: ((e: ProgressEvent) => void) | null } = { onprogress: null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  ontimeout: (() => void) | null = null;

  constructor() {
    MockXhr.instances.push(this);
  }
  open(method: string, url: string, async?: boolean) {
    this.method = method;
    this.url = url;
    this.async = async;
  }
  setRequestHeader(name: string, value: string) {
    this.headers[name] = value;
  }
  send(body: unknown) {
    this.body = body;
  }
  abort() {
    this.aborted = true;
  }
  getResponseHeader(name: string) {
    return this.responseHeaders[name] ?? null;
  }
  progress(loaded: number, total: number) {
    this.upload.onprogress?.({ loaded, total, lengthComputable: true } as ProgressEvent);
  }
  respond(status: number, body = '', headers: Record<string, string> = {}) {
    this.status = status;
    this.responseText = body;
    this.responseHeaders = headers;
    this.onload?.();
  }
}

const INSTRUCTION: UploadInstruction = {
  url: 'http://localhost:9000/openfiling-documents/public/a-1/d-1/spec.pdf?X-Amz-Signature=sig',
  method: 'PUT',
  headers: { 'Content-Type': 'application/pdf', 'x-amz-meta-document-id': 'd-1' },
  expiresAt: '2026-09-29T12:15:00Z',
};

describe('DirectUploadService', () => {
  let service: DirectUploadService;
  let http: HttpTestingController;

  beforeEach(() => {
    MockXhr.instances = [];
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: XHR_FACTORY, useValue: () => new MockXhr() as unknown as XMLHttpRequest },
        { provide: REDACTION_POLL_MS, useValue: 3000 },
      ],
    });
    service = TestBed.inject(DirectUploadService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('PUTs to the pre-signed URL with exactly the returned headers (no Authorization) and reports progress', () => {
    const file = pdfFile();
    const events: unknown[] = [];
    let done = false;
    service.put(INSTRUCTION, file).subscribe({ next: (e) => events.push(e), complete: () => (done = true) });

    const xhr = MockXhr.instances[0]!;
    expect(xhr.method).toBe('PUT');
    expect(xhr.url).toBe(INSTRUCTION.url);
    expect(xhr.async).toBe(true);
    expect(xhr.headers).toEqual({ 'Content-Type': 'application/pdf', 'x-amz-meta-document-id': 'd-1' });
    expect(Object.keys(xhr.headers).map((h) => h.toLowerCase())).not.toContain('authorization');
    expect(xhr.body).toBe(file);

    xhr.progress(5, 20);
    xhr.progress(10, 20);
    xhr.respond(200, '', { ETag: '"abc"' });

    expect(events).toEqual([
      { type: 'progress', loaded: 5, total: 20, percent: 25 },
      { type: 'progress', loaded: 10, total: 20, percent: 50 },
      { type: 'progress', loaded: file.size, total: file.size, percent: 100 },
      { type: 'done', etag: '"abc"' },
    ]);
    expect(done).toBe(true);
  });

  it('aborts the XHR when the subscriber unsubscribes (cancel)', () => {
    const sub = service.put(INSTRUCTION, pdfFile()).subscribe();
    const xhr = MockXhr.instances[0]!;
    xhr.progress(1, 10);
    sub.unsubscribe();
    expect(xhr.aborted).toBe(true);
  });

  it('does not abort after a completed transfer', () => {
    const sub = service.put(INSTRUCTION, pdfFile()).subscribe();
    const xhr = MockXhr.instances[0]!;
    xhr.respond(200);
    sub.unsubscribe();
    expect(xhr.aborted).toBe(false);
  });

  it('maps S3 XML errors to readable messages (expired link)', () => {
    let error: UploadError | undefined;
    service.put(INSTRUCTION, pdfFile()).subscribe({ error: (e: UploadError) => (error = e) });
    MockXhr.instances[0]!.respond(
      403,
      '<?xml version="1.0"?><Error><Code>AccessDenied</Code><Message>Request has expired</Message><RequestId>1</RequestId></Error>',
    );
    expect(error).toBeInstanceOf(UploadError);
    expect(error!.phase).toBe('put');
    expect(error!.status).toBe(403);
    expect(error!.code).toBe('AccessDenied');
    expect(error!.message).toMatch(/expired/i);
  });

  it('reports network failures (status 0) as a put error', () => {
    let error: UploadError | undefined;
    service.put(INSTRUCTION, pdfFile()).subscribe({ error: (e: UploadError) => (error = e) });
    MockXhr.instances[0]!.onerror?.();
    expect(error?.message).toMatch(/network error/i);
  });

  it('parseS3Error extracts Code and Message', () => {
    expect(parseS3Error('<Error><Code>SignatureDoesNotMatch</Code><Message>bad sig</Message></Error>')).toEqual({
      code: 'SignatureDoesNotMatch',
      message: 'bad sig',
    });
    expect(parseS3Error('')).toEqual({ code: null, message: null });
  });

  it('upload(): create → PUT → complete, emitting created/progress/verifying/completed', () => {
    const file = pdfFile('claims.pdf');
    const events: DirectUploadEvent[] = [];
    let completed = false;
    service
      .upload('a-1', file, { docCode: 'CLM', visibility: 'PUBLIC', description: '  amended  ' })
      .subscribe({ next: (e) => events.push(e), complete: () => (completed = true) });

    const create = http.expectOne('/api/v1/applications/a-1/documents');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual({
      docCode: 'CLM',
      fileName: 'claims.pdf',
      contentType: 'application/pdf',
      sizeBytes: file.size,
      visibility: 'PUBLIC',
      description: 'amended',
    });
    const pending = documentResponse({ id: 'd-9', docCode: 'CLM', status: 'PENDING_UPLOAD' });
    create.flush({ document: pending, upload: INSTRUCTION });

    const xhr = MockXhr.instances[0]!;
    xhr.progress(file.size, file.size);
    xhr.respond(200);

    const complete = http.expectOne('/api/v1/documents/d-9/complete');
    expect(complete.request.method).toBe('POST');
    const uploaded = documentResponse({ id: 'd-9', status: 'UPLOADED', redaction: null });
    complete.flush(uploaded);

    expect(events.map((e) => e.type)).toEqual(['created', 'progress', 'progress', 'verifying', 'completed']);
    expect((events.at(-1) as { document: DocumentResponse }).document.status).toBe('UPLOADED');
    expect(completed).toBe(true);
  });

  it('upload(): surfaces 422 validation errors from document creation', () => {
    let error: UploadError | undefined;
    service.upload('a-1', pdfFile(), { docCode: 'SPEC' }).subscribe({ error: (e: UploadError) => (error = e) });
    http
      .expectOne('/api/v1/applications/a-1/documents')
      .flush({ title: 'Unprocessable', status: 422, errors: [{ field: 'docCode', message: 'SPEC cannot be uploaded in this status' }] }, { status: 422, statusText: 'Unprocessable' });
    expect(MockXhr.instances.length).toBe(0);
    expect(error?.phase).toBe('create');
    expect(error?.message).toBe('SPEC cannot be uploaded in this status');
  });

  it('upload(): a rejected /complete (422) fails in the complete phase', () => {
    let error: UploadError | undefined;
    service.upload('a-1', pdfFile(), { docCode: 'SPEC' }).subscribe({ error: (e: UploadError) => (error = e) });
    http.expectOne('/api/v1/applications/a-1/documents').flush({ document: documentResponse({ status: 'PENDING_UPLOAD' }), upload: INSTRUCTION });
    MockXhr.instances[0]!.respond(200);
    http
      .expectOne('/api/v1/documents/d-1/complete')
      .flush({ title: 'Rejected', detail: 'The file is not a PDF', status: 422 }, { status: 422, statusText: 'Unprocessable' });
    expect(error?.phase).toBe('complete');
    expect(error?.message).toBe('The file is not a PDF');
  });

  it('pollDocument(): polls GET /documents/{id} every 3 s while redaction is PENDING/PROCESSING, then stops', () => {
    vi.useFakeTimers();
    const seen: string[] = [];
    let done = false;
    service.pollDocument('d-1').subscribe({ next: (d) => seen.push(d.redaction!.status), complete: () => (done = true) });

    vi.advanceTimersByTime(2999);
    http.expectNone('/api/v1/documents/d-1');
    vi.advanceTimersByTime(1);
    http.expectOne('/api/v1/documents/d-1').flush(documentResponse({ redaction: redaction('PROCESSING') }));
    vi.advanceTimersByTime(3000);
    http.expectOne('/api/v1/documents/d-1').flush(documentResponse({ redaction: redaction('COMPLETED') }));
    vi.advanceTimersByTime(9000);
    http.expectNone('/api/v1/documents/d-1');

    expect(seen).toEqual(['PROCESSING', 'COMPLETED']);
    expect(done).toBe(true);
  });
});

function redaction(status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED') {
  return {
    status,
    engine: null,
    model: null,
    pageCount: null,
    redactionCount: status === 'COMPLETED' ? 3 : null,
    categories: [],
    startedAt: null,
    completedAt: null,
    error: null,
    publicCopyAvailable: status === 'COMPLETED',
  };
}
