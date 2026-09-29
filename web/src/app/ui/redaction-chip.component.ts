import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideLoaderCircle, lucideLock, lucideShieldCheck } from '@ng-icons/lucide';

import { ZardTooltipImports } from '@/shared/components/tooltip';

import { REDACTION_CATEGORY_LABELS, type RedactionCategory, type RedactionResponse, type Visibility } from '../core/models';
import { TONE_CLASSES } from '../core/status-tone';

/** One-line summary `SSN ×2 · Email ×1` (sorted by count, desc). */
export function summarizeCategories(r: Pick<RedactionResponse, 'categories'> | null | undefined, max = 4): string {
  const cats = [...(r?.categories ?? [])].filter((c) => c.count > 0).sort((a, b) => b.count - a.count);
  if (!cats.length) return '';
  const shown = cats.slice(0, max).map((c) => `${REDACTION_CATEGORY_LABELS[c.category as RedactionCategory] ?? c.category} ×${c.count}`);
  return cats.length > max ? `${shown.join(' · ')} · +${cats.length - max} more` : shown.join(' · ');
}

/** Redaction status of a document's public copy: spinner while processing, counts + categories when done. */
@Component({
  selector: 'app-redaction-chip',
  imports: [NgIcon, ...ZardTooltipImports],
  viewProviders: [provideIcons({ lucideLoaderCircle, lucideShieldCheck, lucideCircleAlert, lucideLock })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @switch (state()) {
      @case ('RESTRICTED') {
        <span class="text-muted-foreground inline-flex items-center gap-1 text-xs"><ng-icon name="lucideLock" class="size-3.5" aria-hidden="true" /> Restricted — never public</span>
      }
      @case ('NONE') {
        <span class="text-muted-foreground text-xs">—</span>
      }
      @case ('PENDING') {
        <span [class]="chip('neutral')" role="status"><ng-icon name="lucideLoaderCircle" class="size-3.5 animate-spin" aria-hidden="true" /> Queued for redaction</span>
      }
      @case ('PROCESSING') {
        <span [class]="chip('progress')" role="status"><ng-icon name="lucideLoaderCircle" class="size-3.5 animate-spin" aria-hidden="true" /> Redacting PII / CUI…</span>
      }
      @case ('COMPLETED') {
        <span class="inline-flex flex-col gap-0.5">
          <span [class]="chip('success')" [zTooltip]="engineNote()">
            <ng-icon name="lucideShieldCheck" class="size-3.5" aria-hidden="true" />
            {{ count() }} {{ count() === 1 ? 'redaction' : 'redactions' }}
          </span>
          @if (summary()) {
            <span class="text-muted-foreground max-w-56 text-[11px] leading-tight">{{ summary() }}</span>
          }
        </span>
      }
      @case ('FAILED') {
        <span [class]="chip('danger')" [zTooltip]="redaction()?.error || 'The redaction function reported an error.'">
          <ng-icon name="lucideCircleAlert" class="size-3.5" aria-hidden="true" /> Redaction failed
        </span>
      }
    }
  `,
})
export class RedactionChipComponent {
  readonly redaction = input<RedactionResponse | null | undefined>(null);
  readonly visibility = input<Visibility>('PUBLIC');

  protected readonly state = computed(() => {
    if (this.visibility() === 'RESTRICTED') return 'RESTRICTED';
    return this.redaction()?.status ?? 'NONE';
  });
  protected readonly count = computed(() => this.redaction()?.redactionCount ?? 0);
  protected readonly summary = computed(() => summarizeCategories(this.redaction()));
  protected readonly engineNote = computed(() => {
    const r = this.redaction();
    if (!r) return '';
    const engine = r.engine === 'CLAUDE+RULES' ? `Claude (${r.model ?? 'model'}) + rules` : r.engine === 'RULES' ? 'Deterministic rules only' : 'Redaction engine';
    return `${engine}${r.pageCount ? ` · ${r.pageCount} pages` : ''}`;
  });

  protected chip(tone: keyof typeof TONE_CLASSES): string {
    return `inline-flex w-fit items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${TONE_CLASSES[tone]}`;
  }
}
