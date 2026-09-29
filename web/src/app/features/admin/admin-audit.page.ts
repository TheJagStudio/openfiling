import { ChangeDetectionStrategy, Component, computed, inject, signal, type TemplateRef, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBraces, lucideFilter, lucideRefreshCw, lucideScrollText, lucideX } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardEmptyComponent } from '@/shared/components/empty';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSelectImports } from '@/shared/components/select';
import { ZardSheetService } from '@/shared/components/sheet';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardTableImports } from '@/shared/components/table';

import { AdminApi } from '../../core/api/admin.api';
import { formatDateTime, humanize } from '../../core/format';
import { AUDIT_ACTIONS, type AuditEvent, type AuditQuery } from '../../core/models';
import { CopyButtonComponent } from '../../ui/copy-button.component';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { PagerComponent } from '../../ui/pager.component';
import { AdminNavComponent } from './admin-nav.component';

/** Pretty-prints audit `details` (object, JSON string or scalar) for display in a <pre>. */
export function prettyJson(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/** `YYYY-MM-DD` (local) → ISO instant at local start / end of that day. */
export function dayBoundary(date: string, end: boolean): string | undefined {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!m) return undefined;
  const d = end ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 23, 59, 59, 999) : new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return d.toISOString();
}

interface AuditFilters {
  actor: string;
  action: string;
  resourceType: string;
  resourceId: string;
  from: string;
  to: string;
}

const EMPTY: AuditFilters = { actor: '', action: '', resourceType: '', resourceId: '', from: '', to: '' };

