import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleCheck,
  lucideCircleX,
  lucideCloudUpload,
  lucideFileText,
  lucideLoaderCircle,
  lucidePlay,
  lucideRotateCw,
  lucideShieldCheck,
  lucideTrash2,
  lucideX,
} from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardProgressComponent } from '@/shared/components/progress';
import { ZardSelectImports } from '@/shared/components/select';

import { ReferenceStore } from '../../../core/api/reference.api';
import { formatBytes } from '../../../core/format';
import type { DocumentCode, DocumentResponse, Visibility } from '../../../core/models';
import { DirectUploadService } from '../../../core/upload/direct-upload.service';
import { UploadQueue, type UploadState, type UploadTask } from '../../../core/upload/upload-queue';
import { RedactionChipComponent } from '../../../ui/redaction-chip.component';

/** Guesses a document code from a file name (`claims.pdf` → CLM), limited to the allowed codes. */
export function guessDocCode(fileName: string, allowed: readonly DocumentCode[]): DocumentCode | null {
  const n = fileName.toLowerCase();
  const rules: [RegExp, DocumentCode][] = [
    [/claim/, 'CLM'],
    [/abstract|abst/, 'ABST'],
    [/spec|description/, 'SPEC'],
    [/draw|figure|fig\b|sheet/, 'DRW'],
    [/data.?sheet|\bads\b/, 'ADS'],
    [/oath|declaration/, 'OATH'],
    [/\bids\b|disclosure/, 'IDS'],
    [/transmittal/, 'TRNA'],
    [/response|reply|amend/, 'RESP'],
    [/non.?final|ctnf/, 'CTNF'],
    [/final.?rej|ctfr/, 'CTFR'],
    [/allowance|\bnoa\b/, 'NOA'],
    [/missing.?parts|ntmp/, 'NTMP'],
  ];
  for (const [re, code] of rules) {
    if (re.test(n) && allowed.includes(code)) return code;
  }
  return null;
}

const STATE_LABEL: Record<UploadState, string> = {
  checking: 'Checking file…',
  invalid: 'Not accepted',
  ready: 'Ready to upload',
  queued: 'Waiting…',
  uploading: 'Uploading',
  verifying: 'Verifying on storage…',
  processing: 'Uploaded — redacting public copy…',
  done: 'Uploaded',
  failed: 'Failed',
  cancelled: 'Cancelled',
};

/**
 * Drag & drop / keyboard-operable drop zone + per-file queue. Each file gets a document-code and
 * visibility selector (defaults from `/reference`), a progress bar, and cancel / retry. Uploads go
 * directly to S3 (2 at a time) via {@link UploadQueue}.
 */
