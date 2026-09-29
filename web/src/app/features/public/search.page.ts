import { ChangeDetectionStrategy, Component, computed, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowRight, lucideBookOpen, lucideSearch, lucideShieldCheck, lucideX } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardEmptyComponent } from '@/shared/components/empty';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSelectImports } from '@/shared/components/select';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';

import { PublicApi } from '../../core/api/public.api';
import { formatDate, formatNumber } from '../../core/format';
import { APPLICATION_TYPES, type ApplicationType } from '../../core/models';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { PagerComponent } from '../../ui/pager.component';
import { StatusBadgeComponent } from '../../ui/status-badge.component';

/** Public search over published applications and issued patents (`?q&type&page`). */
@Component({
  selector: 'app-search',
  imports: [
    RouterLink,
    NgIcon,
    ZardButtonComponent,
    ZardInputComponent,
    ZardSkeletonComponent,
    ZardEmptyComponent,
    ...ZardSelectImports,
    PageHeaderComponent,
    PagerComponent,
    StatusBadgeComponent,
    ErrorStateComponent,
  ],
  viewProviders: [provideIcons({ lucideSearch, lucideX, lucideArrowRight, lucideShieldCheck, lucideBookOpen })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-5xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header
        eyebrow="Public records"
        heading="Search published applications"
        description="Published applications and issued patents are public record. Only sanitized public copies of their documents are available."
      />

      <form role="search" class="bg-card flex flex-col gap-3 rounded-xl border p-4 sm:flex-row" (submit)="$event.preventDefault(); apply()">
        <div class="relative flex-1">
          <label for="pub-q" class="sr-only">Search published applications</label>
          <ng-icon name="lucideSearch" class="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" aria-hidden="true" />
          <input id="pub-q" z-input type="search" class="h-10 pl-9" placeholder="Title, inventor, application or publication number" [value]="qDraft()" (input)="qDraft.set($any($event.target).value)" />
        </div>
        <z-select class="sm:w-44" zAriaLabel="Application type" zPlaceholder="All types" [zValue]="type() ?? ''" (zSelectionChange)="setType($event)">
          <z-select-item zValue="">All types</z-select-item>
          @for (t of types; track t.code) {
            <z-select-item [zValue]="t.code">{{ t.label }}</z-select-item>
          }
        </z-select>
        <button type="submit" z-button class="h-10 px-5">Search</button>
      </form>

      @if (results.isLoading() && !results.hasValue()) {
        <div class="space-y-3">@for (i of [1, 2, 3, 4]; track i) {<z-skeleton class="h-28 w-full" />}</div>
      } @else if (results.error()) {
        <app-error-state [error]="results.error()" (retry)="results.reload()" />
      } @else if (results.hasValue()) {
        @let page = results.value();
        <p class="text-muted-foreground text-sm" aria-live="polite">
          {{ count(page.totalElements) }} {{ page.totalElements === 1 ? 'result' : 'results' }}{{ q() ? ' for “' + q() + '”' : '' }}
        </p>
        @if (!page.content.length) {
          <z-empty
            class="rounded-xl border border-dashed py-14"
            zIcon="lucideBookOpen"
            zTitle="No published applications found"
            zDescription="Unpublished applications are confidential and never appear in public search."
          />
        } @else {
          <ol class="space-y-3">
            @for (r of page.content; track r.applicationNumber) {
              <li>
                <article class="bg-card group relative rounded-xl border p-5 transition-shadow hover:shadow-md">
                  <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
                    <span class="font-mono font-semibold">{{ r.applicationNumberDisplay }}</span>
                    <app-status-badge [status]="r.status" [category]="r.statusCategory" [title]="r.statusLabel" />
                    <span class="text-muted-foreground text-xs">{{ typeLabel(r.applicationType) }}</span>
                  </div>
                  <h2 class="mt-2 text-lg leading-snug font-semibold">
                    <a [routerLink]="['/status', r.applicationNumber]" class="group-hover:text-primary after:absolute after:inset-0 hover:underline">{{ r.title }}</a>
                  </h2>
                  @if (r.abstractSnippet) {
                    <p class="text-muted-foreground mt-1 line-clamp-2 text-sm">{{ r.abstractSnippet }}</p>
                  }
                  <dl class="text-muted-foreground mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs">
                    @if (r.firstNamedInventor) {
                      <div><dt class="inline">Inventor: </dt><dd class="text-foreground inline">{{ r.firstNamedInventor }}</dd></div>
                    }
                    <div><dt class="inline">Filed: </dt><dd class="text-foreground inline">{{ date(r.filingDate) }}</dd></div>
                    @if (r.publicationNumber) {
                      <div><dt class="inline">Publication: </dt><dd class="text-foreground inline font-mono">{{ r.publicationNumber }}</dd></div>
                    }
                    @if (r.patentNumber) {
                      <div><dt class="inline">Patent: </dt><dd class="inline font-mono font-semibold text-emerald-700 dark:text-emerald-400">{{ r.patentNumber }}</dd></div>
                    }
                  </dl>
                </article>
              </li>
            }
          </ol>
          <app-pager [page]="page.page" [totalPages]="page.totalPages" [size]="page.size" [totalElements]="page.totalElements" ariaLabel="Result pages" (pageChange)="setPage($event)" />
        }
      }

      <p class="text-muted-foreground flex items-start gap-2 text-xs">
        <ng-icon name="lucideShieldCheck" class="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        Public copies are generated automatically: personal information (e.g. SSNs, addresses, account numbers) and controlled markings are
        permanently redacted before release.
      </p>
    </div>
  `,
})
export class SearchPage {
  private readonly api = inject(PublicApi);
  private readonly router = inject(Router);

  readonly q = input<string>();
  readonly type = input<string>();
  readonly page = input<string>();

  protected readonly types = APPLICATION_TYPES;
  protected readonly qDraft = linkedSignal(() => this.q() ?? '');

  protected readonly results = rxResource({
    params: () => ({
      q: this.q() ?? '',
      type: (APPLICATION_TYPES.some((t) => t.code === this.type()) ? this.type() : undefined) as ApplicationType | undefined,
      page: Math.max(0, Number.parseInt(this.page() ?? '0', 10) || 0),
      size: 10,
    }),
    stream: ({ params }) => this.api.search(params),
  });
  protected readonly hasQuery = computed(() => !!this.q());

  protected apply(): void {
    this.navigate({ q: this.qDraft().trim() || null, page: null });
  }

  protected setType(v: string | string[]): void {
    const t = Array.isArray(v) ? (v[0] ?? '') : v;
    this.navigate({ type: t || null, page: null });
  }

  protected setPage(p: number): void {
    this.navigate({ page: p ? String(p) : null });
  }

  protected typeLabel(code: string): string {
    return APPLICATION_TYPES.find((t) => t.code === code)?.label ?? code;
  }

  protected date(v: string | null): string {
    return formatDate(v);
  }

  protected count(n: number): string {
    return formatNumber(n);
  }

  private navigate(params: Record<string, string | null>): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