@Component({
  selector: 'app-admin-audit',
  imports: [
    NgIcon,
    ZardButtonComponent,
    ZardInputComponent,
    ZardSkeletonComponent,
    ZardEmptyComponent,
    ...ZardSelectImports,
    ...ZardTableImports,
    PageHeaderComponent,
    PagerComponent,
    ErrorStateComponent,
    CopyButtonComponent,
    AdminNavComponent,
  ],
  viewProviders: [provideIcons({ lucideFilter, lucideX, lucideRefreshCw, lucideBraces, lucideScrollText })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header eyebrow="Administration" heading="Audit log" description="Every sensitive action — sign-ins, filings, workflow transitions, downloads and redactions — newest first." />
      <app-admin-nav />

      <form class="bg-card grid gap-3 rounded-xl border p-4 sm:grid-cols-2 lg:grid-cols-6" (submit)="$event.preventDefault(); apply()" aria-label="Audit filters">
        <div class="space-y-1 lg:col-span-1">
          <label for="au-actor" class="text-muted-foreground text-xs font-medium">Actor (email or id)</label>
          <input id="au-actor" z-input [value]="draft().actor" (input)="patch('actor', $any($event.target).value)" />
        </div>
        <div class="space-y-1 lg:col-span-1">
          <span class="text-muted-foreground block text-xs font-medium">Action</span>
          <z-select zAriaLabel="Action" zPlaceholder="Any action" [zValue]="draft().action" (zSelectionChange)="patch('action', $any($event))">
            <z-select-item zValue="">Any action</z-select-item>
            @for (a of actions; track a) {
              <z-select-item [zValue]="a">{{ humanize(a) }}</z-select-item>
            }
          </z-select>
        </div>
        <div class="space-y-1">
          <label for="au-rtype" class="text-muted-foreground text-xs font-medium">Resource type</label>
          <input id="au-rtype" z-input placeholder="APPLICATION, DOCUMENT…" [value]="draft().resourceType" (input)="patch('resourceType', $any($event.target).value)" />
        </div>
        <div class="space-y-1">
          <label for="au-rid" class="text-muted-foreground text-xs font-medium">Resource id</label>
          <input id="au-rid" z-input class="font-mono" [value]="draft().resourceId" (input)="patch('resourceId', $any($event.target).value)" />
        </div>
        <div class="space-y-1">
          <label for="au-from" class="text-muted-foreground text-xs font-medium">From</label>
          <input id="au-from" z-input type="date" [value]="draft().from" (change)="patch('from', $any($event.target).value)" />
        </div>
        <div class="space-y-1">
          <label for="au-to" class="text-muted-foreground text-xs font-medium">To</label>
          <input id="au-to" z-input type="date" [value]="draft().to" (change)="patch('to', $any($event.target).value)" />
        </div>
        <div class="flex justify-end gap-2 sm:col-span-2 lg:col-span-6">
          <button type="button" z-button zType="ghost" (click)="clear()"><ng-icon name="lucideX" aria-hidden="true" /> Clear</button>
          <button type="button" z-button zType="outline" zSize="icon" aria-label="Refresh" (click)="events.reload()">
            <ng-icon name="lucideRefreshCw" [class.animate-spin]="events.isLoading()" aria-hidden="true" />
          </button>
          <button type="submit" z-button><ng-icon name="lucideFilter" aria-hidden="true" /> Apply filters</button>
        </div>
      </form>

      @if (events.isLoading() && !events.hasValue()) {
        <div class="space-y-2">@for (i of [1, 2, 3, 4, 5, 6]; track i) {<z-skeleton class="h-11 w-full" />}</div>
      } @else if (events.error()) {
        <app-error-state [error]="events.error()" (retry)="events.reload()" />
      } @else if (events.hasValue()) {
        @let page = events.value();
        @if (!page.content.length) {
          <z-empty class="rounded-xl border border-dashed py-12" zIcon="lucideScrollText" zTitle="No audit events" zDescription="Nothing matches these filters." />
        } @else {
          <div class="bg-card relative overflow-x-auto rounded-xl border">
            <table z-table class="min-w-[64rem]">
              <caption class="sr-only">Audit events, newest first</caption>
              <thead z-table-header>
                <tr z-table-row>
                  <th z-table-head scope="col">Time</th>
                  <th z-table-head scope="col">Actor</th>
                  <th z-table-head scope="col">Action</th>
                  <th z-table-head scope="col">Resource</th>
                  <th z-table-head scope="col">IP</th>
                  <th z-table-head scope="col">Request id</th>
                  <th z-table-head scope="col" class="text-right"><span class="sr-only">Details</span></th>
                </tr>
              </thead>
              <tbody z-table-body>
                @for (e of page.content; track e.id) {
                  <tr z-table-row>
                    <td z-table-cell class="text-xs whitespace-nowrap">{{ dateTime(e.occurredAt) }}</td>
                    <td z-table-cell class="max-w-48 truncate text-sm">{{ e.actorEmail ?? 'system' }}</td>
                    <td z-table-cell>
                      <span class="rounded px-1.5 py-0.5 font-mono text-[11px] font-semibold" [class]="actionClass(e.action)">{{ e.action }}</span>
                    </td>
                    <td z-table-cell class="max-w-56 text-xs">
                      <span class="font-medium">{{ e.resourceType ?? '—' }}</span>
                      @if (e.resourceId) {
                        <span class="text-muted-foreground block truncate font-mono" [title]="e.resourceId">{{ e.resourceId }}</span>
                      }
                    </td>
                    <td z-table-cell class="font-mono text-xs">{{ e.ip ?? '—' }}</td>
                    <td z-table-cell class="text-muted-foreground max-w-36 truncate font-mono text-[11px]" [title]="e.requestId ?? ''">{{ e.requestId ?? '—' }}</td>
                    <td z-table-cell class="text-right">
                      <button type="button" z-button zType="ghost" zSize="sm" [attr.aria-label]="'Details of event ' + e.id" (click)="open(e)"><ng-icon name="lucideBraces" aria-hidden="true" /> Details</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <app-pager [page]="page.page" [totalPages]="page.totalPages" [size]="page.size" [totalElements]="page.totalElements" ariaLabel="Audit pages" (pageChange)="pageIndex.set($event)" />
        }
      }
    </div>

    <ng-template #detailTpl>
      @if (selected(); as e) {
        <div class="space-y-4 px-4 pb-6">
          <dl class="grid grid-cols-[7rem_1fr] gap-x-3 gap-y-2 text-sm">
            <dt class="text-muted-foreground">Event</dt><dd class="font-mono">#{{ e.id }}</dd>
            <dt class="text-muted-foreground">Time</dt><dd>{{ dateTime(e.occurredAt) }}</dd>
            <dt class="text-muted-foreground">Action</dt><dd class="font-mono">{{ e.action }}</dd>
            <dt class="text-muted-foreground">Actor</dt><dd class="break-all">{{ e.actorEmail ?? 'system' }}<span class="text-muted-foreground block font-mono text-xs">{{ e.actorId ?? '' }}</span></dd>
            <dt class="text-muted-foreground">Resource</dt><dd class="break-all">{{ e.resourceType ?? '—' }} <span class="text-muted-foreground font-mono text-xs">{{ e.resourceId ?? '' }}</span></dd>
            <dt class="text-muted-foreground">IP</dt><dd class="font-mono">{{ e.ip ?? '—' }}</dd>
            <dt class="text-muted-foreground">User agent</dt><dd class="text-xs break-all">{{ e.userAgent ?? '—' }}</dd>
            <dt class="text-muted-foreground">Request id</dt><dd class="font-mono text-xs break-all">{{ e.requestId ?? '—' }}</dd>
          </dl>
          <div>
            <div class="mb-1 flex items-center justify-between">
              <h3 class="text-sm font-semibold">Details</h3>
              <app-copy-button [value]="json()" tooltip="Copy JSON" />
            </div>
            <pre class="bg-muted max-h-[55vh] overflow-auto rounded-lg p-3 font-mono text-xs leading-relaxed" tabindex="0" aria-label="Event details JSON">{{ json() }}</pre>
          </div>
        </div>
      }
    </ng-template>
  `,
})
export class AdminAuditPage {
  private readonly api = inject(AdminApi);
  private readonly sheet = inject(ZardSheetService);
  private readonly detailTpl = viewChild.required<TemplateRef<unknown>>('detailTpl');

  protected readonly actions = AUDIT_ACTIONS;
  protected readonly humanize = humanize;
  protected readonly draft = signal<AuditFilters>({ ...EMPTY });
  private readonly applied = signal<AuditFilters>({ ...EMPTY });
  protected readonly pageIndex = signal(0);
  protected readonly selected = signal<AuditEvent | null>(null);
  protected readonly json = computed(() => prettyJson(this.selected()?.details));

  protected readonly events = rxResource({
    params: (): AuditQuery => {
      const f = this.applied();
      return {
        actor: f.actor,
        action: f.action,
        resourceType: f.resourceType,
        resourceId: f.resourceId,
        from: dayBoundary(f.from, false),
        to: dayBoundary(f.to, true),
        page: this.pageIndex(),
        size: 25,
      };
    },
    stream: ({ params }) => this.api.audit(params),
  });

  protected patch(key: keyof AuditFilters, value: string | string[]): void {
    const v = Array.isArray(value) ? (value[0] ?? '') : value;
    this.draft.update((d) => ({ ...d, [key]: v }));
    if (key === 'action' || key === 'from' || key === 'to') this.apply();
  }

  protected apply(): void {
    this.applied.set({ ...this.draft() });
    this.pageIndex.set(0);
  }

  protected clear(): void {
    this.draft.set({ ...EMPTY });
    this.apply();
  }

  protected open(e: AuditEvent): void {
    this.selected.set(e);
    this.sheet.create({
      zTitle: humanize(e.action),
      zDescription: `Audit event #${e.id}`,
      zContent: this.detailTpl(),
      zSide: 'right',
      zHideFooter: true,
      zSize: 'lg',
    });
  }

  protected actionClass(action: string): string {
    if (/FAILED|LOCKED|REUSE|REJECTED/.test(action)) return 'bg-red-50 text-red-800 dark:bg-red-500/10 dark:text-red-300';
    if (/DELETED|ADMIN_/.test(action)) return 'bg-amber-50 text-amber-900 dark:bg-amber-500/10 dark:text-amber-300';
    if (/AUTH_/.test(action)) return 'bg-muted text-muted-foreground';
    return 'bg-primary/10 text-primary';
  }

  protected dateTime(v: string): string {
    return formatDateTime(v);
  }
}
