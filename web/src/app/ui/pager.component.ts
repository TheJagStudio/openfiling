import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { ZardPaginationImports } from '@/shared/components/pagination';

/** Page numbers to show around `current` (1-based), with `null` for an ellipsis gap. */
export function pageWindow(current: number, total: number, radius = 1): (number | null)[] {
  if (total <= 0) return [];
  if (total <= 5 + radius * 2) return Array.from({ length: total }, (_, i) => i + 1);
  const pages = new Set<number>([1, total]);
  for (let p = current - radius; p <= current + radius; p++) if (p >= 1 && p <= total) pages.add(p);
  const sorted = [...pages].sort((a, b) => a - b);
  const out: (number | null)[] = [];
  sorted.forEach((p, i) => {
    const prev = sorted[i - 1];
    if (i > 0 && prev !== undefined && p - prev > 1) out.push(p - prev === 2 ? p - 1 : null);
    out.push(p);
  });
  return out;
}

/**
 * Windowed pager built from ZardUI pagination primitives. Works with the API's 0-based
 * `page`, shows 1-based numbers, and never renders hundreds of buttons.
 */
@Component({
  selector: 'app-pager',
  imports: [DecimalPipe, ...ZardPaginationImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (totalPages() > 1) {
      <nav class="flex flex-col items-center justify-between gap-3 sm:flex-row" [attr.aria-label]="ariaLabel()">
        <p class="text-muted-foreground text-sm" aria-live="polite">
          @if (totalElements() !== null) {
            {{ rangeStart() | number }}–{{ rangeEnd() | number }} of {{ totalElements() | number }}
          } @else {
            Page {{ page() + 1 }} of {{ totalPages() | number }}
          }
        </p>
        <ul z-pagination-content>
          <li z-pagination-item>
            <z-pagination-previous [zDisabled]="page() === 0" (click)="go(page() - 1)" />
          </li>
          @for (p of pages(); track $index) {
            <li z-pagination-item class="hidden sm:list-item">
              @if (p === null) {
                <z-pagination-ellipsis />
              } @else {
                <button
                  z-pagination-button
                  type="button"
                  [zActive]="p === page() + 1"
                  [attr.aria-current]="p === page() + 1 ? 'page' : null"
                  [attr.aria-label]="'Page ' + p"
                  (click)="go(p - 1)"
                >
                  {{ p }}
                </button>
              }
            </li>
          }
          <li z-pagination-item>
            <z-pagination-next [zDisabled]="page() >= totalPages() - 1" (click)="go(page() + 1)" />
          </li>
        </ul>
      </nav>
    }
  `,
})
export class PagerComponent {
  /** 0-based current page. */
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly size = input<number>(20);
  readonly totalElements = input<number | null>(null);
  readonly ariaLabel = input('Pagination');
  /** Emits the requested 0-based page. */
  readonly pageChange = output<number>();

  protected readonly pages = computed(() => pageWindow(this.page() + 1, this.totalPages()));
  protected readonly rangeStart = computed(() => this.page() * this.size() + 1);
  protected readonly rangeEnd = computed(() =>
    Math.min((this.page() + 1) * this.size(), this.totalElements() ?? Number.MAX_SAFE_INTEGER),
  );

  protected go(page: number): void {
    if (page < 0 || page >= this.totalPages() || page === this.page()) return;
    this.pageChange.emit(page);
  }
}
