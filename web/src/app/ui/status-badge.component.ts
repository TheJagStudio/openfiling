import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { ZardBadgeComponent } from '@/shared/components/badge';

import { humanize } from '../core/format';
import { type ApplicationStatus, STATUS_META, type StatusCategory } from '../core/models';
import { TONE_CLASSES, TONE_DOT, toneOf, toneOfCategory, type Tone } from '../core/status-tone';

/**
 * ZardUI badge with a semantic colour + dot for any domain status. Application statuses are toned
 * by their contract `statusCategory` (pass `category` when the API provides it).
 */
@Component({
  selector: 'app-status-badge',
  imports: [ZardBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <z-badge zType="outline" [class]="classes()" [attr.data-tone]="tone()" [attr.title]="title() || null">
      <span class="size-1.5 shrink-0 rounded-full" [class]="dot()" aria-hidden="true"></span>
      <span class="truncate">{{ text() }}</span>
    </z-badge>
  `,
})
export class StatusBadgeComponent {
  readonly status = input.required<string | null | undefined>();
  /** Override the displayed text (defaults to the short status label / humanized code). */
  readonly label = input<string>();
  /** Contract `statusCategory`; drives the tone for applications. */
  readonly category = input<StatusCategory | null | undefined>(undefined);
  /** Override the tone. */
  readonly toneOverride = input<Tone | undefined>(undefined, { alias: 'tone' });
  /** Tooltip (e.g. the long status label). */
  readonly title = input<string | null | undefined>(undefined);

  protected readonly tone = computed(
    () => this.toneOverride() ?? (this.category() ? toneOfCategory(this.category()) : toneOf(this.status())),
  );
  protected readonly text = computed(() => {
    const explicit = this.label();
    if (explicit) return explicit;
    const s = this.status();
    return (s && STATUS_META[s as ApplicationStatus]?.short) || humanize(s);
  });
  protected readonly classes = computed(() => `max-w-full border-transparent ring-1 ring-inset ${TONE_CLASSES[this.tone()]}`);
  protected readonly dot = computed(() => TONE_DOT[this.tone()]);
}
