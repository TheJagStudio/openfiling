import { effect, inject, Injectable, Injector, signal } from '@angular/core';

import { environment } from '../../../environments/environment';
import { NotificationsApi } from '../api/notifications.api';
import { AuthStore } from '../auth/auth.store';

/** Unread notification count for the nav bell, polled every 30 s while signed in. */
@Injectable({ providedIn: 'root' })
export class UnreadStore {
  private readonly api = inject(NotificationsApi);
  private readonly auth = inject(AuthStore);
  private readonly injector = inject(Injector);
  private started = false;

  readonly count = signal(0);
  /** Bumped on every successful poll — lets open dropdowns know to refetch. */
  readonly version = signal(0);

  start(): void {
    if (this.started) return;
    this.started = true;
    effect(
      (onCleanup) => {
        if (!this.auth.isAuthenticated()) {
          this.count.set(0);
          return;
        }
        this.refresh();
        const timer = setInterval(() => this.refresh(), environment.unreadPollMs);
        onCleanup(() => clearInterval(timer));
      },
      { injector: this.injector },
    );
  }

  refresh(): void {
    if (!this.auth.isAuthenticated()) return;
    this.api.unreadCount().subscribe({
      next: (n) => {
        if (n !== this.count()) this.version.update((v) => v + 1);
        this.count.set(n);
      },
      error: () => undefined,
    });
  }

  decrement(): void {
    this.count.update((n) => Math.max(0, n - 1));
  }

  clear(): void {
    this.count.set(0);
  }
}
