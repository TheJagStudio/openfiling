import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Consistent page title block: eyebrow, h1, description, and an actions slot. */
@Component({
  selector: 'app-page-header',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ng-content select="[breadcrumb]" />
    <div class="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
      <div class="min-w-0 space-y-1.5">
        @if (eyebrow()) {
          <p class="text-primary text-xs font-semibold tracking-wider uppercase">{{ eyebrow() }}</p>
        }
        <h1 class="text-2xl font-semibold tracking-tight text-balance sm:text-3xl">{{ heading() }}</h1>
        @if (description()) {
          <p class="text-muted-foreground max-w-3xl text-sm text-pretty sm:text-base">{{ description() }}</p>
        }
        <ng-content select="[meta]" />
      </div>
      <div class="flex shrink-0 flex-wrap items-center gap-2 print:hidden">
        <ng-content select="[actions]" />
      </div>
    </div>
  `,
  host: { class: 'block space-y-3' },
})
export class PageHeaderComponent {
  readonly heading = input.required<string>();
  readonly description = input<string>('');
  readonly eyebrow = input<string>('');
}
