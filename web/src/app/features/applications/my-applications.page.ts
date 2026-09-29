import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideFilePlus2, lucideFolderOpen, lucideRefreshCw, lucideSearch, lucideX } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardEmptyComponent } from '@/shared/components/empty';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSelectImports } from '@/shared/components/select';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardTableImports } from '@/shared/components/table';

import { ApplicationsApi } from '../../core/api/applications.api';
import { formatDate, formatRelative } from '../../core/format';
import { APPLICATION_STATUSES, APPLICATION_TYPES, type ApplicationStatus, type ApplicationType, STATUS_META } from '../../core/models';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { PagerComponent } from '../../ui/pager.component';
import { ariaSort, formatSort, parseSort, SortHeaderComponent, type SortState } from '../../ui/sort-header.component';
import { StatusBadgeComponent } from '../../ui/status-badge.component';

const DEFAULT_SORT: SortState = { field: 'updatedAt', dir: 'desc' };
const SORT_FIELDS = ['updatedAt', 'createdAt', 'filingDate', 'title', 'status'];

/** Parses a comma-separated status list from the URL, keeping only known codes. */
export function parseStatusList(raw: string | null | undefined): ApplicationStatus[] {
  if (!raw) return [];
  return raw.split(',').filter((s): s is ApplicationStatus => (APPLICATION_STATUSES as string[]).includes(s));
}

