import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { formatRelative } from '../core/format';
import type { ApplicationSummary } from '../core/models';
import { StatusBadgeComponent } from './status-badge.component';

/** Compact list of application summaries (dashboard cards). */
@Component({
  selector: 'app-application-list',
  imports: [RouterLink, StatusBadgeComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (items().length) {
      <ul class="divide-y">
        @for (a of items(); track a.id) {
          <li>
            <a
              [routerLink]="['/applications', a.id]"
              class="hover:bg-muted/50 focus-visible:bg-muted -mx-2 flex items-center gap-3 rounded-md px-2 py-3 outline-none"
            >
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm font-medium">{{ a.title }}</span>
                <span class="text-muted-foreground block text-xs">
                  <span class="font-mono">{{ a.applicationNumberDisplay ?? 'Draft' }}</span> · updated {{ relative(a.updatedAt) }}
                </span>
              </span>
              <app-status-badge class="shrink-0" [status]="a.status" [category]="a.statusCategory" [title]="a.statusLabel" />
            </a>
          </li>
        }
      </ul>
    } @else {
      <p class="text-muted-foreground py-6 text-center text-sm">{{ empty() }}</p>
    }
  `,
})
export class ApplicationListComponent {
  readonly items = input.required<ApplicationSummary[]>();
  readonly empty = input('Nothing here yet.');

  protected relative(v: string): string {
    return formatRelative(v);
  }
}
