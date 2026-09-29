import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowRight,
  lucideBadgeCheck,
  lucideCircleAlert,
  lucideFilePen,
  lucideFilePlus2,
  lucideFiles,
  lucideHourglass,
  lucideInbox,
  lucideTimer,
  lucideUserCheck,
  lucideCircleSlash,
  lucideClipboardList,
} from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';

import { ApplicationsApi } from '../../core/api/applications.api';
import { AuthStore } from '../../core/auth/auth.store';
import { formatNumber } from '../../core/format';
import { APPLICATION_STATUSES, STATUS_META, type StatusCount } from '../../core/models';
import { ApplicationListComponent } from '../../ui/application-list.component';
import { BarChartComponent, type BarDatum } from '../../ui/bar-chart.component';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { StatCardComponent } from '../../ui/stat-card.component';

/** Status counts → chart bars in workflow order (only statuses that occur, so labels stay legible). */
export function statusBars(byStatus: readonly StatusCount[], includeDraft = true): BarDatum[] {
  const counts = new Map(byStatus.map((s) => [s.status, s.count]));
  return APPLICATION_STATUSES.filter((s) => (includeDraft || s !== 'DRAFT') && (counts.get(s) ?? 0) > 0).map((s) => ({
    label: STATUS_META[s].short,
    title: STATUS_META[s].label,
    value: counts.get(s) ?? 0,
  }));
}

