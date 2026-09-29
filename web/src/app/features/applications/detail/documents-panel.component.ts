import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideDownload,
  lucideEye,
  lucideEyeOff,
  lucideFileCheck2,
  lucideFileText,
  lucideRefreshCw,
  lucideRotateCw,
  lucideShieldCheck,
  lucideTrash2,
} from '@ng-icons/lucide';

import { ZardAlertDialogService } from '@/shared/components/alert-dialog';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardEmptyComponent } from '@/shared/components/empty';
import { ZardTableImports } from '@/shared/components/table';
import { ZardTooltipImports } from '@/shared/components/tooltip';

import { DocumentsApi } from '../../../core/api/documents.api';
import { AuthStore } from '../../../core/auth/auth.store';
import { formatBytes, formatDateTime, formatRelative } from '../../../core/format';
import { type ApplicationDetail, type DocumentResponse, type DownloadCopy, isRedactionActive } from '../../../core/models';
import { Notifier } from '../../../core/notify/notifier.service';
import { startDownload } from '../../../ui/download';
import { RedactionChipComponent } from '../../../ui/redaction-chip.component';
import { StatusBadgeComponent } from '../../../ui/status-badge.component';
import { UploadPanelComponent } from './upload-panel.component';

/** Contract delete rule: owner, when the application is DRAFT or the document is PENDING_UPLOAD / REJECTED / EXPIRED. */
export function canDeleteDocument(app: Pick<ApplicationDetail, 'status' | 'owner'>, doc: Pick<DocumentResponse, 'status'>, viewerId: string | null): boolean {
  if (!viewerId || app.owner?.id !== viewerId) return false;
  return app.status === 'DRAFT' || doc.status === 'PENDING_UPLOAD' || doc.status === 'REJECTED' || doc.status === 'EXPIRED';
}

