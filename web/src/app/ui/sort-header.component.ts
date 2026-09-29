import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowDown, lucideArrowUp, lucideArrowUpDown } from '@ng-icons/lucide';

export interface SortState {
  field: string;
  dir: 'asc' | 'desc';
}

/** `title,asc` → `{ field: 'title', dir: 'asc' }` (defaults to `fallback` when blank/invalid). */
export function parseSort(raw: string | null | undefined, fallback: SortState): SortState {
  if (!raw) return fallback;
  const [field, dir] = raw.split(',');
  if (!field) return fallback;
  return { field, dir: dir === 'asc' ? 'asc' : 'desc' };
}

export function formatSort(s: SortState): string {
  return `${s.field},${s.dir}`;
}

/**
 * Sortable column header content. Put it inside a `<th>` and bind `[attr.aria-sort]` on the th with
 * `ariaSort(sort, field)`. Clicking toggles asc/desc (first click uses `firstDir`).
 */
@Component({
  selector: 'app-sort-header',
  imports: [NgIcon],
  viewProviders: [provideIcons({ lucideArrowUp, lucideArrowDown, lucideArrowUpDown })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      class="hover:text-foreground focus-visible:ring-ring/50 -mx-1 inline-flex items-center gap-1 rounded px-1 py-0.5 outline-none focus-visible:ring-2"
      [class.text-foreground]="active()"
      (click)="toggle()"
    >
      <ng-content />
      <ng-icon [name]="icon()" class="size-3.5" [class.opacity-40]="!active()" aria-hidden="true" />
      <span class="sr-only">{{ active() ? (sort().dir === 'asc' ? ', sorted ascending' : ', sorted descending') : ', sortable' }}</span>
    </button>
  `,
})
export class SortHeaderComponent {
  readonly field = input.required<string>();
  readonly sort = input.required<SortState>();
  readonly firstDir = input<'asc' | 'desc'>('asc');
  readonly sortChange = output<SortState>();

  protected readonly active = computed(() => this.sort().field === this.field());
  protected readonly icon = computed(() =>
    !this.active() ? 'lucideArrowUpDown' : this.sort().dir === 'asc' ? 'lucideArrowUp' : 'lucideArrowDown',
  );

  protected toggle(): void {
    const dir = this.active() ? (this.sort().dir === 'asc' ? 'desc' : 'asc') : this.firstDir();
    this.sortChange.emit({ field: this.field(), dir });
  }
}

export function ariaSort(sort: SortState, field: string): 'ascending' | 'descending' | 'none' {
  return sort.field === field ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none';
}
