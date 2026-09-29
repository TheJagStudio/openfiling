import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowLeft, lucideLock, lucidePrinter, lucideScanSearch, lucideShieldCheck } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardTableImports } from '@/shared/components/table';

import { PublicApi } from '../../core/api/public.api';
import { formatApplicationNumber, parseApplicationNumber } from '../../core/application-number';
import { formatDate, formatDateTime } from '../../core/format';
import { toProblem } from '../../core/http/problem';
import { APPLICATION_TYPES, type PublicDocument, STATUS_META } from '../../core/models';
import { TONE_CLASSES, TONE_SOLID, toneOfApplicationStatus, toneOfCategory } from '../../core/status-tone';
import { OpenFilingTitleStrategy } from '../../core/title.strategy';
import { startDownload } from '../../ui/download';
import { ErrorStateComponent } from '../../ui/error-state.component';

/** TSDR-style public status page for a published application. 404 → confidentiality explanation. */
@Component({
  selector: 'app-public-status',
  imports: [RouterLink, NgIcon, ZardButtonComponent, ZardSkeletonComponent, ...ZardTableImports, ErrorStateComponent],
  viewProviders: [provideIcons({ lucideArrowLeft, lucideShieldCheck, lucideLock, lucidePrinter, lucideScanSearch })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-6xl px-4 py-8 sm:px-6">
      <div class="mb-6 flex flex-wrap items-center justify-between gap-2 print:hidden">
        <a z-button zType="ghost" routerLink="/status"><ng-icon name="lucideArrowLeft" aria-hidden="true" /> New lookup</a>
        @if (detail.hasValue()) {
          <button type="button" z-button zType="outline" (click)="print()"><ng-icon name="lucidePrinter" aria-hidden="true" /> Print</button>
        }
      </div>

      @if (detail.isLoading() && !detail.hasValue()) {
        <div class="space-y-4"><z-skeleton class="h-32 w-full" /><z-skeleton class="h-64 w-full" /></div>
      } @else if (notFound()) {
        <div class="mx-auto max-w-2xl rounded-2xl border p-8 text-center sm:p-12">
          <span class="bg-muted mx-auto grid size-14 place-items-center rounded-full"><ng-icon name="lucideLock" class="text-muted-foreground size-6" aria-hidden="true" /></span>
          <h1 class="mt-4 text-2xl font-semibold">No public record for {{ display() }}</h1>
          <p class="text-muted-foreground mt-3">
            Either the number is incorrect, or the application has not been published. Unpublished patent applications are kept confidential and
            their status is not disclosed to the public.
          </p>
          <div class="mt-6 flex flex-wrap justify-center gap-2">
            <a z-button routerLink="/status"><ng-icon name="lucideScanSearch" aria-hidden="true" /> Try another number</a>
            <a z-button zType="outline" routerLink="/login">Applicant? Sign in</a>
          </div>
        </div>
      } @else if (detail.error()) {
        <app-error-state [error]="detail.error()" (retry)="detail.reload()" />
      } @else if (detail.hasValue()) {
        @let d = detail.value();
        <!-- Status banner -->
        <section class="overflow-hidden rounded-2xl border" aria-labelledby="pub-title">
          <div class="bg-navy text-navy-foreground px-6 py-5">
            <p class="text-xs font-semibold tracking-[0.14em] uppercase opacity-80">Application {{ d.applicationNumberDisplay }} · {{ typeLabel(d.applicationType) }}</p>
            <h1 id="pub-title" class="mt-1 text-2xl font-semibold text-balance">{{ d.title }}</h1>
          </div>
          <div class="bg-card grid gap-6 p-6 md:grid-cols-[1fr_auto]">
            <div>
              <p class="text-muted-foreground text-xs font-semibold tracking-wider uppercase">Status</p>
              <p class="mt-1 flex flex-wrap items-center gap-2 text-lg font-semibold">
                <span class="rounded-full px-2.5 py-0.5 text-sm ring-1 ring-inset" [class]="toneClasses()">{{ meta()?.short ?? d.status }}</span>
                {{ d.statusLabel }}
              </p>
              <p class="text-muted-foreground mt-2 max-w-2xl text-sm">{{ meta()?.explanation }}</p>
            </div>
            <dl class="grid grid-cols-2 gap-x-6 gap-y-2 text-sm md:text-right">
              <dt class="text-muted-foreground">Filed</dt><dd class="font-medium">{{ date(d.filingDate) }}</dd>
              <dt class="text-muted-foreground">Publication</dt><dd class="font-mono">{{ d.publicationNumber ?? '—' }}</dd>
              <dt class="text-muted-foreground">Published</dt><dd>{{ date(d.publicationDate) }}</dd>
              @if (d.patentNumber) {
                <dt class="text-muted-foreground">Patent</dt><dd class="font-mono font-semibold text-emerald-700 dark:text-emerald-400">{{ d.patentNumber }}</dd>
                <dt class="text-muted-foreground">Issued</dt><dd>{{ date(d.issueDate) }}</dd>
              }
            </dl>
          </div>
        </section>

        <div class="mt-8 grid gap-8 lg:grid-cols-[minmax(0,1fr)_22rem]">
          <div class="space-y-8">
            <section aria-labelledby="pub-biblio">
              <h2 id="pub-biblio" class="mb-3 border-b pb-2 text-lg font-semibold">Bibliographic data</h2>
              <dl class="grid gap-x-8 gap-y-4 text-sm sm:grid-cols-2">
                <div><dt class="text-muted-foreground text-xs uppercase">Application number</dt><dd class="mt-0.5 font-mono">{{ d.applicationNumberDisplay }}</dd></div>
                <div><dt class="text-muted-foreground text-xs uppercase">Application type</dt><dd class="mt-0.5">{{ typeLabel(d.applicationType) }}</dd></div>
                <div><dt class="text-muted-foreground text-xs uppercase">Technology field</dt><dd class="mt-0.5">{{ d.technologyField ?? '—' }}</dd></div>
                <div><dt class="text-muted-foreground text-xs uppercase">Examiner · art unit</dt><dd class="mt-0.5">{{ d.examinerName ?? '—' }}{{ d.artUnit ? ' · ' + d.artUnit : '' }}</dd></div>
                <div class="sm:col-span-2">
                  <dt class="text-muted-foreground text-xs uppercase">Inventors</dt>
                  <dd class="mt-1">
                    <ol class="space-y-0.5">
                      @for (inv of d.inventors; track $index) {
                        <li><span class="font-medium">{{ inv.name }}</span> <span class="text-muted-foreground">— {{ place(inv.city, inv.state, inv.country) }}</span></li>
                      }
                    </ol>
                  </dd>
                </div>
                <div class="sm:col-span-2">
                  <dt class="text-muted-foreground text-xs uppercase">Abstract</dt>
                  <dd class="mt-1 leading-relaxed whitespace-pre-line">{{ d.abstractText ?? '—' }}</dd>
                </div>
              </dl>
            </section>

            <section aria-labelledby="pub-docs">
              <h2 id="pub-docs" class="mb-3 border-b pb-2 text-lg font-semibold">Public documents</h2>
              @if (!d.documents.length) {
                <p class="text-muted-foreground text-sm">No public copies are available yet.</p>
              } @else {
                <div class="relative overflow-x-auto rounded-xl border">
                  <table z-table class="min-w-[36rem]">
                    <caption class="sr-only">Sanitized public copies</caption>
                    <thead z-table-header>
                      <tr z-table-row>
                        <th z-table-head scope="col">Document</th>
                        <th z-table-head scope="col">Received</th>
                        <th z-table-head scope="col" class="text-right">Pages</th>
                        <th z-table-head scope="col" class="text-right">Redactions</th>
                        <th z-table-head scope="col" class="text-right"><span class="sr-only">Download</span></th>
                      </tr>
                    </thead>
                    <tbody z-table-body>
                      @for (doc of d.documents; track doc.id) {
                        <tr z-table-row>
                          <td z-table-cell><span class="text-primary mr-2 font-mono text-xs font-semibold">{{ doc.docCode }}</span>{{ doc.docCodeLabel }}</td>
                          <td z-table-cell class="text-sm">{{ date(doc.uploadedAt) }}</td>
                          <td z-table-cell class="tabular text-right text-sm">{{ doc.pageCount ?? '—' }}</td>
                          <td z-table-cell class="tabular text-right text-sm">{{ doc.redactionCount ?? 0 }}</td>
                          <td z-table-cell class="text-right">
                            <button
                              type="button"
                              z-button
                              zType="outline"
                              zSize="sm"
                              [zDisabled]="!doc.publicCopyAvailable"
                              [zLoading]="downloading() === doc.id"
                              [attr.aria-label]="'Download public copy of ' + doc.docCodeLabel"
                              (click)="download(doc)"
                            >
                              <ng-icon name="lucideShieldCheck" aria-hidden="true" /> Public copy
                            </button>
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
                <p class="text-muted-foreground mt-2 text-xs">Personal and controlled information is permanently redacted from public copies.</p>
              }
            </section>
          </div>

          <aside aria-labelledby="pub-history">
            <h2 id="pub-history" class="mb-3 border-b pb-2 text-lg font-semibold">Prosecution history</h2>
            <ol class="relative ml-2 border-l pl-5">
              @for (e of historyNewestFirst(); track $index) {
                <li class="relative pb-5 last:pb-0">
                  <span class="ring-background absolute top-1.5 -left-[27px] size-3 rounded-full ring-4" [class]="marker(e.toStatus)" aria-hidden="true"></span>
                  <p class="text-sm font-medium">{{ e.description }}</p>
                  <time class="text-muted-foreground text-xs" [attr.datetime]="e.createdAt">{{ dateTime(e.createdAt) }}</time>
                </li>
              } @empty {
                <li class="text-muted-foreground text-sm">No public history.</li>
              }
            </ol>
          </aside>
        </div>
      }
    </div>
  `,
})
export class PublicStatusPage {
  private readonly api = inject(PublicApi);
  private readonly titles = inject(OpenFilingTitleStrategy);

  readonly applicationNumber = input.required<string>();

  protected readonly normalized = computed(() => parseApplicationNumber(this.applicationNumber()) ?? this.applicationNumber());
  protected readonly display = computed(() => formatApplicationNumber(this.normalized()));
  protected readonly detail = rxResource({ params: () => this.normalized(), stream: ({ params }) => this.api.application(params) });
  protected readonly notFound = computed(() => {
    const e = this.detail.error();
    return !!e && [400, 404].includes(toProblem(e).status ?? 0);
  });
  protected readonly meta = computed(() => (this.detail.hasValue() ? STATUS_META[this.detail.value().status] : null));
  protected readonly toneClasses = computed(() => (this.detail.hasValue() ? TONE_CLASSES[toneOfCategory(this.detail.value().statusCategory)] : ''));
  protected readonly historyNewestFirst = computed(() => (this.detail.hasValue() ? [...this.detail.value().history].reverse() : []));
  protected readonly downloading = signal<string | null>(null);

  constructor() {
    effect(() => {
      if (this.detail.hasValue()) this.titles.setPageTitle(`${this.detail.value().applicationNumberDisplay} status`);
    });
  }

  protected download(doc: PublicDocument): void {
    this.downloading.set(doc.id);
    this.api.downloadDocument(doc.id).subscribe({
      next: (res) => {
        this.downloading.set(null);
        startDownload(res);
      },
      error: () => this.downloading.set(null),
    });
  }

  protected marker(status: string | null): string {
    return status ? TONE_SOLID[toneOfApplicationStatus(status)] : 'bg-muted-foreground/40';
  }

  protected typeLabel(code: string): string {
    return APPLICATION_TYPES.find((t) => t.code === code)?.label ?? code;
  }

  protected place(...parts: (string | null)[]): string {
    return parts.filter((p) => !!p).join(', ') || '—';
  }

  protected date(v: string | null): string {
    return formatDate(v);
  }

  protected dateTime(v: string): string {
    return formatDateTime(v);
  }

  protected print(): void {
    window.print();
  }
}