@Component({
  selector: 'app-upload-panel',
  imports: [NgIcon, ZardButtonComponent, ZardInputComponent, ZardProgressComponent, RedactionChipComponent, ...ZardSelectImports],
  viewProviders: [
    provideIcons({
      lucideCloudUpload,
      lucideFileText,
      lucideX,
      lucideRotateCw,
      lucidePlay,
      lucideTrash2,
      lucideCircleCheck,
      lucideCircleX,
      lucideLoaderCircle,
      lucideShieldCheck,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-4">
      <div
        role="button"
        tabindex="0"
        class="focus-visible:ring-ring/50 flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed px-6 py-8 text-center transition-colors outline-none focus-visible:ring-2"
        [class]="dragging() ? 'border-primary bg-primary/5' : 'hover:bg-muted/50 hover:border-primary/40'"
        aria-describedby="drop-hint"
        [attr.aria-label]="'Add PDF documents: drop files here or press Enter to browse'"
        (click)="picker.click()"
        (keydown.enter)="$event.preventDefault(); picker.click()"
        (keydown.space)="$event.preventDefault(); picker.click()"
        (dragover)="onDragOver($event)"
        (dragleave)="dragging.set(false)"
        (drop)="onDrop($event)"
      >
        <span class="bg-primary/10 text-primary grid size-12 place-items-center rounded-full">
          <ng-icon name="lucideCloudUpload" class="size-6" aria-hidden="true" />
        </span>
        <span class="font-medium">Drag &amp; drop PDFs here, or <span class="text-primary underline underline-offset-2">browse</span></span>
        <span id="drop-hint" class="text-muted-foreground text-xs">
          PDF only · up to {{ maxSize() }} each · files go directly to encrypted storage
        </span>
        <input #picker type="file" class="sr-only" tabindex="-1" aria-hidden="true" multiple accept="application/pdf,.pdf" (change)="onPick($event)" />
      </div>

      @if (queue.tasks().length) {
        <ul class="divide-y rounded-xl border" aria-label="Upload queue">
          @for (t of queue.tasks(); track t.id) {
            <li class="space-y-3 p-4">
              <div class="flex items-start gap-3">
                <ng-icon name="lucideFileText" class="text-muted-foreground mt-0.5 size-5 shrink-0" aria-hidden="true" />
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium" [title]="t.file.name">{{ t.file.name }}</p>
                  <p class="text-muted-foreground text-xs">
                    {{ bytes(t.file.size) }} ·
                    <span [class]="stateClass(t)" role="status">{{ stateLabel(t) }}</span>
                  </p>
                </div>
                <div class="flex shrink-0 items-center gap-1">
                  @if (t.state() === 'ready') {
                    <button type="button" z-button zSize="sm" (click)="queue.start(t)"><ng-icon name="lucidePlay" aria-hidden="true" /> Upload</button>
                  }
                  @if (t.state() === 'failed' || t.state() === 'cancelled') {
                    <button type="button" z-button zType="outline" zSize="sm" (click)="queue.retry(t)"><ng-icon name="lucideRotateCw" aria-hidden="true" /> Retry</button>
                  }
                  @if (t.active() || t.state() === 'queued') {
                    <button type="button" z-button zType="ghost" zSize="sm" (click)="queue.cancel(t)"><ng-icon name="lucideX" aria-hidden="true" /> Cancel</button>
                  } @else {
                    <button type="button" z-button zType="ghost" zSize="icon-sm" [attr.aria-label]="'Remove ' + t.file.name + ' from the list'" (click)="queue.remove(t)">
                      <ng-icon name="lucideTrash2" aria-hidden="true" />
                    </button>
                  }
                </div>
              </div>

              @if (t.editable()) {
                <div class="grid gap-3 sm:grid-cols-[1.4fr_1fr_1.4fr]">
                  <div class="space-y-1">
                    <span class="text-muted-foreground text-xs font-medium">Document type</span>
                    <z-select [zAriaLabel]="'Document type for ' + t.file.name" [zValue]="t.docCode()" (zSelectionChange)="setCode(t, $event)">
                      @for (c of codes(); track c.code) {
                        <z-select-item [zValue]="c.code">{{ c.code }} — {{ c.label }}</z-select-item>
                      }
                    </z-select>
                  </div>
                  <div class="space-y-1">
                    <span class="text-muted-foreground text-xs font-medium">Visibility</span>
                    <z-select [zAriaLabel]="'Visibility for ' + t.file.name" [zValue]="t.visibility()" (zSelectionChange)="setVisibility(t, $event)">
                      <z-select-item zValue="PUBLIC">Public (redacted copy)</z-select-item>
                      <z-select-item zValue="RESTRICTED">Restricted</z-select-item>
                    </z-select>
                  </div>
                  <div class="space-y-1">
                    <label class="text-muted-foreground text-xs font-medium" [for]="'desc-' + t.id">Description (optional)</label>
                    <input z-input [id]="'desc-' + t.id" maxlength="200" [value]="t.description()" (input)="t.description.set($any($event.target).value)" />
                  </div>
                </div>
                @if (t.visibility() === 'PUBLIC') {
                  <p class="text-muted-foreground flex items-center gap-1.5 text-xs">
                    <ng-icon name="lucideShieldCheck" class="size-3.5" aria-hidden="true" /> A sanitized public copy is generated automatically; the original stays private.
                  </p>
                }
              } @else if (t.state() !== 'invalid') {
                <p class="text-muted-foreground text-xs">
                  <span class="font-mono font-semibold">{{ t.docCode() }}</span> · {{ t.visibility() === 'PUBLIC' ? 'Public (redacted copy)' : 'Restricted' }}
                </p>
              }

              @if (t.active() || t.state() === 'queued') {
                <div class="space-y-1">
                  <z-progress [value]="t.progress()" [attr.aria-label]="'Upload progress for ' + t.file.name" [attr.aria-valuenow]="t.progress()" aria-valuemin="0" aria-valuemax="100" />
                  <p class="text-muted-foreground tabular flex justify-between text-[11px]">
                    <span>{{ bytes(t.loaded()) }} of {{ bytes(t.file.size) }}</span><span>{{ t.progress() }}%</span>
                  </p>
                </div>
              }

              @if (t.state() === 'processing' || t.state() === 'done') {
                <app-redaction-chip [redaction]="t.document()?.redaction" [visibility]="t.visibility()" />
              }

              @if (t.error()) {
                <p class="field-error" role="alert">{{ t.error() }}</p>
              }
            </li>
          }
        </ul>

        <div class="flex flex-wrap items-center justify-between gap-2">
          <p class="text-muted-foreground text-xs" aria-live="polite">
            @if (queue.busy()) {
              Uploading {{ queue.activeCount() }} of {{ queue.pendingCount() }} · at most {{ queue.concurrency }} at a time
            } @else {
              {{ queue.tasks().length }} file(s) in the list
            }
          </p>
          <div class="flex gap-2">
            <button type="button" z-button zType="ghost" zSize="sm" (click)="queue.clearFinished()">Clear finished</button>
            <button type="button" z-button zSize="sm" [zDisabled]="!queue.readyCount()" (click)="queue.start()">
              <ng-icon name="lucideCloudUpload" aria-hidden="true" /> Upload {{ queue.readyCount() || '' }} ready file{{ queue.readyCount() === 1 ? '' : 's' }}
            </button>
          </div>
        </div>
      }
    </div>
  `,
})
export class UploadPanelComponent {
  private readonly reference = inject(ReferenceStore);

  readonly applicationId = input.required<string>();
  /** `permissions.uploadableCodes` */
  readonly allowedCodes = input.required<DocumentCode[]>();
  /** Every server-side update of an uploaded document (for the documents table). */
  readonly documentChange = output<DocumentResponse>();

  protected readonly dragging = signal(false);
  protected readonly codes = computed(() => this.allowedCodes().map((c) => ({ code: c, label: this.reference.docLabel(c) })));
  protected readonly maxSize = computed(() => formatBytes(this.reference.limits().maxUploadBytes));

  protected readonly queue = new UploadQueue(inject(DirectUploadService), {
    applicationId: () => this.applicationId(),
    limits: () => this.reference.limits(),
    defaults: (file) => {
      const allowed = this.allowedCodes();
      const code = guessDocCode(file.name, allowed) ?? allowed[0] ?? 'MISC';
      return { docCode: code, visibility: this.reference.defaultVisibility(code) };
    },
    onDocument: (doc) => this.documentChange.emit(doc),
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.queue.destroy());
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
    this.dragging.set(true);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    void this.queue.add(Array.from(event.dataTransfer?.files ?? []));
  }

  protected onPick(event: Event): void {
    const el = event.target as HTMLInputElement;
    void this.queue.add(Array.from(el.files ?? []));
    el.value = '';
  }

  protected setCode(t: UploadTask, value: string | string[]): void {
    const code = (Array.isArray(value) ? value[0] : value) as DocumentCode;
    if (!code) return;
    t.docCode.set(code);
    t.visibility.set(this.reference.defaultVisibility(code));
  }

  protected setVisibility(t: UploadTask, value: string | string[]): void {
    const v = (Array.isArray(value) ? value[0] : value) as Visibility;
    if (v) t.visibility.set(v);
  }

  protected bytes(n: number): string {
    return formatBytes(n);
  }

  protected stateLabel(t: UploadTask): string {
    return STATE_LABEL[t.state()];
  }

  protected stateClass(t: UploadTask): string {
    switch (t.state()) {
      case 'done':
        return 'text-emerald-700 dark:text-emerald-400 font-medium';
      case 'invalid':
      case 'failed':
        return 'text-destructive font-medium';
      case 'uploading':
      case 'verifying':
      case 'processing':
        return 'text-primary font-medium';
      default:
        return '';
    }
  }
}
