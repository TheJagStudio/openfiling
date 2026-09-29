import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { NgIcon } from '@ng-icons/core';

import { ZardSkeletonComponent } from '@/shared/components/skeleton';

/**
 * KPI tile: label, big tabular value, optional hint and icon. Becomes a link when `link` is set.
 * Icons must be provided by the host component (`provideIcons`).
 */
@Component({
  selector: 'app-stat-card',
  imports: [RouterLink, NgIcon, ZardSkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bg-card relative flex h-full flex-col gap-1 rounded-xl border p-4 shadow-xs transition-shadow" [class.hover:shadow-md]="!!link()">
      <div class="flex items-center justify-between gap-2">
        <span class="text-muted-foreground text-xs font-medium tracking-wide uppercase">{{ label() }}</span>
        @if (icon()) {
          <span class="grid size-7 place-items-center rounded-md" [class]="accent()">
            <ng-icon [name]="icon()!" class="size-4" aria-hidden="true" />
          </span>
        }
      </div>
      @if (loading()) {
        <z-skeleton class="mt-1 h-8 w-20" />
      } @else {
        <span class="tabular text-3xl font-semibold tracking-tight">{{ value() ?? '—' }}</span>
      }
      @if (hint()) {
        <span class="text-muted-foreground text-xs">{{ hint() }}</span>
      }
      @if (link()) {
        <a [routerLink]="link()" [queryParams]="query()" class="absolute inset-0 rounded-xl focus-visible:ring-2 focus-visible:ring-ring/50 outline-none">
          <span class="sr-only">{{ label() }}: view</span>
        </a>
      }
    </div>
  `,
  host: { class: 'block' },
})
export class StatCardComponent {
  readonly label = input.required<string>();
  readonly value = input<string | number | null>(null);
  readonly hint = input<string>('');
  readonly icon = input<string | null>(null);
  readonly accent = input('bg-primary/10 text-primary');
  readonly loading = input(false);
  readonly link = input<string | unknown[] | null>(null);
  readonly query = input<Record<string, string> | null>(null);
}
