import { computed, signal, type WritableSignal } from '@angular/core';

import type { Subscription } from 'rxjs';

import { type DocumentCode, type DocumentResponse, isRedactionActive, type Uuid, type Visibility } from '../models';
import { type DirectUploadService, UploadError } from './direct-upload.service';
import { type UploadLimits, validatePdf } from './upload-validation';

export type UploadState =
  | 'checking' // client-side validation running
  | 'invalid' // failed client-side validation
  | 'ready' // waiting for the user to confirm code / visibility
  | 'queued'
  | 'uploading'
  | 'verifying' // POST /complete
  | 'processing' // uploaded; redaction PENDING/PROCESSING
  | 'done'
  | 'failed'
  | 'cancelled';

let nextId = 0;

/** One file in the queue. All fields are signals so the template updates as the upload progresses. */
export class UploadTask {
  readonly id = ++nextId;
  readonly state = signal<UploadState>('checking');
  readonly progress = signal(0);
  readonly loaded = signal(0);
  readonly error = signal<string | null>(null);
  readonly document = signal<DocumentResponse | null>(null);
  readonly docCode: WritableSignal<DocumentCode>;
  readonly visibility: WritableSignal<Visibility>;
  readonly description = signal('');

  /** Editable (code / visibility / description) until the transfer starts. */
  readonly editable = computed(() => ['ready', 'failed', 'cancelled', 'queued'].includes(this.state()));
  readonly active = computed(() => this.state() === 'uploading' || this.state() === 'verifying');
  readonly finished = computed(() => ['done', 'processing'].includes(this.state()));

  /** @internal */ sub: Subscription | null = null;
  /** @internal */ pollSub: Subscription | null = null;

  constructor(
    readonly file: File,
    docCode: DocumentCode,
    visibility: Visibility,
  ) {
    this.docCode = signal(docCode);
    this.visibility = signal(visibility);
  }
}

export interface UploadQueueOptions {
  applicationId: () => Uuid;
  limits: () => UploadLimits;
  /** Default doc code + visibility for a newly added file. */
  defaults: (file: File) => { docCode: DocumentCode; visibility: Visibility };
  concurrency?: number;
  /** Called with every server-side document update (completed, redaction progress). */
  onDocument?: (doc: DocumentResponse) => void;
}

/**
 * Client-side upload queue: validates files, uploads at most `concurrency` (2) at a time via
 * {@link DirectUploadService}, supports cancel / retry per file, then polls redaction status of
 * public documents until the public copy is ready. Call `destroy()` when the host is destroyed.
 */
export class UploadQueue {
  readonly tasks = signal<UploadTask[]>([]);
  readonly concurrency: number;

  readonly readyCount = computed(() => this.tasks().filter((t) => t.state() === 'ready').length);
  readonly activeCount = computed(() => this.tasks().filter((t) => t.active()).length);
  readonly pendingCount = computed(() => this.tasks().filter((t) => t.state() === 'queued' || t.active()).length);
  readonly busy = computed(() => this.pendingCount() > 0);

  constructor(
    private readonly uploader: DirectUploadService,
    private readonly options: UploadQueueOptions,
  ) {
    this.concurrency = Math.max(1, options.concurrency ?? 2);
  }

  /** Adds files and validates them (name, type, size, `%PDF-` header). Resolves with the new tasks. */
  async add(files: readonly File[]): Promise<UploadTask[]> {
    const added = files.map((file) => {
      const d = this.options.defaults(file);
      return new UploadTask(file, d.docCode, d.visibility);
    });
    this.tasks.update((cur) => [...cur, ...added]);
    await Promise.all(
      added.map(async (task) => {
        const error = await validatePdf(task.file, this.options.limits());
        if (task.state() !== 'checking') return; // removed meanwhile
        if (error) {
          task.error.set(error);
          task.state.set('invalid');
        } else {
          task.state.set('ready');
        }
      }),
    );
    return added;
  }

  /** Queues one task (or every ready task) and starts uploading. */
  start(task?: UploadTask): void {
    const targets = task ? [task] : this.tasks().filter((t) => t.state() === 'ready');
    for (const t of targets) {
      if (t.state() === 'ready' || t.state() === 'failed' || t.state() === 'cancelled') {
        t.error.set(null);
        t.progress.set(0);
        t.loaded.set(0);
        t.state.set('queued');
      }
    }
    this.pump();
  }

  retry(task: UploadTask): void {
    this.start(task);
  }

  /** Aborts an in-flight transfer (XHR abort) or dequeues a waiting one. */
  cancel(task: UploadTask): void {
    task.sub?.unsubscribe();
    task.sub = null;
    if (task.state() === 'queued' || task.active()) {
      task.state.set('cancelled');
      task.error.set('Upload cancelled.');
    }
    this.pump();
  }

  remove(task: UploadTask): void {
    this.cancel(task);
    task.pollSub?.unsubscribe();
    task.state.set('cancelled');
    this.tasks.update((cur) => cur.filter((t) => t !== task));
  }

  /** Drops finished / invalid / cancelled rows. */
  clearFinished(): void {
    for (const t of this.tasks()) {
      if (['done', 'invalid', 'cancelled'].includes(t.state())) t.pollSub?.unsubscribe();
    }
    this.tasks.update((cur) => cur.filter((t) => !['done', 'invalid', 'cancelled'].includes(t.state())));
  }

  /** Cancels everything (in-flight transfers and polls). */
  destroy(): void {
    for (const t of this.tasks()) {
      t.sub?.unsubscribe();
      t.pollSub?.unsubscribe();
    }
  }

  private pump(): void {
    let active = this.tasks().filter((t) => t.active()).length;
    for (const task of this.tasks()) {
      if (active >= this.concurrency) break;
      if (task.state() === 'queued') {
        active++;
        this.run(task);
      }
    }
  }

  private run(task: UploadTask): void {
    task.state.set('uploading');
    task.sub = this.uploader
      .upload(this.options.applicationId(), task.file, {
        docCode: task.docCode(),
        visibility: task.visibility(),
        description: task.description(),
      })
      .subscribe({
        next: (event) => {
          switch (event.type) {
            case 'created':
              task.document.set(event.document);
              break;
            case 'progress':
              task.progress.set(event.percent);
              task.loaded.set(event.loaded);
              break;
            case 'verifying':
              task.state.set('verifying');
              break;
            case 'completed':
              this.onCompleted(task, event.document);
              break;
          }
        },
        error: (err: unknown) => {
          task.sub = null;
          task.state.set('failed');
          task.error.set(err instanceof UploadError ? err.message : 'The upload failed. Please retry.');
          const doc = task.document();
          if (doc) this.options.onDocument?.(doc);
          this.pump();
        },
        complete: () => {
          task.sub = null;
          this.pump();
        },
      });
  }

  private onCompleted(task: UploadTask, doc: DocumentResponse): void {
    task.document.set(doc);
    task.progress.set(100);
    this.options.onDocument?.(doc);
    if (doc.status === 'REJECTED') {
      task.state.set('failed');
      task.error.set(doc.rejectionReason ?? 'Storage verification rejected the file.');
      return;
    }
    if (!isRedactionActive(doc)) {
      task.state.set('done');
      return;
    }
    task.state.set('processing');
    task.pollSub = this.uploader.pollDocument(doc.id).subscribe({
      next: (d) => {
        task.document.set(d);
        this.options.onDocument?.(d);
        if (!isRedactionActive(d)) task.state.set('done');
      },
      complete: () => {
        if (task.state() === 'processing') task.state.set('done');
      },
    });
  }
}
