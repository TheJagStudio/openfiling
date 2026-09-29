import { ChangeDetectionStrategy, Component, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideBell, lucideBellOff, lucideCheck, lucideCheckCheck, lucideExternalLink, lucideRefreshCw } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardEmptyComponent } from '@/shared/components/empty';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardSwitchComponent } from '@/shared/components/switch';

import { NotificationsApi } from '../../core/api/notifications.api';
import { formatApplicationNumber } from '../../core/application-number';
import { formatDateTime, formatRelative, humanize } from '../../core/format';
import type { NotificationResponse } from '../../core/models';
import { UnreadStore } from '../../core/notifications/unread.store';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { PagerComponent } from '../../ui/pager.component';

@Component({
  selector: 'app-notifications',
  imports: [
    RouterLink,
    NgIcon,
    ZardButtonComponent,
    ZardSkeletonComponent,
    ZardEmptyComponent,
    ZardSwitchComponent,
    PageHeaderComponent,
    PagerComponent,
    ErrorStateComponent,
  ],
  viewProviders: [provideIcons({ lucideBell, lucideBellOff, lucideCheck, lucideCheckCheck, lucideRefreshCw, lucideExternalLink })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-4xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header eyebrow="Inbox" heading="Notifications" description="Status changes, office actions and responses on applications you own or examine.">
        <div actions class="flex flex-wrap items-center gap-3">
          <z-switch zId="unread-only" [zChecked]="unreadOnly()" (zCheckedChange)="setUnreadOnly($event)">Unread only</z-switch>
          <button type="button" z-button zType="outline" [zDisabled]="!unread.count()" (click)="markAll()">
            <ng-icon name="lucideCheckCheck" aria-hidden="true" /> Mark all read
          </button>
        </div>
      </app-page-header>

      @if (list.isLoading() && !list.hasValue()) {
        <div class="space-y-2">@for (i of [1, 2, 3, 4]; track i) {<z-skeleton class="h-20 w-full" />}</div>
      } @else if (list.error()) {
        <app-error-state [error]="list.error()" (retry)="list.reload()" />
      } @else if (list.hasValue()) {
        @let page = list.value();
        @if (!items().length) {
          <z-empty
            class="rounded-xl border border-dashed py-14"
            zIcon="lucideBellOff"
            [zTitle]="unreadOnly() ? 'No unread notifications' : 'No notifications yet'"
            zDescription="You’ll be notified when an application you own or examine changes status."
          />
        } @else {
          <ul class="bg-card divide-y rounded-xl border" aria-live="polite">
            @for (n of items(); track n.id) {
              <li class="flex gap-4 p-4" [class.bg-primary/[0.03]]="!n.read">
                <span class="mt-1.5 size-2.5 shrink-0 rounded-full" [class]="n.read ? 'bg-border' : 'bg-primary'" aria-hidden="true"></span>
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-baseline justify-between gap-x-3">
                    <p class="text-sm" [class.font-semibold]="!n.read">
                      {{ n.title }}
                      @if (!n.read) {
                        <span class="sr-only">(unread)</span>
                      }
                    </p>
                    <time class="text-muted-foreground text-xs" [attr.datetime]="n.createdAt" [title]="dateTime(n.createdAt)">{{ relative(n.createdAt) }}</time>
                  </div>
                  <p class="text-muted-foreground mt-0.5 text-sm">{{ n.message }}</p>
                  <div class="mt-2 flex flex-wrap items-center gap-2">
                    <span class="bg-muted text-muted-foreground rounded px-1.5 py-0.5 text-[10.5px] font-semibold tracking-wide uppercase">{{ humanize(n.type) }}</span>
                    @if (n.applicationId) {
                      <a z-button zType="link" zSize="sm" class="h-auto px-0" [routerLink]="['/applications', n.applicationId]" (click)="markRead(n)">
                        {{ n.applicationNumber ? formatNumber(n.applicationNumber) : 'Open application' }} <ng-icon name="lucideExternalLink" aria-hidden="true" />
                      </a>
                    }
                    @if (!n.read) {
                      <button type="button" z-button zType="ghost" zSize="sm" class="ml-auto" (click)="markRead(n)"><ng-icon name="lucideCheck" aria-hidden="true" /> Mark read</button>
                    }
                  </div>
                </div>
              </li>
            }
          </ul>
          <app-pager [page]="page.page" [totalPages]="page.totalPages" [size]="page.size" [totalElements]="page.totalElements" ariaLabel="Notification pages" (pageChange)="pageIndex.set($event)" />
        }
      }
    </div>
  `,
})
export class NotificationsPage {
  private readonly api = inject(NotificationsApi);
  protected readonly unread = inject(UnreadStore);

  protected readonly unreadOnly = signal(false);
  protected readonly pageIndex = signal(0);
  protected readonly list = rxResource({
    params: () => ({ unreadOnly: this.unreadOnly() || undefined, page: this.pageIndex(), size: 20 }),
    stream: ({ params }) => this.api.list(params),
  });
  protected readonly items = linkedSignal<NotificationResponse[]>(() => (this.list.hasValue() ? this.list.value().content : []));
  protected readonly humanize = humanize;
  protected readonly formatNumber = formatApplicationNumber;

  protected setUnreadOnly(v: boolean): void {
    this.unreadOnly.set(v);
    this.pageIndex.set(0);
  }

  protected markRead(n: NotificationResponse): void {
    if (n.read) return;
    this.items.update((list) => list.map((x) => (x.id === n.id ? { ...x, read: true } : x)));
    this.api.markRead(n.id).subscribe({ next: () => this.unread.decrement(), error: () => this.list.reload() });
  }

  protected markAll(): void {
    this.api.markAllRead().subscribe(() => {
      this.unread.clear();
      this.list.reload();
    });
  }

  protected relative(v: string): string {
    return formatRelative(v);
  }

  protected dateTime(v: string): string {
    return formatDateTime(v);
  }
}
