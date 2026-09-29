import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideClipboardList,
  lucideFilter,
  lucideHandHelping,
  lucideInbox,
  lucideRefreshCw,
  lucideScanEye,
  lucideSearch,
  lucideTimer,
  lucideUserCheck,
  lucideX,
} from '@ng-icons/lucide';
import { switchMap, throwError } from 'rxjs';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardEmptyComponent } from '@/shared/components/empty';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSelectImports } from '@/shared/components/select';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardTableImports } from '@/shared/components/table';

import { ApplicationsApi } from '../../core/api/applications.api';
import { ExaminerApi } from '../../core/api/examiner.api';
import { formatDate, formatNumber } from '../../core/format';
import { toProblem } from '../../core/http/problem';
import {
  APPLICATION_TYPES,
  type ApplicationSummary,
  type ApplicationType,
  type AssignedFilter,
  FILED_STATUSES,
  STATUS_META,
} from '../../core/models';
import { Notifier } from '../../core/notify/notifier.service';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { PagerComponent } from '../../ui/pager.component';
import { ariaSort, formatSort, parseSort, SortHeaderComponent, type SortState } from '../../ui/sort-header.component';
import { StatCardComponent } from '../../ui/stat-card.component';
import { StatusBadgeComponent } from '../../ui/status-badge.component';