@Component({
  selector: 'app-documents-panel',
  imports: [
    NgIcon,
    ZardButtonComponent,
    ZardEmptyComponent,
    ...ZardTableImports,
    ...ZardTooltipImports,
    StatusBadgeComponent,
    RedactionChipComponent,
    UploadPanelComponent,
  ],
  viewProviders: [
    provideIcons({ lucideDownload, lucideShieldCheck, lucideTrash2, lucideRotateCw, lucideRefreshCw, lucideFileText, lucideEye, lucideEyeOff, lucideFileCheck2 }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="space-y-6">
      @if (app().permissions.canUpload && app().permissions.uploadableCodes.length) {
        <section aria-labelledby="upload-h" class="space-y-3">
          <div>
            <h3 id="upload-h" class="font-semibold">Upload documents</h3>
            <p class="text-muted-foreground text-sm">
              You can upload: <span class="font-mono text-xs">{{ app().permissions.uploadableCodes.join(', ') }}</span>
            </p>
          </div>
          <app-upload-panel [applicationId]="app().id" [allowedCodes]="app().permissions.uploadableCodes" (documentChange)="documentChange.emit($event)" />
        </section>
      }

      <section aria-labelledby="docs-h" class="space-y-3">
        <div class="flex items-center justify-between gap-2">
          <h3 id="docs-h" class="font-semibold">
            Documents <span class="text-muted-foreground font-normal">({{ documents().length }})</span>
          </h3>
          <div class="flex items-center gap-2">
            @if (processing()) {
              <span class="text-muted-foreground flex items-center gap-1.5 text-xs" role="status">
                <span class="size-2 animate-pulse rounded-full bg-violet-500" aria-hidden="true"></span> Redaction in progress — updating live
              </span>
            }
            <button type="button" z-button zType="outline" zSize="sm" (click)="reload.emit()" aria-label="Refresh documents">
              <ng-icon name="lucideRefreshCw" [class.animate-spin]="loading()" aria-hidden="true" />
            </button>
          </div>
        </div>

        @if (!documents().length) {
          <z-empty class="rounded-xl border border-dashed py-10" zIcon="lucideFileText" zTitle="No documents yet" [zDescription]="emptyText()" />
        } @else {
          <div class="bg-card relative overflow-x-auto rounded-xl border">
            <table z-table class="min-w-[46rem]">
              <caption class="sr-only">Documents of this application, newest first</caption>
              <thead z-table-header>
                <tr z-table-row>
                  <th z-table-head scope="col">Document</th>
                  <th z-table-head scope="col">Uploaded</th>
                  <th z-table-head scope="col">Status</th>
                  <th z-table-head scope="col">Public copy</th>
                  <th z-table-head scope="col" class="text-right"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody z-table-body>
                @for (d of documents(); track d.id) {
                  <tr z-table-row class="align-top">
                    <td z-table-cell class="max-w-72">
                      <div class="flex items-start gap-2">
                        <span class="bg-primary/10 text-primary mt-0.5 rounded px-1.5 py-0.5 font-mono text-[11px] font-semibold">{{ d.docCode }}</span>
                        <div class="min-w-0">
                          <p class="truncate text-sm font-medium">{{ d.docCodeLabel }}</p>
                          <p class="text-muted-foreground truncate text-xs" [title]="d.fileName">{{ d.fileName }}</p>
                          @if (d.description) {
                            <p class="text-muted-foreground truncate text-xs italic">{{ d.description }}</p>
                          }
                          <p class="mt-0.5 flex flex-wrap items-center gap-x-2 text-[11px]">
                            <span class="inline-flex items-center gap-1 font-medium" [class]="d.visibility === 'PUBLIC' ? 'text-sky-800 dark:text-sky-300' : 'text-amber-800 dark:text-amber-300'">
                              <ng-icon [name]="d.visibility === 'PUBLIC' ? 'lucideEye' : 'lucideEyeOff'" class="size-3" aria-hidden="true" />
                              {{ d.visibility === 'PUBLIC' ? 'Public' : 'Restricted' }}
                            </span>
                            <span class="text-muted-foreground tabular">{{ bytes(d.sizeBytes) }}</span>
                            @if (d.source === 'EXAMINER') {
                              <span class="text-primary font-medium">Office document</span>
                            }
                          </p>
                        </div>
                      </div>
                    </td>
                    <td z-table-cell class="text-xs">
                      <span class="block">{{ d.uploadedBy?.displayName ?? '—' }}</span>
                      <span class="text-muted-foreground block whitespace-nowrap" [title]="dateTime(d.uploadedAt ?? d.createdAt)">{{ relative(d.uploadedAt ?? d.createdAt) }}</span>
                    </td>
                    <td z-table-cell>
                      <app-status-badge [status]="d.status" />
                      @if (d.rejectionReason) {
                        <p class="text-destructive mt-1 max-w-44 text-xs">{{ d.rejectionReason }}</p>
                      }
                    </td>
                    <td z-table-cell><app-redaction-chip [redaction]="d.redaction" [visibility]="d.visibility" /></td>
                    <td z-table-cell class="text-right">
                      <div class="flex justify-end gap-1">
                        @if (d.status === 'UPLOADED') {
                          <button
                            type="button"
                            z-button
                            zType="ghost"
                            zSize="icon-sm"
                            zTooltip="Download original"
                            [attr.aria-label]="'Download original of ' + d.fileName"
                            [zLoading]="busy() === d.id + ':original'"
                            (click)="download(d, 'original')"
                          >
                            <ng-icon name="lucideDownload" aria-hidden="true" />
                          </button>
                        }
                        @if (d.redaction?.publicCopyAvailable) {
                          <button
                            type="button"
                            z-button
                            zType="ghost"
                            zSize="icon-sm"
                            zTooltip="Download public (redacted) copy"
                            [attr.aria-label]="'Download public copy of ' + d.fileName"
                            [zLoading]="busy() === d.id + ':public'"
                            (click)="download(d, 'public')"
                          >
                            <ng-icon name="lucideShieldCheck" aria-hidden="true" />
                          </button>
                        }
                        @if (canRetry(d)) {
                          <button
                            type="button"
                            z-button
                            zType="ghost"
                            zSize="icon-sm"
                            zTooltip="Retry redaction"
                            [attr.aria-label]="'Retry redaction of ' + d.fileName"
                            [zLoading]="busy() === d.id + ':retry'"
                            (click)="retry(d)"
                          >
                            <ng-icon name="lucideRotateCw" aria-hidden="true" />
                          </button>
                        }
                        @if (canDelete(d)) {
                          <button
                            type="button"
                            z-button
                            zType="ghost"
                            zSize="icon-sm"
                            class="text-destructive"
                            zTooltip="Delete document"
                            [attr.aria-label]="'Delete ' + d.fileName"
                            (click)="remove(d)"
                          >
                            <ng-icon name="lucideTrash2" aria-hidden="true" />
                          </button>
                        }
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `,
})
export class DocumentsPanelComponent {
  private readonly api = inject(DocumentsApi);
  private readonly auth = inject(AuthStore);
  private readonly confirm = inject(ZardAlertDialogService);
  private readonly notifier = inject(Notifier);

  readonly app = input.required<ApplicationDetail>();
  readonly documents = input.required<DocumentResponse[]>();
  readonly loading = input(false);
  readonly documentChange = output<DocumentResponse>();
  readonly documentRemoved = output<string>();
  readonly reload = output<void>();

  protected readonly busy = signal<string | null>(null);
  protected readonly processing = computed(() => this.documents().some((d) => isRedactionActive(d)));
  protected readonly emptyText = computed(() =>
    this.app().permissions.canUpload ? 'Add PDFs with the upload area above.' : 'No documents have been filed for this application.',
  );

  protected canDelete(d: DocumentResponse): boolean {
    return canDeleteDocument(this.app(), d, this.auth.user()?.id ?? null);
  }

  protected canRetry(d: DocumentResponse): boolean {
    return this.auth.isExaminer() && d.visibility === 'PUBLIC' && d.status === 'UPLOADED' && !isRedactionActive(d);
  }

  protected download(d: DocumentResponse, copy: DownloadCopy): void {
    this.busy.set(`${d.id}:${copy}`);
    this.api.download(d.id, copy).subscribe({
      next: (res) => {
        this.busy.set(null);
        startDownload(res);
      },
      error: () => this.busy.set(null),
    });
  }

  protected retry(d: DocumentResponse): void {
    this.busy.set(`${d.id}:retry`);
    this.api.retryRedaction(d.id).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.notifier.info('Redaction restarted', { description: d.fileName });
        this.documentChange.emit(updated);
      },
      error: () => this.busy.set(null),
    });
  }

  protected remove(d: DocumentResponse): void {
    this.confirm.create({
      zTitle: `Delete “${d.fileName}”?`,
      zDescription: 'The document and its stored file are removed permanently. This cannot be undone.',
      zOkText: 'Delete document',
      zOkDestructive: true,
      zCancelText: 'Cancel',
      zOnOk: () => {
        this.api.remove(d.id).subscribe(() => {
          this.notifier.success('Document deleted', { description: d.fileName });
          this.documentRemoved.emit(d.id);
        });
      },
    });
  }

  protected bytes(n: number): string {
    return formatBytes(n);
  }

  protected relative(v: string | null): string {
    return formatRelative(v);
  }

  protected dateTime(v: string | null): string {
    return formatDateTime(v);
  }
}
