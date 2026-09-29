import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBell, lucideCheckCheck } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardPopoverImports } from '@/shared/components/popover';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';

import { NotificationsApi } from '../core/api/notifications.api';
import { formatRelative } from '../core/format';
import type { NotificationResponse } from '../core/models';
import { UnreadStore } from '../core/notifications/unread.store';

/** Bell with unread badge; opens a popover with the latest notifications. */
@Component({
  selector: 'app-notification-bell',
  imports: [RouterLink, NgIcon, ZardButtonComponent, ZardSkeletonComponent, ...ZardPopoverImports],
  viewProviders: [provideIcons({ lucideBell, lucideCheckCheck })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      z-button
      zType="ghost"
      zSize="icon"
      class="relative"
      zPopover
      [zContent]="panel"
      zPlacement="bottom"
      zAlign="end"
      [attr.aria-label]="unread.count() ? 'Notifications, ' + unread.count() + ' unread' : 'Notifications'"
      (zVisibleChange)="onToggle($event)"
    >
      <ng-icon name="lucideBell" aria-hidden="true" />
      @if (unread.count() > 0) {
        <span
          class="bg-destructive ring-background absolute -top-0.5 -right-0.5 grid h-4 min-w-4 place-items-center rounded-full px-1 text-[10px] leading-none font-semibold text-white ring-2"
          aria-hidden="true"
          >{{ unread.count() > 99 ? '99+' : unread.count() }}</span
        >
      }
    </button>

    <ng-template #panel>
      <div z-popover class="w-[22rem] max-w-[calc(100vw-2rem)] p-0">
        <div class="flex items-center justify-between border-b px-4 py-2.5">
          <h2 class="text-sm font-semibold">Notifications</h2>
          <button type="button" z-button zType="ghost" zSize="xs" [zDisabled]="!unread.count()" (click)="markAll()">
            <ng-icon name="lucideCheckCheck" aria-hidden="true" /> Mark all read
          </button>
        </div>
        <div class="max-h-96 overflow-y-auto" aria-live="polite">
          @if (loading() && !items().length) {
            <div class="space-y-3 p-4">
              @for (i of [1, 2, 3]; track i) {
                <z-skeleton class="h-10 w-full" />
              }
            </div>
          } @else if (!items().length) {
            <p class="text-muted-foreground px-4 py-10 text-center text-sm">You’re all caught up.</p>
          } @else {
            <ul class="divide-y">
              @for (n of items(); track n.id) {
                <li>
                  <button
                    type="button"
                    class="hover:bg-muted/60 focus-visible:bg-muted flex w-full gap-3 px-4 py-3 text-left outline-none"
                    (click)="open(n)"
                  >
                    <span class="mt-1.5 size-2 shrink-0 rounded-full" [class]="n.read ? 'bg-transparent' : 'bg-primary'" aria-hidden="true"></span>
                    <span class="min-w-0 flex-1">
                      <span class="block truncate text-sm" [class.font-semibold]="!n.read">{{ n.title }}</span>
                      <span class="text-muted-foreground line-clamp-2 text-xs">{{ n.message }}</span>
                      <span class="text-muted-foreground mt-0.5 block text-[11px]">
                        {{ relative(n.createdAt) }}
                        @if (n.applicationNumber) {
                          · {{ n.applicationNumber }}
                        }
                        @if (!n.read) {
                          <span class="sr-only">(unread)</span>
                        }
                      </span>
                    </span>
                  </button>
                </li>
              }
            </ul>
          }
        </div>
        <div class="border-t p-2">
          <a z-button zType="ghost" zSize="sm" class="w-full" routerLink="/notifications">View all notifications</a>
        </div>
      </div>
    </ng-template>
  `,
})
export class NotificationBellComponent {
  private readonly api = inject(NotificationsApi);
  private readonly router = inject(Router);
  protected readonly unread = inject(UnreadStore);

  protected readonly items = signal<NotificationResponse[]>([]);
  protected readonly loading = signal(false);

  protected onToggle(open: boolean): void {
    if (!open) return;
    this.loading.set(true);
    this.api.list({ page: 0, size: 6 }, true).subscribe({
      next: (p) => {
        this.items.set(p.content);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  protected open(n: NotificationResponse): void {
    if (!n.read) {
      this.api.markRead(n.id).subscribe({ next: () => this.unread.decrement(), error: () => undefined });
      this.items.update((list) => list.map((x) => (x.id === n.id ? { ...x, read: true } : x)));
    }
    void this.router.navigateByUrl(n.applicationId ? `/applications/${n.applicationId}` : '/notifications');
  }

  protected markAll(): void {
    this.api.markAllRead().subscribe(() => {
      this.unread.clear();
      this.items.update((list) => list.map((x) => ({ ...x, read: true })));
    });
  }

  protected relative(iso: string): string {
    return formatRelative(iso);
  }
}
