import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleCheck, lucideCircleX, lucideDatabase, lucideHardDrive, lucideRefreshCw, lucideScanEye } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardSwitchComponent } from '@/shared/components/switch';

import { environment } from '../../../environments/environment';
import { AdminApi } from '../../core/api/admin.api';
import { formatBytes, formatDateTime, formatNumber } from '../../core/format';
import { REDACTION_CATEGORY_LABELS, type RedactionCategory } from '../../core/models';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { AdminNavComponent } from './admin-nav.component';

const COMPONENT_INFO: Record<string, { label: string; description: string; icon: string }> = {
  database: { label: 'Database', description: 'PostgreSQL (RDS) — applications, history, audit', icon: 'lucideDatabase' },
  storage: { label: 'Object storage', description: 'S3 buckets — originals and public copies', icon: 'lucideHardDrive' },
  'redaction-lambda': { label: 'Redaction function', description: 'Lambda — Claude + rules PII/CUI redaction', icon: 'lucideScanEye' },
};

@Component({
  selector: 'app-admin-system',
  imports: [NgIcon, ZardButtonComponent, ZardSkeletonComponent, ZardSwitchComponent, ...ZardCardImports, PageHeaderComponent, ErrorStateComponent, AdminNavComponent],
  viewProviders: [provideIcons({ lucideRefreshCw, lucideCircleCheck, lucideCircleX, lucideDatabase, lucideHardDrive, lucideScanEye })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header eyebrow="Administration" heading="System" description="Component health, platform counts and redaction pipeline statistics.">
        <div actions class="flex items-center gap-3">
          <z-switch zId="sys-auto" [zChecked]="auto()" (zCheckedChange)="auto.set($event)">Auto-refresh (15 s)</z-switch>
          <button type="button" z-button zType="outline" zSize="sm" (click)="refresh()">
            <ng-icon name="lucideRefreshCw" [class.animate-spin]="system.isLoading()" aria-hidden="true" /> Refresh
          </button>
        </div>
      </app-page-header>
      <app-admin-nav />

      @if (system.error() && !system.hasValue()) {
        <app-error-state [error]="system.error()" (retry)="system.reload()" />
      } @else if (!system.hasValue()) {
        <div class="grid gap-4 md:grid-cols-3">@for (i of [1, 2, 3]; track i) {<z-skeleton class="h-28 w-full" />}</div>
        <z-skeleton class="h-64 w-full" />
      } @else {
        @let s = system.value();
        <p class="text-muted-foreground text-xs" aria-live="polite">Last checked {{ checkedAt() }}{{ system.error() ? ' · latest refresh failed' : '' }}</p>

        <section aria-labelledby="health-h">
          <h2 id="health-h" class="mb-3 font-semibold">Component health</h2>
          <div class="grid gap-4 md:grid-cols-3">
            @for (c of s.components; track c.name) {
              <div class="bg-card flex gap-4 rounded-xl border p-5" [class]="c.status === 'UP' ? '' : 'border-red-500/40'">
                <span class="grid size-10 shrink-0 place-items-center rounded-lg" [class]="c.status === 'UP' ? 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300' : 'bg-red-500/10 text-red-700 dark:text-red-300'">
                  <ng-icon [name]="info(c.name).icon" class="size-5" aria-hidden="true" />
                </span>
                <div class="min-w-0 flex-1">
                  <div class="flex items-center justify-between gap-2">
                    <p class="font-medium">{{ info(c.name).label }}</p>
                    <span class="inline-flex items-center gap-1 text-xs font-semibold" [class]="c.status === 'UP' ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-700 dark:text-red-400'">
                      <ng-icon [name]="c.status === 'UP' ? 'lucideCircleCheck' : 'lucideCircleX'" class="size-4" aria-hidden="true" /> {{ c.status }}
                    </span>
                  </div>
                  <p class="text-muted-foreground text-xs">{{ info(c.name).description }}</p>
                  @if (c.detail) {
                    <p class="mt-1 truncate font-mono text-[11px]" [title]="c.detail">{{ c.detail }}</p>
                  }
                </div>
              </div>
            }
          </div>
        </section>

        <section aria-labelledby="counts-h">
          <h2 id="counts-h" class="sr-only">Platform counts</h2>
          <dl class="bg-border grid grid-cols-2 gap-px overflow-hidden rounded-xl border md:grid-cols-5">
            <div class="bg-card p-4"><dt class="text-muted-foreground text-xs font-medium uppercase">Users</dt><dd class="tabular mt-1 text-2xl font-semibold">{{ n(s.counts.users) }}</dd></div>
            <div class="bg-card p-4"><dt class="text-muted-foreground text-xs font-medium uppercase">Applications</dt><dd class="tabular mt-1 text-2xl font-semibold">{{ n(s.counts.applications) }}</dd></div>
            <div class="bg-card p-4"><dt class="text-muted-foreground text-xs font-medium uppercase">Filed</dt><dd class="tabular mt-1 text-2xl font-semibold">{{ n(s.counts.filedApplications) }}</dd></div>
            <div class="bg-card p-4"><dt class="text-muted-foreground text-xs font-medium uppercase">Documents</dt><dd class="tabular mt-1 text-2xl font-semibold">{{ n(s.counts.documents) }}</dd></div>
            <div class="bg-card p-4"><dt class="text-muted-foreground text-xs font-medium uppercase">Storage used</dt><dd class="tabular mt-1 text-2xl font-semibold">{{ bytes(s.counts.storageBytes) }}</dd></div>
          </dl>
        </section>

        <div class="grid gap-6 lg:grid-cols-5">
          <z-card class="lg:col-span-2">
            <z-card-header>
              <z-card-title>Redaction pipeline</z-card-title>
              <z-card-description>{{ n(s.redactions.totalRedactions) }} redactions applied in total.</z-card-description>
            </z-card-header>
            <z-card-content>
              <dl class="grid grid-cols-2 gap-3">
                @for (k of pipeline(); track k.label) {
                  <div class="rounded-lg border p-3">
                    <dt class="flex items-center gap-2 text-xs font-medium"><span class="size-2 rounded-full" [class]="k.dot" aria-hidden="true"></span>{{ k.label }}</dt>
                    <dd class="tabular mt-1 text-2xl font-semibold">{{ n(k.value) }}</dd>
                  </div>
                }
              </dl>
              @if (successRate() !== null) {
                <p class="text-muted-foreground mt-4 text-xs">Success rate {{ successRate() }}% of finished runs.</p>
              }
            </z-card-content>
          </z-card>

          <z-card class="lg:col-span-3">
            <z-card-header>
              <z-card-title>Redactions by category</z-card-title>
              <z-card-description>What the rules and Claude found across all public documents.</z-card-description>
            </z-card-header>
            <z-card-content>
              @if (!categories().length) {
                <p class="text-muted-foreground py-8 text-center text-sm">No redactions yet.</p>
              } @else {
                <ul class="space-y-2.5" aria-label="Redactions by category">
                  @for (c of categories(); track c.category) {
                    <li class="grid grid-cols-[9rem_1fr_3.5rem] items-center gap-3 text-sm">
                      <span class="truncate">{{ c.label }}</span>
                      <span class="bg-muted h-2.5 overflow-hidden rounded-full" aria-hidden="true">
                        <span class="bg-primary block h-full rounded-full transition-[width]" [style.width.%]="c.pct"></span>
                      </span>
                      <span class="tabular text-right">{{ n(c.count) }}</span>
                    </li>
                  }
                </ul>
              }
            </z-card-content>
          </z-card>
        </div>
      }
    </div>
  `,
})
export class AdminSystemPage {
  private readonly api = inject(AdminApi);

  protected readonly auto = signal(true);
  protected readonly lastLoaded = signal<string | null>(null);
  protected readonly system = rxResource({ stream: () => this.api.system() });

  protected readonly checkedAt = computed(() => formatDateTime(this.lastLoaded()));
  protected readonly pipeline = computed(() => {
    if (!this.system.hasValue()) return [];
    const r = this.system.value().redactions;
    return [
      { label: 'Pending', value: r.pending, dot: 'bg-muted-foreground/60' },
      { label: 'Processing', value: r.processing, dot: 'bg-violet-500 animate-pulse' },
      { label: 'Completed', value: r.completed, dot: 'bg-emerald-500' },
      { label: 'Failed', value: r.failed, dot: 'bg-red-500' },
    ];
  });
  protected readonly successRate = computed(() => {
    if (!this.system.hasValue()) return null;
    const r = this.system.value().redactions;
    const done = r.completed + r.failed;
    return done ? Math.round((r.completed / done) * 1000) / 10 : null;
  });
  protected readonly categories = computed(() => {
    if (!this.system.hasValue()) return [];
    const list = [...(this.system.value().redactions.byCategory ?? [])].sort((a, b) => b.count - a.count);
    const max = Math.max(1, ...list.map((c) => c.count));
    return list.map((c) => ({
      category: c.category,
      label: REDACTION_CATEGORY_LABELS[c.category as RedactionCategory] ?? c.category,
      count: c.count,
      pct: Math.round((c.count / max) * 100),
    }));
  });

  constructor() {
    effect(() => {
      if (this.system.hasValue() && !this.system.isLoading()) this.lastLoaded.set(new Date().toISOString());
    });
    // Auto refresh every 15 s while enabled (cleaned up on toggle / destroy).
    effect((onCleanup) => {
      if (!this.auto()) return;
      const timer = setInterval(() => this.system.reload(), environment.systemPollMs);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected refresh(): void {
    this.system.reload();
  }

  protected info(name: string) {
    return COMPONENT_INFO[name] ?? { label: name, description: '', icon: 'lucideDatabase' };
  }

  protected n(v: number): string {
    return formatNumber(v);
  }

  protected bytes(v: number): string {
    return formatBytes(v);
  }
}
