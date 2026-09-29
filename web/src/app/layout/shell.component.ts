import { ChangeDetectionStrategy, Component, computed, inject, type TemplateRef, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideActivity,
  lucideBell,
  lucideChevronDown,
  lucideFolderOpen,
  lucideHouse,
  lucideInbox,
  lucideLayoutDashboard,
  lucideLogIn,
  lucideLogOut,
  lucideMenu,
  lucideScanSearch,
  lucideScrollText,
  lucideSearch,
  lucideUserCog,
  lucideUserPlus,
  lucideUsers,
} from '@ng-icons/lucide';
import { filter, map } from 'rxjs';

import { ZardAvatarComponent } from '@/shared/components/avatar';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardDropdownImports } from '@/shared/components/dropdown';
import { ZardSheetService } from '@/shared/components/sheet';
import { ZardSonnerComponent } from '@/shared/components/sonner';

import { AuthStore } from '../core/auth/auth.store';
import { SessionService } from '../core/auth/session.service';
import { ROLE_LABELS, type Role } from '../core/models';
import { UnreadStore } from '../core/notifications/unread.store';
import { Notifier } from '../core/notify/notifier.service';
import { BrandMarkComponent } from '../ui/brand-mark.component';
import { ADMIN_NAV, EXAMINER_NAV, MEMBER_NAV, type NavItem, PUBLIC_NAV } from './nav';
import { NotificationBellComponent } from './notification-bell.component';
import { ThemeToggleComponent } from './theme-toggle.component';

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    NgIcon,
    ZardButtonComponent,
    ZardAvatarComponent,
    ZardSonnerComponent,
    ThemeToggleComponent,
    NotificationBellComponent,
    BrandMarkComponent,
    ...ZardDropdownImports,
  ],
  viewProviders: [
    provideIcons({
      lucideMenu,
      lucideHouse,
      lucideSearch,
      lucideScanSearch,
      lucideLayoutDashboard,
      lucideFolderOpen,
      lucideInbox,
      lucideUsers,
      lucideScrollText,
      lucideActivity,
      lucideChevronDown,
      lucideBell,
      lucideUserCog,
      lucideLogIn,
      lucideLogOut,
      lucideUserPlus,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.component.html',
  host: { class: 'flex min-h-dvh flex-col' },
})
export class ShellComponent {
  protected readonly auth = inject(AuthStore);
  protected readonly unread = inject(UnreadStore);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly sheet = inject(ZardSheetService);
  private readonly notifier = inject(Notifier);
  protected readonly adminNav = ADMIN_NAV;
  protected readonly publicShortcuts = PUBLIC_NAV.slice(1);
  protected readonly year = new Date().getFullYear();
  private readonly mobileNav = viewChild.required<TemplateRef<unknown>>('mobileNav');

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  protected readonly isAdminRoute = computed(() => this.url().startsWith('/admin'));

  /** Public items for guests; member items (+ examiner queue) once signed in. */
  protected readonly navItems = computed<readonly NavItem[]>(() => {
    if (!this.auth.isAuthenticated()) return PUBLIC_NAV;
    const items: NavItem[] = [...MEMBER_NAV];
    if (this.auth.isExaminer()) items.push(EXAMINER_NAV);
    items.push(PUBLIC_NAV[1]!, PUBLIC_NAV[2]!);
    return items;
  });

  constructor() {
    this.session.start();
    this.unread.start();
  }

  protected roleLabel(r: Role): string {
    return ROLE_LABELS[r] ?? r;
  }

  protected focusMain(event: Event): void {
    event.preventDefault();
    const main = document.getElementById('main');
    main?.focus();
    main?.scrollIntoView();
  }

  protected openMobileNav(): void {
    this.sheet.create({
      zContent: this.mobileNav(),
      zSide: 'left',
      zTitle: 'OpenFiling',
      zDescription: 'Patent application filing & status',
      zHideFooter: true,
      zCustomClasses: 'w-72 max-w-[85vw]',
    });
  }

  protected go(path: string): void {
    void this.router.navigateByUrl(path);
  }

  protected logout(): void {
    this.session.logout();
    this.notifier.info('Signed out', { description: 'Your session was revoked on this device.' });
    void this.router.navigateByUrl('/');
  }

  protected loginQuery(): Record<string, string> {
    const url = this.router.url;
    return url.startsWith('/login') || url.startsWith('/register') || url === '/' ? {} : { returnUrl: url };
  }
}