const DEFAULT_SORT: SortState = { field: 'filingDate', dir: 'asc' };
const SORT_FIELDS = ['filingDate', 'updatedAt', 'applicationNumber', 'status'];
const ASSIGNED: AssignedFilter[] = ['any', 'me', 'unassigned'];
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Examiner work queue: every filed application; URL holds the filters (`?q&status&assigned&type&artUnit&filedFrom&filedTo&sort&page`). */
@Component({
  selector: 'app-examiner-queue',
  imports: [
    RouterLink,
    NgIcon,
    ZardButtonComponent,
    ZardInputComponent,
    ZardSkeletonComponent,
    ZardEmptyComponent,
    ...ZardSelectImports,
    ...ZardTableImports,
    PageHeaderComponent,
    PagerComponent,
    StatusBadgeComponent,
    StatCardComponent,
    SortHeaderComponent,
    ErrorStateComponent,
  ],
  viewProviders: [
    provideIcons({ lucideInbox, lucideClipboardList, lucideUserCheck, lucideTimer, lucideScanEye, lucideSearch, lucideX, lucideRefreshCw, lucideHandHelping, lucideFilter }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header eyebrow="Examination" heading="Examiner queue" description="Every filed application, oldest first. Claim a case to add it to your docket, then move it through the prosecution workflow." />

      <section aria-labelledby="kpi-h">
        <h2 id="kpi-h" class="sr-only">Queue statistics</h2>
        @let s = stats.hasValue() ? stats.value() : null;
        <div class="grid grid-cols-2 gap-3 lg:grid-cols-5">
          <app-stat-card label="Unassigned" icon="lucideInbox" [value]="s ? num(s.unassigned) : null" [loading]="!s && stats.isLoading()" link="/examiner" [query]="{ assigned: 'unassigned' }" />
          <app-stat-card label="My docket" icon="lucideClipboardList" [value]="s ? num(s.myDocket) : null" [loading]="!s && stats.isLoading()" link="/examiner" [query]="{ assigned: 'me' }" />
          <app-stat-card label="Awaiting my action" icon="lucideUserCheck" [value]="s ? num(s.awaitingMyAction) : null" [loading]="!s && stats.isLoading()" accent="bg-amber-500/15 text-amber-700 dark:text-amber-300" />
          <app-stat-card label="Avg. pendency" icon="lucideTimer" [value]="s ? (s.avgPendencyDays === null ? '—' : round(s.avgPendencyDays) + ' d') : null" [loading]="!s && stats.isLoading()" hint="Filing to today, pending cases" />
          <app-stat-card
            label="Redactions"
            icon="lucideScanEye"
            [value]="s ? num(s.redactions.pending + s.redactions.processing) : null"
            [loading]="!s && stats.isLoading()"
            [hint]="s ? 'in progress · ' + num(s.redactions.completed) + ' done · ' + num(s.redactions.failed) + ' failed' : ''"
            accent="bg-violet-500/10 text-violet-700 dark:text-violet-300"
          />
        </div>
      </section>

      <form role="search" class="bg-card space-y-3 rounded-xl border p-4" (submit)="$event.preventDefault(); applyText()">
        <div class="grid gap-3 md:grid-cols-[1fr_12rem_12rem]">
          <div class="relative">
            <label for="q-search" class="sr-only">Search title, application number or owner</label>
            <ng-icon name="lucideSearch" class="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" aria-hidden="true" />
            <input id="q-search" z-input type="search" class="pl-9" placeholder="Title, application number or owner" [value]="qDraft()" (input)="onQuery($any($event.target).value)" />
          </div>
          <z-select zAriaLabel="Assignment" [zValue]="assignedValue()" (zSelectionChange)="set('assigned', $event)">
            <z-select-item zValue="any">Any assignment</z-select-item>
            <z-select-item zValue="me">Assigned to me</z-select-item>
            <z-select-item zValue="unassigned">Unassigned</z-select-item>
          </z-select>
          <z-select zAriaLabel="Status" zPlaceholder="All statuses" [zValue]="status() ?? ''" (zSelectionChange)="set('status', $event)">
            <z-select-item zValue="">All statuses</z-select-item>
            @for (st of statuses; track st) {
              <z-select-item [zValue]="st">{{ meta[st].short }}</z-select-item>
            }
          </z-select>
        </div>
        <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-[10rem_7rem_15rem_12rem_auto]">
          <z-select zAriaLabel="Application type" zPlaceholder="All types" [zValue]="type() ?? ''" (zSelectionChange)="set('type', $event)">
            <z-select-item zValue="">All types</z-select-item>
            @for (t of types; track t.code) {
              <z-select-item [zValue]="t.code">{{ t.label }}</z-select-item>
            }
          </z-select>
          <div>
            <label for="q-au" class="sr-only">Art unit</label>
            <input id="q-au" z-input class="font-mono" placeholder="Art unit" maxlength="4" inputmode="numeric" [value]="artDraft()" (input)="artDraft.set($any($event.target).value)" (change)="applyText()" />
          </div>
          <div class="flex items-center gap-2">
            <label for="q-from" class="text-muted-foreground text-xs whitespace-nowrap">Filed from</label>
            <input id="q-from" z-input type="date" [value]="filedFrom() ?? ''" (change)="set('filedFrom', $any($event.target).value)" />
          </div>
          <div class="flex items-center gap-2">
            <label for="q-to" class="text-muted-foreground text-xs">to</label>
            <input id="q-to" z-input type="date" [value]="filedTo() ?? ''" (change)="set('filedTo', $any($event.target).value)" />
          </div>
          <div class="flex justify-end gap-2">
            @if (hasFilters()) {
              <button type="button" z-button zType="ghost" (click)="clear()"><ng-icon name="lucideX" aria-hidden="true" /> Clear</button>
            }
            <button type="button" z-button zType="outline" zSize="icon" aria-label="Refresh queue" (click)="refresh()">
              <ng-icon name="lucideRefreshCw" [class.animate-spin]="queue.isLoading()" aria-hidden="true" />
            </button>
          </div>
        </div>
      </form>

      @if (queue.isLoading() && !queue.hasValue()) {
        <div class="space-y-2">@for (i of [1, 2, 3, 4, 5, 6]; track i) {<z-skeleton class="h-12 w-full" />}</div>
      } @else if (queue.error()) {
        <app-error-state [error]="queue.error()" (retry)="queue.reload()" />
      } @else if (queue.hasValue()) {
        @let page = queue.value();
        <p class="sr-only" aria-live="polite">{{ page.totalElements }} applications in the queue</p>
        @if (!page.content.length) {
          <z-empty class="rounded-xl border border-dashed py-14" zIcon="lucideInbox" zTitle="The queue is clear" zDescription="No filed applications match these filters." />
        } @else {
          <div class="bg-card relative overflow-x-auto rounded-xl border">
            <table z-table class="min-w-[68rem]">
              <caption class="sr-only">Examiner queue</caption>
              <thead z-table-header>
                <tr z-table-row>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('applicationNumber')">
                    <app-sort-header field="applicationNumber" [sort]="sortState()" (sortChange)="setSort($event)">Application</app-sort-header>
                  </th>
                  <th z-table-head scope="col">Title / owner</th>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('status')">
                    <app-sort-header field="status" [sort]="sortState()" (sortChange)="setSort($event)">Status</app-sort-header>
                  </th>
                  <th z-table-head scope="col">Examiner · AU</th>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('filingDate')">
                    <app-sort-header field="filingDate" [sort]="sortState()" (sortChange)="setSort($event)">Filed</app-sort-header>
                  </th>
                  <th z-table-head scope="col" class="text-right">Pending</th>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('updatedAt')">
                    <app-sort-header field="updatedAt" [sort]="sortState()" firstDir="desc" (sortChange)="setSort($event)">Updated</app-sort-header>
                  </th>
                  <th z-table-head scope="col" class="text-right"><span class="sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody z-table-body>
                @for (a of page.content; track a.id) {
                  <tr z-table-row class="hover:bg-muted/40 cursor-pointer" (click)="open(a, $event)">
                    <td z-table-cell class="whitespace-nowrap">
                      <a [routerLink]="['/applications', a.id]" class="text-primary font-mono text-sm font-semibold hover:underline">{{ a.applicationNumberDisplay }}</a>
                      <span class="text-muted-foreground block text-xs">{{ typeLabel(a.applicationType) }}</span>
                    </td>
                    <td z-table-cell class="max-w-sm">
                      <span class="block truncate text-sm font-medium">{{ a.title }}</span>
                      <span class="text-muted-foreground block truncate text-xs">{{ a.owner.displayName }} · {{ a.documentCount }} docs</span>
                    </td>
                    <td z-table-cell class="max-w-48">
                      <app-status-badge [status]="a.status" [category]="a.statusCategory" [title]="a.statusLabel" />
                      @if (a.actionRequired) {
                        <span class="mt-1 block text-[11px] font-medium text-amber-700 dark:text-amber-400">Your action</span>
                      }
                    </td>
                    <td z-table-cell class="text-sm">
                      @if (a.examiner) {
                        {{ a.examiner.displayName }}
                      } @else {
                        <span class="text-muted-foreground italic">Unassigned</span>
                      }
                      <span class="text-muted-foreground block font-mono text-xs">{{ a.artUnit ?? '—' }}</span>
                    </td>
                    <td z-table-cell class="text-sm whitespace-nowrap">{{ date(a.filingDate) }}</td>
                    <td z-table-cell class="tabular text-right text-sm" [class.text-amber-700]="(a.daysPending ?? 0) > 180">{{ a.daysPending ?? '—' }}{{ a.daysPending !== null ? ' d' : '' }}</td>
                    <td z-table-cell class="text-muted-foreground text-xs whitespace-nowrap">{{ date(a.updatedAt) }}</td>
                    <td z-table-cell class="text-right">
                      @if (!a.examiner && !meta[a.status].terminal) {
                        <button type="button" z-button zType="outline" zSize="sm" [zLoading]="claiming() === a.id" [zDisabled]="!!claiming()" (click)="claim(a, $event)">
                          <ng-icon name="lucideHandHelping" aria-hidden="true" /> Claim
                        </button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <app-pager [page]="page.page" [totalPages]="page.totalPages" [size]="page.size" [totalElements]="page.totalElements" ariaLabel="Queue pages" (pageChange)="set('page', '' + $event)" />
        }
      }
    </div>
  `,
})
export class ExaminerQueuePage {
  private readonly examiner = inject(ExaminerApi);
  private readonly applications = inject(ApplicationsApi);
  private readonly router = inject(Router);
  private readonly notifier = inject(Notifier);

  readonly q = input<string>();
  readonly status = input<string>();
  readonly assigned = input<string>();
  readonly type = input<string>();
  readonly artUnit = input<string>();
  readonly filedFrom = input<string>();
  readonly filedTo = input<string>();
  readonly sort = input<string>();
  readonly page = input<string>();

  protected readonly statuses = FILED_STATUSES;
  protected readonly meta = STATUS_META;
  protected readonly types = APPLICATION_TYPES;
  protected readonly qDraft = linkedSignal(() => this.q() ?? '');
  protected readonly artDraft = linkedSignal(() => this.artUnit() ?? '');
  protected readonly claiming = signal<string | null>(null);

  protected readonly assignedValue = computed<AssignedFilter>(() => (ASSIGNED.includes(this.assigned() as AssignedFilter) ? (this.assigned() as AssignedFilter) : 'any'));
  protected readonly sortState = computed(() => {
    const s = parseSort(this.sort(), DEFAULT_SORT);
    return SORT_FIELDS.includes(s.field) ? s : DEFAULT_SORT;
  });
  protected readonly hasFilters = computed(
    () => !!(this.q() || this.status() || this.type() || this.artUnit() || this.filedFrom() || this.filedTo() || this.assignedValue() !== 'any'),
  );

  protected readonly stats = rxResource({ stream: () => this.examiner.stats() });
  protected readonly queue = rxResource({
    params: () => ({
      q: this.q() ?? '',
      status: (FILED_STATUSES as string[]).includes(this.status() ?? '') ? this.status() : undefined,
      assigned: this.assignedValue(),
      type: (APPLICATION_TYPES.some((t) => t.code === this.type()) ? this.type() : undefined) as ApplicationType | undefined,
      artUnit: this.artUnit() ?? '',
      filedFrom: ISO_DATE.test(this.filedFrom() ?? '') ? this.filedFrom() : undefined,
      filedTo: ISO_DATE.test(this.filedTo() ?? '') ? this.filedTo() : undefined,
      sort: formatSort(this.sortState()),
      page: Math.max(0, Number.parseInt(this.page() ?? '0', 10) || 0),
      size: 25,
    }),
    stream: ({ params }) => this.examiner.queue(params),
  });

  private debounce: ReturnType<typeof setTimeout> | null = null;

  protected onQuery(v: string): void {
    this.qDraft.set(v);
    if (this.debounce) clearTimeout(this.debounce);
    this.debounce = setTimeout(() => this.applyText(), 350);
  }

  protected applyText(): void {
    if (this.debounce) clearTimeout(this.debounce);
    this.navigate({ q: this.qDraft().trim() || null, artUnit: this.artDraft().trim() || null, page: null }, true);
  }

  protected set(key: 'status' | 'assigned' | 'type' | 'filedFrom' | 'filedTo' | 'page', value: string | string[]): void {
    const v = Array.isArray(value) ? (value[0] ?? '') : value;
    const cleared = !v || (key === 'assigned' && v === 'any') || (key === 'page' && v === '0');
    this.navigate({ [key]: cleared ? null : v, ...(key !== 'page' ? { page: null } : {}) });
  }

  protected setSort(s: SortState): void {
    this.navigate({ sort: formatSort(s), page: null });
  }

  protected clear(): void {
    this.qDraft.set('');
    this.artDraft.set('');
    this.navigate({ q: null, status: null, assigned: null, type: null, artUnit: null, filedFrom: null, filedTo: null, page: null });
  }

  protected refresh(): void {
    this.queue.reload();
    this.stats.reload();
  }

  protected open(a: ApplicationSummary, event: Event): void {
    const target = event.target as HTMLElement;
    if (target.closest('a,button')) return;
    void this.router.navigate(['/applications', a.id]);
  }

  /** Quick claim: the action needs the current `version`, so fetch the detail first. */
  protected claim(a: ApplicationSummary, event: Event): void {
    event.stopPropagation();
    this.claiming.set(a.id);
    this.applications
      .get(a.id)
      .pipe(
        switchMap((detail) =>
          detail.availableActions.some((x) => x.action === 'CLAIM')
            ? this.applications.action(a.id, { action: 'CLAIM', version: detail.version })
            : throwError(() => new Error('This application can no longer be claimed — it may have been assigned already.')),
        ),
      )
      .subscribe({
        next: () => {
          this.claiming.set(null);
          this.notifier.success('Application claimed', { description: `${a.applicationNumberDisplay} is now on your docket.` });
          this.refresh();
        },
        error: (err: unknown) => {
          this.claiming.set(null);
          const p = toProblem(err);
          this.notifier.error('Could not claim', { description: p.detail ?? p.title });
          this.refresh();
        },
      });
  }

  protected aria(field: string) {
    return ariaSort(this.sortState(), field);
  }

  protected typeLabel(code: string): string {
    return APPLICATION_TYPES.find((t) => t.code === code)?.label ?? code;
  }

  protected date(v: string | null): string {
    return formatDate(v);
  }

  protected num(n: number): string {
    return formatNumber(n);
  }

  protected round(n: number): number {
    return Math.round(n);
  }

  private navigate(params: Record<string, string | null>, replaceUrl = false): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge', replaceUrl });
  }
}
