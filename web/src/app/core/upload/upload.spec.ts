import { Observable, Subject } from 'rxjs';

import { documentResponse, pdfFile } from '../../../testing/test-data';
import { type DirectUploadEvent, type DirectUploadService, UploadError } from './direct-upload.service';
import { UploadQueue } from './upload-queue';
import { checkPdfMeta, hasPdfMagic, validatePdf } from './upload-validation';

const LIMITS = { maxUploadBytes: 1024, allowedContentTypes: ['application/pdf'] };

describe('upload validation', () => {
  it('accepts a PDF within the limit', () => {
    expect(checkPdfMeta({ name: 'Spec.PDF', size: 100, type: 'application/pdf' }, LIMITS)).toBeNull();
    // Some platforms report no MIME type — name + magic bytes still apply.
    expect(checkPdfMeta({ name: 'spec.pdf', size: 100, type: '' }, LIMITS)).toBeNull();
  });

  it('rejects wrong extensions, MIME types, empty files and oversize files', () => {
    expect(checkPdfMeta({ name: 'spec.docx', size: 100, type: 'application/pdf' }, LIMITS)).toMatch(/\.pdf/);
    expect(checkPdfMeta({ name: 'spec.pdf', size: 100, type: 'image/png' }, LIMITS)).toMatch(/not a PDF/);
    expect(checkPdfMeta({ name: 'spec.pdf', size: 0, type: 'application/pdf' }, LIMITS)).toMatch(/empty/);
    expect(checkPdfMeta({ name: 'spec.pdf', size: 2048, type: 'application/pdf' }, LIMITS)).toMatch(/exceeds the 1\.0 KB/);
  });

  it('checks the %PDF- magic bytes', () => {
    expect(hasPdfMagic(new TextEncoder().encode('%PDF-'))).toBe(true);
    expect(hasPdfMagic(new TextEncoder().encode('%PDX-'))).toBe(false);
    expect(hasPdfMagic(new Uint8Array([0x25, 0x50]))).toBe(false);
  });

  it('validatePdf reads File.slice(0, 5) and rejects renamed non-PDFs', async () => {
    expect(await validatePdf(pdfFile('ok.pdf'), LIMITS)).toBeNull();
    expect(await validatePdf(pdfFile('fake.pdf', 'PK\u0003\u0004 zip'), LIMITS)).toMatch(/%PDF-/);
  });
});

describe('UploadQueue', () => {
  /** Fake uploader: each upload() returns a Subject the test completes by hand. */
  function fakeUploader() {
    const calls: { file: File; subject: Subject<DirectUploadEvent>; unsubscribed: boolean }[] = [];
    const uploader = {
      upload: (_app: string, file: File) => {
        const subject = new Subject<DirectUploadEvent>();
        const call = { file, subject, unsubscribed: false };
        calls.push(call);
        return new Observable<DirectUploadEvent>((sub) => {
          const inner = subject.subscribe(sub);
          return () => {
            call.unsubscribed = true;
            inner.unsubscribe();
          };
        });
      },
      pollDocument: () => new Subject().asObservable(),
    } as unknown as DirectUploadService;
    return { uploader, calls };
  }

  function queue(uploader: DirectUploadService) {
    return new UploadQueue(uploader, {
      applicationId: () => 'a-1',
      limits: () => ({ maxUploadBytes: 10_000, allowedContentTypes: ['application/pdf'] }),
      defaults: () => ({ docCode: 'SPEC', visibility: 'PUBLIC' }),
    });
  }

  it('validates added files and marks invalid ones', async () => {
    const { uploader } = fakeUploader();
    const q = queue(uploader);
    const [good, bad] = await q.add([pdfFile('a.pdf'), pdfFile('b.txt')]);
    expect(good!.state()).toBe('ready');
    expect(bad!.state()).toBe('invalid');
    expect(bad!.error()).toMatch(/PDF/);
  });

  it('uploads at most 2 files at a time and starts the next when one finishes', async () => {
    const { uploader, calls } = fakeUploader();
    const q = queue(uploader);
    await q.add([pdfFile('1.pdf'), pdfFile('2.pdf'), pdfFile('3.pdf')]);
    q.start();

    expect(calls.map((c) => c.file.name)).toEqual(['1.pdf', '2.pdf']);
    expect(q.tasks()[2]!.state()).toBe('queued');

    calls[0]!.subject.next({ type: 'progress', loaded: 5, total: 10, percent: 50 });
    expect(q.tasks()[0]!.progress()).toBe(50);
    calls[0]!.subject.next({ type: 'completed', document: documentResponse({ redaction: null }) });
    calls[0]!.subject.complete();

    expect(q.tasks()[0]!.state()).toBe('done');
    expect(calls.map((c) => c.file.name)).toEqual(['1.pdf', '2.pdf', '3.pdf']);
  });

  it('cancel aborts the in-flight upload; retry re-queues it', async () => {
    const { uploader, calls } = fakeUploader();
    const q = queue(uploader);
    const [task] = await q.add([pdfFile('1.pdf')]);
    q.start();
    q.cancel(task!);
    expect(calls[0]!.unsubscribed).toBe(true);
    expect(task!.state()).toBe('cancelled');

    q.retry(task!);
    expect(calls.length).toBe(2);
    expect(task!.state()).toBe('uploading');
  });

  it('marks failures with the upload error message', async () => {
    const { uploader, calls } = fakeUploader();
    const q = queue(uploader);
    const [task] = await q.add([pdfFile('1.pdf')]);
    q.start();
    calls[0]!.subject.error(new UploadError('The secure upload link expired', 'put', 403));
    expect(task!.state()).toBe('failed');
    expect(task!.error()).toBe('The secure upload link expired');
  });
});
