import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowDownUp, lucideArrowRight, lucideMessageSquareQuote } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';

import { formatDateTime, formatRelative, humanize } from '../../../core/format';
import { type ApplicationStatus, ROLE_LABELS, type Role, STATUS_META, type StatusEventResponse } from '../../../core/models';
import { TONE_SOLID, toneOfApplicationStatus } from '../../../core/status-tone';

/** TSDR-style prosecution history: vertical timeline with actor role, status transition and note. */
@Component({
  selector: 'app-status-timeline',
  imports: [NgIcon, ZardButtonComponent],
  viewProviders: [provideIcons({ lucideArrowRight, lucideMessageSquareQuote, lucideArrowDownUp })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mb-3 flex items-center justify-between gap-2">
      <p class="text-muted-foreground text-xs">{{ events().length }} {{ events().length === 1 ? 'event' : 'events' }} · {{ newestFirst() ? 'newest first' : 'oldest first' }}</p>
      <button type="button" z-button zType="ghost" zSize="sm" (click)="newestFirst.set(!newestFirst())" [attr.aria-pressed]="newestFirst()">
        <ng-icon name="lucideArrowDownUp" aria-hidden="true" /> {{ newestFirst() ? 'Show oldest first' : 'Show newest first' }}
      </button>
    </div>
    @if (!events().length) {
      <p class="text-muted-foreground rounded-lg border border-dashed p-6 text-center text-sm">No history recorded yet.</p>
    } @else {
      <ol class="relative ml-2 border-l pl-6">
        @for (e of ordered(); track e.id; let first = $first) {
          <li class="relative pb-7 last:pb-0">
            <span
              class="ring-background absolute top-1 -left-[31px] size-3.5 rounded-full ring-4"
              [class]="marker(e)"
              aria-hidden="true"
            ></span>
            <div class="flex flex-col gap-1 sm:flex-row sm:items-baseline sm:justify-between sm:gap-4">
              <p class="font-medium" [class.text-primary]="first && newestFirst()">{{ e.description || humanize(e.eventCode) }}</p>
              <time class="text-muted-foreground shrink-0 text-xs whitespace-nowrap" [attr.datetime]="e.createdAt" [attr.title]="relative(e.createdAt)">
                {{ dateTime(e.createdAt) }}
              </time>
            </div>
            <div class="text-muted-foreground mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
              @if (e.toStatus && e.fromStatus !== e.toStatus) {
                <span class="inline-flex items-center gap-1">
                  @if (e.fromStatus) {
                    <span>{{ short(e.fromStatus) }}</span>
                    <ng-icon name="lucideArrowRight" class="size-3" aria-label="to" />
                  }
                  <span class="text-foreground font-medium">{{ short(e.toStatus) }}</span>
                </span>
              }
              @if (e.actor) {
                <span>
                  by <span class="text-foreground">{{ e.actor.displayName }}</span>
                  <span class="bg-muted ml-1 rounded px-1.5 py-0.5 text-[10px] font-semibold tracking-wide uppercase">{{ role(e.actor.role) }}</span>
                </span>
              }
              <span class="font-mono text-[10.5px] opacity-70">{{ e.eventCode }}</span>
            </div>
            @if (e.note) {
              <blockquote class="bg-muted/50 mt-2 flex gap-2 rounded-md border-l-2 border-primary/40 px-3 py-2 text-sm whitespace-pre-line">
                <ng-icon name="lucideMessageSquareQuote" class="text-muted-foreground mt-0.5 size-4 shrink-0" aria-hidden="true" />
                <span><span class="sr-only">Note: </span>{{ e.note }}</span>
              </blockquote>
            }
          </li>
        }
      </ol>
    }
  `,
})
export class StatusTimelineComponent {
  /** Oldest first, as returned by `GET /applications/{id}/history`. */
  readonly events = input.required<StatusEventResponse[]>();
  protected readonly newestFirst = signal(true);
  protected readonly ordered = computed(() => (this.newestFirst() ? [...this.events()].reverse() : this.events()));
  protected readonly humanize = humanize;

  protected marker(e: StatusEventResponse): string {
    if (!e.toStatus || e.toStatus === e.fromStatus) return 'bg-muted-foreground/40 size-2.5 top-1.5 -left-[29px]';
    return TONE_SOLID[toneOfApplicationStatus(e.toStatus)];
  }

  protected short(s: ApplicationStatus): string {
    return STATUS_META[s]?.short ?? s;
  }

  protected role(r: string): string {
    return ROLE_LABELS[r as Role] ?? humanize(r);
  }

  protected dateTime(v: string): string {
    return formatDateTime(v);
  }

  protected relative(v: string): string {
    return formatRelative(v);
  }
}