/** The caller's own applications. The URL is the state: `?q=&status=A,B&type=&sort=&page=`. */
@Component({
  selector: 'app-my-applications',
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
    SortHeaderComponent,
    ErrorStateComponent,
  ],
  viewProviders: [provideIcons({ lucideFilePlus2, lucideSearch, lucideX, lucideRefreshCw, lucideFolderOpen, lucideCircleAlert })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header eyebrow="Filing" heading="My applications" description="Drafts and filed applications you own. Select an application to upload documents, respond to office actions or view its history.">
        <a actions z-button zSize="lg" routerLink="/applications/new"><ng-icon name="lucideFilePlus2" aria-hidden="true" /> New application</a>
      </app-page-header>

      <form role="search" class="bg-card grid gap-3 rounded-xl border p-4 md:grid-cols-[1fr_16rem_12rem_auto]" (submit)="$event.preventDefault(); applySearch()">
        <div class="relative">
          <label for="apps-q" class="sr-only">Search by title, application number or docket number</label>
          <ng-icon name="lucideSearch" class="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" aria-hidden="true" />
          <input
            id="apps-q"
            z-input
            type="search"
            class="pl-9"
            placeholder="Search title, application or docket number"
            [value]="qDraft()"
            (input)="onQuery($any($event.target).value)"
          />
        </div>
        <z-select zAriaLabel="Filter by status" zPlaceholder="All statuses" [zMultiple]="true" [zMaxLabelCount]="1" [zValue]="statuses()" (zSelectionChange)="setStatuses($event)">
          @for (s of allStatuses; track s) {
            <z-select-item [zValue]="s">{{ meta[s].short }}</z-select-item>
          }
        </z-select>
        <z-select zAriaLabel="Filter by application type" zPlaceholder="All types" [zValue]="type() ?? ''" (zSelectionChange)="setParam('type', $event)">
          <z-select-item zValue="">All types</z-select-item>
          @for (t of types; track t.code) {
            <z-select-item [zValue]="t.code">{{ t.label }}</z-select-item>
          }
        </z-select>
        <div class="flex gap-2">
          @if (hasFilters()) {
            <button type="button" z-button zType="ghost" (click)="clear()"><ng-icon name="lucideX" aria-hidden="true" /> Clear</button>
          }
          <button type="button" z-button zType="outline" zSize="icon" aria-label="Refresh list" (click)="apps.reload()">
            <ng-icon name="lucideRefreshCw" [class.animate-spin]="apps.isLoading()" aria-hidden="true" />
          </button>
        </div>
      </form>

      @if (apps.isLoading() && !apps.hasValue()) {
        <div class="space-y-2">
          @for (i of [1, 2, 3, 4, 5]; track i) {
            <z-skeleton class="h-14 w-full" />
          }
        </div>
      } @else if (apps.error()) {
        <app-error-state [error]="apps.error()" (retry)="apps.reload()" />
      } @else if (apps.hasValue()) {
        @let page = apps.value();
        <p class="sr-only" aria-live="polite">{{ page.totalElements }} applications found</p>
        @if (!page.content.length) {
          @if (hasFilters()) {
            <z-empty class="rounded-xl border border-dashed py-14" zIcon="lucideSearch" zTitle="No matching applications" zDescription="Try a different search term or clear the filters." />
          } @else {
            <z-empty
              class="rounded-xl border border-dashed py-14"
              zIcon="lucideFolderOpen"
              zTitle="No applications yet"
              zDescription="Start your first patent application — it stays a private draft until you submit it."
              [zActions]="[emptyAction]"
            />
            <ng-template #emptyAction>
              <a z-button routerLink="/applications/new"><ng-icon name="lucideFilePlus2" aria-hidden="true" /> New application</a>
            </ng-template>
          }
        } @else {
          <div class="bg-card relative overflow-x-auto rounded-xl border">
            <table z-table class="min-w-[56rem]">
              <caption class="sr-only">My applications, sorted by {{ sortState().field }} {{ sortState().dir }}ending</caption>
              <thead z-table-header>
                <tr z-table-row>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('title')">
                    <app-sort-header field="title" [sort]="sortState()" (sortChange)="setSort($event)">Application</app-sort-header>
                  </th>
                  <th z-table-head scope="col">Type</th>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('status')">
                    <app-sort-header field="status" [sort]="sortState()" (sortChange)="setSort($event)">Status</app-sort-header>
                  </th>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('filingDate')">
                    <app-sort-header field="filingDate" [sort]="sortState()" firstDir="desc" (sortChange)="setSort($event)">Filed</app-sort-header>
                  </th>
                  <th z-table-head scope="col" class="text-right">Docs</th>
                  <th z-table-head scope="col" [attr.aria-sort]="aria('updatedAt')">
                    <app-sort-header field="updatedAt" [sort]="sortState()" firstDir="desc" (sortChange)="setSort($event)">Last update</app-sort-header>
                  </th>
                </tr>
              </thead>
              <tbody z-table-body>
                @for (a of page.content; track a.id) {
                  <tr z-table-row class="group">
                    <td z-table-cell class="max-w-md">
                      <a [routerLink]="['/applications', a.id]" class="text-foreground group-hover:text-primary block truncate font-medium hover:underline">{{ a.title }}</a>
                      <span class="text-muted-foreground flex items-center gap-2 text-xs">
                        <span class="font-mono">{{ a.applicationNumberDisplay ?? 'Not yet filed' }}</span>
                        @if (a.actionRequired) {
                          <span class="inline-flex items-center gap-1 font-medium text-amber-700 dark:text-amber-400">
                            <ng-icon name="lucideCircleAlert" class="size-3.5" aria-hidden="true" /> Action required
                          </span>
                        }
                      </span>
                    </td>
                    <td z-table-cell class="text-sm">{{ typeLabel(a.applicationType) }}</td>
                    <td z-table-cell class="max-w-56"><app-status-badge [status]="a.status" [category]="a.statusCategory" [title]="a.statusLabel" /></td>
                    <td z-table-cell class="text-muted-foreground text-sm whitespace-nowrap">{{ date(a.filingDate) }}</td>
                    <td z-table-cell class="tabular text-right text-sm">{{ a.documentCount }}</td>
                    <td z-table-cell class="text-muted-foreground text-sm whitespace-nowrap">{{ relative(a.updatedAt) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <app-pager [page]="page.page" [totalPages]="page.totalPages" [size]="page.size" [totalElements]="page.totalElements" ariaLabel="Application pages" (pageChange)="setParam('page', '' + $event)" />
        }
      }
    </div>
  `,
})
export class MyApplicationsPage {
  private readonly api = inject(ApplicationsApi);
  private readonly router = inject(Router);

  // Query params (component input binding).
  readonly q = input<string>();
  readonly status = input<string>();
  readonly type = input<string>();
  readonly sort = input<string>();
  readonly page = input<string>();

  protected readonly allStatuses = APPLICATION_STATUSES;
  protected readonly meta = STATUS_META;
  protected readonly types = APPLICATION_TYPES;

  protected readonly qDraft = linkedSignal(() => this.q() ?? '');
  protected readonly statuses = computed(() => parseStatusList(this.status()));
  protected readonly sortState = computed(() => {
    const s = parseSort(this.sort(), DEFAULT_SORT);
    return SORT_FIELDS.includes(s.field) ? s : DEFAULT_SORT;
  });
  protected readonly hasFilters = computed(() => !!(this.q() || this.statuses().length || this.type()));

  protected readonly apps = rxResource({
    params: () => ({
      q: this.q() ?? '',
      status: this.statuses().join(','),
      type: (APPLICATION_TYPES.some((t) => t.code === this.type()) ? this.type() : undefined) as ApplicationType | undefined,
      sort: formatSort(this.sortState()),
      page: Math.max(0, Number.parseInt(this.page() ?? '0', 10) || 0),
      size: 20,
    }),
    stream: ({ params }) => this.api.list(params),
  });

  private debounce: ReturnType<typeof setTimeout> | null = null;

  protected onQuery(value: string): void {
    this.qDraft.set(value);
    if (this.debounce) clearTimeout(this.debounce);
    this.debounce = setTimeout(() => this.applySearch(), 350);
  }

  protected applySearch(): void {
    if (this.debounce) clearTimeout(this.debounce);
    this.navigate({ q: this.qDraft().trim() || null, page: null }, true);
  }

  protected setStatuses(value: string | string[]): void {
    const list = Array.isArray(value) ? value : value ? [value] : [];
    this.navigate({ status: list.join(',') || null, page: null });
  }

  protected setParam(key: 'type' | 'page', value: string | string[]): void {
    const v = Array.isArray(value) ? (value[0] ?? '') : value;
    this.navigate({ [key]: v && v !== '0' ? v : null, ...(key !== 'page' ? { page: null } : {}) });
  }

  protected setSort(s: SortState): void {
    this.navigate({ sort: formatSort(s), page: null });
  }

  protected clear(): void {
    this.qDraft.set('');
    this.navigate({ q: null, status: null, type: null, page: null });
  }

  protected aria(field: string) {
    return ariaSort(this.sortState(), field);
  }

  protected typeLabel(code: string): string {
    return APPLICATION_TYPES.find((t) => t.code === code)?.label ?? code;
  }

  protected date(v: string | null): string {
    return v ? formatDate(v) : '—';
  }

  protected relative(v: string): string {
    return formatRelative(v);
  }

  private navigate(params: Record<string, string | null>, replaceUrl = false): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge', replaceUrl });
  }
}
