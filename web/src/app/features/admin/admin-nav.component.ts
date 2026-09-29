import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideActivity, lucideScrollText, lucideUsers } from '@ng-icons/lucide';

import { ADMIN_NAV } from '../../layout/nav';

/** Secondary navigation shared by the admin pages. */
@Component({
  selector: 'app-admin-nav',
  imports: [RouterLink, RouterLinkActive, NgIcon],
  viewProviders: [provideIcons({ lucideUsers, lucideScrollText, lucideActivity })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav aria-label="Administration" class="border-b">
      <ul class="-mb-px flex gap-1 overflow-x-auto">
        @for (item of items; track item.path) {
          <li>
            <a
              [routerLink]="item.path"
              routerLinkActive="border-primary text-foreground"
              ariaCurrentWhenActive="page"
              class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 inline-flex items-center gap-2 border-b-2 border-transparent px-3 py-2.5 text-sm font-medium whitespace-nowrap outline-none focus-visible:ring-2"
            >
              <ng-icon [name]="item.icon" class="size-4" aria-hidden="true" /> {{ item.label }}
            </a>
          </li>
        }
      </ul>
    </nav>
  `,
})
export class AdminNavComponent {
  protected readonly items = ADMIN_NAV;
}
