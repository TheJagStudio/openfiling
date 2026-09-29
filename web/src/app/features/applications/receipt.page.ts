import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowLeft, lucidePrinter } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';

import { ApplicationsApi } from '../../core/api/applications.api';
import { formatBytes, formatDate, formatDateTime } from '../../core/format';
import { toProblem } from '../../core/http/problem';
import { APPLICATION_TYPES, ENTITY_STATUSES } from '../../core/models';
import { BrandMarkComponent } from '../../ui/brand-mark.component';
import { ErrorStateComponent } from '../../ui/error-state.component';

/** Printable "Electronic Acknowledgement Receipt" for a filed application. */
@Component({
  selector: 'app-receipt',
  imports: [RouterLink, NgIcon, ZardButtonComponent, ZardSkeletonComponent, ErrorStateComponent, BrandMarkComponent],
  viewProviders: [provideIcons({ lucidePrinter, lucideArrowLeft })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-4xl px-4 py-8 sm:px-6 print:max-w-none print:p-0">
      <div class="mb-6 flex flex-wrap items-center justify-between gap-2 print:hidden">
        <a z-button zType="ghost" [routerLink]="['/applications', id()]"><ng-icon name="lucideArrowLeft" aria-hidden="true" /> Back to application</a>
        <button type="button" z-button [zDisabled]="!receipt.hasValue()" (click)="print()"><ng-icon name="lucidePrinter" aria-hidden="true" /> Print receipt</button>
      </div>

      @if (receipt.isLoading() && !receipt.hasValue()) {
        <z-skeleton class="h-[36rem] w-full" />
      } @else if (notFiled()) {
        <div class="rounded-xl border border-dashed p-10 text-center">
          <h1 class="text-xl font-semibold">No filing receipt yet</h1>
          <p class="text-muted-foreground mt-2">A receipt is issued once the application has been submitted.</p>
        </div>
      } @else if (receipt.error()) {
        <app-error-state [error]="receipt.error()" (retry)="receipt.reload()" />
      } @else if (receipt.hasValue()) {
        @let r = receipt.value();
        <article class="bg-card rounded-xl border p-8 shadow-xs print:rounded-none print:border-0 print:p-0 print:shadow-none" aria-labelledby="receipt-h">
          <header class="flex items-start justify-between gap-6 border-b-2 border-foreground pb-5">
            <div class="flex items-center gap-3">
              <app-brand-mark [size]="40" />
              <div>
                <p class="text-lg font-bold tracking-tight">OpenFiling</p>
                <p class="text-muted-foreground text-xs">Patent Center · Electronic Filing System</p>
              </div>
            </div>
            <div class="text-right text-xs">
              <p class="text-muted-foreground">Receipt generated</p>
              <p class="font-medium">{{ now }}</p>
            </div>
          </header>

          <h1 id="receipt-h" class="mt-6 text-center text-xl font-bold tracking-wide uppercase">Electronic Acknowledgement Receipt</h1>

          <table class="mt-6 w-full border-collapse text-sm">
            <caption class="sr-only">Filing details</caption>
            <tbody>
              @for (row of rows(); track row.label) {
                <tr class="border-b last:border-b-0">
                  <th scope="row" class="text-muted-foreground w-1/3 py-2 pr-4 text-left align-top font-medium">{{ row.label }}</th>
                  <td class="py-2" [class.font-mono]="row.mono" [class.font-semibold]="row.strong">{{ row.value }}</td>
                </tr>
              }
            </tbody>
          </table>

          <h2 class="mt-8 border-b pb-1 text-sm font-bold tracking-wide uppercase">Inventors</h2>
          <table class="mt-2 w-full text-sm">
            <thead>
              <tr class="text-muted-foreground text-left text-xs">
                <th scope="col" class="py-1.5 pr-2 font-medium">#</th>
                <th scope="col" class="py-1.5 pr-2 font-medium">Name</th>
                <th scope="col" class="py-1.5 pr-2 font-medium">Residence</th>
                <th scope="col" class="py-1.5 font-medium">Citizenship</th>
              </tr>
            </thead>
            <tbody>
              @for (inv of r.inventors; track $index; let i = $index) {
                <tr class="border-t">
                  <td class="py-1.5 pr-2">{{ i + 1 }}</td>
                  <td class="py-1.5 pr-2 font-medium">{{ inv.givenName }} {{ inv.familyName }}</td>
                  <td class="py-1.5 pr-2">{{ place(inv.city, inv.state, inv.country) }}</td>
                  <td class="py-1.5 font-mono">{{ inv.citizenship }}</td>
                </tr>
              }
            </tbody>
          </table>

          <h2 class="mt-8 border-b pb-1 text-sm font-bold tracking-wide uppercase">Documents submitted</h2>
          <table class="mt-2 w-full text-sm">
            <thead>
              <tr class="text-muted-foreground text-left text-xs">
                <th scope="col" class="py-1.5 pr-2 font-medium">Code</th>
                <th scope="col" class="py-1.5 pr-2 font-medium">Description</th>
                <th scope="col" class="py-1.5 pr-2 font-medium">File name</th>
                <th scope="col" class="py-1.5 pr-2 text-right font-medium">Size</th>
                <th scope="col" class="py-1.5 text-right font-medium">Received</th>
              </tr>
            </thead>
            <tbody>
              @for (d of r.documents; track $index) {
                <tr class="border-t print:break-inside-avoid">
                  <td class="py-1.5 pr-2 font-mono font-semibold">{{ d.docCode }}</td>
                  <td class="py-1.5 pr-2">{{ d.docCodeLabel }}</td>
                  <td class="max-w-56 truncate py-1.5 pr-2">{{ d.fileName }}</td>
                  <td class="tabular py-1.5 pr-2 text-right">{{ bytes(d.sizeBytes) }}</td>
                  <td class="py-1.5 text-right whitespace-nowrap">{{ dateTime(d.uploadedAt) }}</td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="text-muted-foreground py-3">No documents.</td></tr>
              }
            </tbody>
          </table>

          <footer class="text-muted-foreground mt-10 space-y-2 border-t pt-4 text-xs leading-relaxed">
            <p>
              This Acknowledgement Receipt evidences receipt of the documents listed above on the date shown. The application number and filing date
              are assigned upon receipt of the required parts. Retain this receipt for your records.
            </p>
            <p>OpenFiling is a demonstration system and is not affiliated with the United States Patent and Trademark Office.</p>
          </footer>
        </article>
      }
    </div>
  `,
})
export class ReceiptPage {
  private readonly api = inject(ApplicationsApi);

  readonly id = input.required<string>();

  protected readonly receipt = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.receipt(params) });
  protected readonly notFiled = computed(() => {
    const e = this.receipt.error();
    return !!e && toProblem(e).status === 409;
  });
  protected readonly now = formatDateTime(new Date().toISOString());

  protected readonly rows = computed(() => {
    if (!this.receipt.hasValue()) return [];
    const r = this.receipt.value();
    return [
      { label: 'Application number', value: r.applicationNumberDisplay, mono: true, strong: true },
      { label: 'Confirmation number', value: r.confirmationNumber, mono: true, strong: true },
      { label: 'Filing date', value: formatDate(r.filingDate), mono: false, strong: true },
      { label: 'Title of invention', value: r.title, mono: false, strong: false },
      { label: 'Application type', value: APPLICATION_TYPES.find((t) => t.code === r.applicationType)?.label ?? r.applicationType, mono: false, strong: false },
      { label: 'Entity status', value: ENTITY_STATUSES.find((t) => t.code === r.entityStatus)?.label ?? r.entityStatus, mono: false, strong: false },
      { label: 'First named inventor', value: r.inventors[0] ? `${r.inventors[0].givenName} ${r.inventors[0].familyName}` : '—', mono: false, strong: false },
      { label: 'Applicant / filer', value: `${r.applicant.displayName} <${r.applicant.email}>`, mono: false, strong: false },
      { label: 'Submitted', value: formatDateTime(r.submittedAt), mono: false, strong: false },
      { label: 'Electronic signature', value: r.signature, mono: true, strong: false },
    ];
  });

  protected print(): void {
    window.print();
  }

  protected place(...parts: (string | null)[]): string {
    return parts.filter((p) => !!p).join(', ') || '—';
  }

  protected bytes(n: number): string {
    return formatBytes(n);
  }

  protected dateTime(v: string | null): string {
    return formatDateTime(v);
  }
}