@Component({
  selector: 'app-dashboard',
  imports: [
    RouterLink,
    NgIcon,
    ZardButtonComponent,
    ZardSkeletonComponent,
    ...ZardCardImports,
    PageHeaderComponent,
    StatCardComponent,
    ApplicationListComponent,
    BarChartComponent,
    ErrorStateComponent,
  ],
  viewProviders: [
    provideIcons({
      lucideFilePlus2,
      lucideFiles,
      lucideFilePen,
      lucideHourglass,
      lucideCircleAlert,
      lucideBadgeCheck,
      lucideCircleSlash,
      lucideArrowRight,
      lucideInbox,
      lucideUserCheck,
      lucideTimer,
      lucideClipboardList,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-7xl space-y-8 px-4 py-8 sm:px-6">
      <app-page-header [eyebrow]="today" [heading]="greeting()" description="Your filings at a glance — what needs your attention, and where everything stands.">
        <ng-container actions>
          @if (auth.isExaminer()) {
            <a z-button zType="outline" zSize="lg" routerLink="/examiner"><ng-icon name="lucideInbox" aria-hidden="true" /> Examiner queue</a>
          }
          <a z-button zSize="lg" routerLink="/applications/new"><ng-icon name="lucideFilePlus2" aria-hidden="true" /> New application</a>
        </ng-container>
      </app-page-header>

      @if (dash.error()) {
        <app-error-state [error]="dash.error()" (retry)="dash.reload()" />
      } @else {
        @let d = dash.hasValue() ? dash.value() : null;
        <section aria-labelledby="totals-h">
          <h2 id="totals-h" class="sr-only">My application totals</h2>
          <div class="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
            <app-stat-card label="All applications" icon="lucideFiles" [value]="n(d?.totals?.all)" [loading]="!d" link="/applications" />
            <app-stat-card label="Drafts" icon="lucideFilePen" [value]="n(d?.totals?.drafts)" [loading]="!d" link="/applications" [query]="{ status: 'DRAFT' }" accent="bg-muted text-muted-foreground" />
            <app-stat-card label="Pending" icon="lucideHourglass" [value]="n(d?.totals?.pending)" [loading]="!d" link="/applications" [query]="{ status: pendingStatuses }" accent="bg-sky-500/10 text-sky-700 dark:text-sky-300" />
            <app-stat-card label="Action required" icon="lucideCircleAlert" [value]="n(d?.totals?.actionRequired)" [loading]="!d" link="/applications" [query]="{ status: actionStatuses }" accent="bg-amber-500/15 text-amber-700 dark:text-amber-300" />
            <app-stat-card label="Issued" icon="lucideBadgeCheck" [value]="n(d?.totals?.issued)" [loading]="!d" link="/applications" [query]="{ status: 'ISSUED' }" accent="bg-emerald-500/10 text-emerald-700 dark:text-emerald-300" />
            <app-stat-card label="Abandoned" icon="lucideCircleSlash" [value]="n(d?.totals?.abandoned)" [loading]="!d" link="/applications" [query]="{ status: 'ABANDONED' }" accent="bg-red-500/10 text-red-700 dark:text-red-300" />
          </div>
        </section>

        @if (d?.examiner; as ex) {
          <section aria-labelledby="exam-h" class="bg-navy text-navy-foreground relative overflow-hidden rounded-2xl p-6">
            <div class="paper-grid pointer-events-none absolute inset-0 opacity-20" aria-hidden="true"></div>
            <div class="relative flex flex-wrap items-center justify-between gap-3">
              <div>
                <h2 id="exam-h" class="text-lg font-semibold">Examiner workload</h2>
                <p class="text-sm opacity-80">Across all filed applications{{ auth.user()?.artUnit ? ' · your art unit ' + auth.user()?.artUnit : '' }}.</p>
              </div>
              <a z-button class="bg-seal text-seal-foreground hover:bg-seal/90" routerLink="/examiner">Open queue <ng-icon name="lucideArrowRight" aria-hidden="true" /></a>
            </div>
            <dl class="relative mt-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
              @for (k of examinerKpis(); track k.label) {
                <div class="rounded-xl bg-white/8 p-4 ring-1 ring-white/10">
                  <dt class="flex items-center gap-2 text-xs font-medium tracking-wide uppercase opacity-80"><ng-icon [name]="k.icon" class="size-4" aria-hidden="true" /> {{ k.label }}</dt>
                  <dd class="tabular mt-1 text-3xl font-semibold">{{ k.value }}</dd>
                </div>
              }
            </dl>
          </section>
        }

        <div class="grid gap-6 lg:grid-cols-2">
          <z-card>
            <z-card-header>
              <z-card-title class="flex items-center gap-2"><ng-icon name="lucideCircleAlert" class="size-4 text-amber-600" aria-hidden="true" /> Needs your attention</z-card-title>
              <z-card-description>Applications waiting for you to act.</z-card-description>
            </z-card-header>
            <z-card-content>
              @if (!d) {
                <div class="space-y-3">@for (i of [1, 2, 3]; track i) {<z-skeleton class="h-10 w-full" />}</div>
              } @else {
                <app-application-list [items]="d.actionRequired" empty="Nothing needs your attention right now." />
              }
            </z-card-content>
          </z-card>

          <z-card>
            <z-card-header>
              <z-card-title>Status breakdown</z-card-title>
              <z-card-description>My applications by status.</z-card-description>
            </z-card-header>
            <z-card-content>
              @if (!d) {
                <z-skeleton class="h-44 w-full" />
              } @else if (d.totals.all === 0) {
                <p class="text-muted-foreground py-10 text-center text-sm">No applications yet.</p>
              } @else {
                <app-bar-chart [data]="bars()" [height]="200" ariaLabel="My applications by status" unit="applications" [labelEvery]="1" />
              }
            </z-card-content>
          </z-card>
        </div>

        <z-card>
          <z-card-header>
            <div class="flex items-start justify-between gap-3">
              <div class="space-y-1.5">
                <z-card-title>Recently updated</z-card-title>
                <z-card-description>Your latest activity.</z-card-description>
              </div>
              <a z-button zType="ghost" zSize="sm" routerLink="/applications">View all <ng-icon name="lucideArrowRight" aria-hidden="true" /></a>
            </div>
          </z-card-header>
          <z-card-content>
            @if (!d) {
              <div class="space-y-3">@for (i of [1, 2, 3]; track i) {<z-skeleton class="h-10 w-full" />}</div>
            } @else if (!d.recent.length) {
              <div class="py-8 text-center">
                <p class="font-medium">Start your first application</p>
                <p class="text-muted-foreground mt-1 text-sm">Drafts are private and can be edited until you submit.</p>
                <a z-button class="mt-4" routerLink="/applications/new"><ng-icon name="lucideFilePlus2" aria-hidden="true" /> New application</a>
              </div>
            } @else {
              <app-application-list [items]="d.recent" />
            }
          </z-card-content>
        </z-card>
      }
    </div>
  `,
})
export class DashboardPage {
  private readonly api = inject(ApplicationsApi);
  protected readonly auth = inject(AuthStore);

  protected readonly dash = rxResource({ stream: () => this.api.dashboard() });
  protected readonly today = new Intl.DateTimeFormat('en-US', { weekday: 'long', month: 'long', day: 'numeric' }).format(new Date());
  protected readonly pendingStatuses = APPLICATION_STATUSES.filter((s) => STATUS_META[s].category === 'PENDING').join(',');
  protected readonly actionStatuses = APPLICATION_STATUSES.filter((s) => STATUS_META[s].category === 'ACTION_REQUIRED').join(',');

  protected readonly greeting = computed(() => {
    const first = this.auth.user()?.displayName?.split(/\s+/)[0];
    const h = new Date().getHours();
    const part = h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
    return first ? `${part}, ${first}` : part;
  });

  protected readonly bars = computed(() => (this.dash.hasValue() ? statusBars(this.dash.value().byStatus) : []));

  protected readonly examinerKpis = computed(() => {
    const ex = this.dash.hasValue() ? this.dash.value().examiner : null;
    if (!ex) return [];
    return [
      { label: 'Unassigned', value: formatNumber(ex.unassigned), icon: 'lucideInbox' },
      { label: 'My docket', value: formatNumber(ex.myDocket), icon: 'lucideClipboardList' },
      { label: 'Awaiting my action', value: formatNumber(ex.awaitingMyAction), icon: 'lucideUserCheck' },
      { label: 'Avg. pendency', value: ex.avgPendencyDays === null ? '—' : `${Math.round(ex.avgPendencyDays)} d`, icon: 'lucideTimer' },
    ];
  });

  protected n(v: number | null | undefined): string | null {
    return v === null || v === undefined ? null : formatNumber(v);
  }
}
