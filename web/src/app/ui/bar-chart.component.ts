import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

export interface BarDatum {
  label: string;
  value: number;
  /** Optional long label for tooltip / screen readers. */
  title?: string;
}

/** Rounds a max value up to a "nice" axis ceiling (1, 2, 2.5, 5 × 10ⁿ). */
export function niceCeil(max: number): number {
  if (max <= 0) return 1;
  const exp = Math.pow(10, Math.floor(Math.log10(max)));
  for (const step of [1, 2, 2.5, 5, 10]) {
    if (max <= step * exp) return step * exp;
  }
  return 10 * exp;
}

/**
 * Dependency-free responsive SVG bar chart. Bars are keyboard/hover inspectable and the data is
 * also exposed as a visually-hidden table for screen readers.
 */
@Component({
  selector: 'app-bar-chart',
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="w-full">
      <div class="relative">
        <svg
          [attr.viewBox]="'0 0 ' + width + ' ' + height()"
          class="h-auto w-full overflow-visible"
          role="img"
          [attr.aria-label]="ariaLabel()"
        >
          @for (tick of ticks(); track tick) {
            <line
              [attr.x1]="padLeft"
              [attr.x2]="width"
              [attr.y1]="y(tick)"
              [attr.y2]="y(tick)"
              class="stroke-border"
              stroke-dasharray="3 3"
              vector-effect="non-scaling-stroke"
            />
            <text [attr.x]="padLeft - 6" [attr.y]="y(tick) + 3" text-anchor="end" class="fill-muted-foreground text-[10px]">
              {{ compact(tick) }}
            </text>
          }
          @for (d of data(); track d.label; let i = $index) {
            <rect
              [attr.x]="barX(i)"
              [attr.y]="y(d.value)"
              [attr.width]="barWidth()"
              [attr.height]="plotBottom() - y(d.value)"
              rx="2"
              class="fill-primary/80 hover:fill-primary focus:fill-primary cursor-default outline-none transition-colors"
              [class.fill-primary]="active() === i"
              tabindex="0"
              [attr.aria-label]="(d.title ?? d.label) + ': ' + d.value"
              (mouseenter)="active.set(i)"
              (mouseleave)="active.set(null)"
              (focus)="active.set(i)"
              (blur)="active.set(null)"
            />
          }
          @for (d of data(); track d.label; let i = $index) {
            @if (showLabel(i)) {
              <text
                [attr.x]="barX(i) + barWidth() / 2"
                [attr.y]="height() - 4"
                text-anchor="middle"
                class="fill-muted-foreground text-[10px]"
              >
                {{ d.label }}
              </text>
            }
          }
        </svg>
        @if (activeDatum(); as a) {
          <div
            class="bg-popover text-popover-foreground pointer-events-none absolute top-0 rounded-md border px-2 py-1 text-xs shadow-sm"
            [style.left.%]="tooltipLeft()"
            aria-hidden="true"
          >
            <div class="font-medium">{{ a.title ?? a.label }}</div>
            <div class="text-muted-foreground">{{ a.value | number }} {{ unit() }}</div>
          </div>
        }
      </div>
      @if (caption()) {
        <figcaption class="text-muted-foreground mt-2 text-xs">{{ caption() }}</figcaption>
      }
      <div class="sr-only"><table>
        <caption>{{ ariaLabel() }}</caption>
        <thead><tr><th scope="col">Label</th><th scope="col">{{ unit() || 'Value' }}</th></tr></thead>
        <tbody>
          @for (d of data(); track d.label) {
            <tr><td>{{ d.title ?? d.label }}</td><td>{{ d.value }}</td></tr>
          }
        </tbody>
      </table></div>
    </figure>
  `,
})
export class BarChartComponent {
  readonly data = input.required<BarDatum[]>();
  readonly height = input(180);
  readonly ariaLabel = input('Bar chart');
  readonly caption = input<string>('');
  readonly unit = input<string>('');
  /** Show every n-th x label (auto when 0). */
  readonly labelEvery = input(0);

  protected readonly width = 600;
  protected readonly padLeft = 40;
  private readonly padBottom = 18;
  protected readonly active = signal<number | null>(null);

  protected readonly max = computed(() => niceCeil(Math.max(0, ...this.data().map((d) => d.value))));
  protected readonly ticks = computed(() => [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(this.max() * f)));
  protected readonly plotBottom = computed(() => this.height() - this.padBottom);
  private readonly slot = computed(() => (this.width - this.padLeft) / Math.max(1, this.data().length));
  protected readonly barWidth = computed(() => Math.max(1, this.slot() * 0.7));
  protected readonly activeDatum = computed(() => {
    const i = this.active();
    return i === null ? null : (this.data()[i] ?? null);
  });
  protected readonly tooltipLeft = computed(() => {
    const i = this.active() ?? 0;
    const x = this.barX(i) + this.barWidth() / 2;
    return Math.min(80, Math.max(0, (x / this.width) * 100 - 8));
  });

  protected y(value: number): number {
    const top = 6;
    return top + (1 - value / this.max()) * (this.plotBottom() - top);
  }

  protected barX(i: number): number {
    return this.padLeft + i * this.slot() + (this.slot() - this.barWidth()) / 2;
  }

  protected showLabel(i: number): boolean {
    const n = this.data().length;
    const every = this.labelEvery() || Math.max(1, Math.ceil(n / 8));
    return i % every === 0 || i === n - 1;
  }

  protected compact(n: number): string {
    return new Intl.NumberFormat('en-US', { notation: 'compact' }).format(n);
  }
}
