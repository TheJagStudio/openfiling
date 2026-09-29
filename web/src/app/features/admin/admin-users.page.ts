import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideLock, lucidePencil, lucideSearch, lucideUserPlus, lucideUsers } from '@ng-icons/lucide';

import { ZardAvatarComponent } from '@/shared/components/avatar';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardDialogService } from '@/shared/components/dialog';
import { ZardEmptyComponent } from '@/shared/components/empty';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSelectImports } from '@/shared/components/select';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardTableImports } from '@/shared/components/table';

import { AdminApi } from '../../core/api/admin.api';
import { formatDate, formatRelative, initialsOf } from '../../core/format';
import { type AdminUser, ROLE_LABELS, ROLES, type Role } from '../../core/models';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { PagerComponent } from '../../ui/pager.component';
import { AdminNavComponent } from './admin-nav.component';
import { UserDialogComponent, type UserDialogData } from './user-dialog.component';

@Component({
  selector: 'app-admin-users',
  imports: [
    NgIcon,
    ZardButtonComponent,
    ZardInputComponent,
    ZardAvatarComponent,
    ZardSkeletonComponent,
    ZardEmptyComponent,
    ...ZardSelectImports,
    ...ZardTableImports,
    PageHeaderComponent,
    PagerComponent,
    ErrorStateComponent,
    AdminNavComponent,
  ],
  viewProviders: [provideIcons({ lucideUserPlus, lucidePencil, lucideSearch, lucideLock, lucideUsers })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-7xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header eyebrow="Administration" heading="Users" description="Accounts, roles, examiner art units and lockouts. New sign-ups are always inventors.">
        <button actions type="button" z-button zSize="lg" (click)="create()"><ng-icon name="lucideUserPlus" aria-hidden="true" /> Create examiner</button>
      </app-page-header>
      <app-admin-nav />

      <form role="search" class="flex flex-col gap-3 sm:flex-row" (submit)="$event.preventDefault(); applySearch()">
        <div class="relative flex-1">
          <label for="users-q" class="sr-only">Search users by name or email</label>
          <ng-icon name="lucideSearch" class="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" aria-hidden="true" />
          <input id="users-q" z-input type="search" class="pl-9" placeholder="Name or email" [value]="qDraft()" (input)="onQuery($any($event.target).value)" />
        </div>
        <z-select class="sm:w-48" zAriaLabel="Filter by role" zPlaceholder="All roles" [zValue]="role()" (zSelectionChange)="setRole($event)">
          <z-select-item zValue="">All roles</z-select-item>
          @for (r of roles; track r) {
            <z-select-item [zValue]="r">{{ roleLabel(r) }}</z-select-item>
          }
        </z-select>
      </form>

      @if (users.isLoading() && !users.hasValue()) {
        <div class="space-y-2">@for (i of [1, 2, 3, 4, 5]; track i) {<z-skeleton class="h-14 w-full" />}</div>
      } @else if (users.error()) {
        <app-error-state [error]="users.error()" (retry)="users.reload()" />
      } @else if (users.hasValue()) {
        @let page = users.value();
        @if (!page.content.length) {
          <z-empty class="rounded-xl border border-dashed py-12" zIcon="lucideUsers" zTitle="No users found" zDescription="Try a different search." />
        } @else {
          <div class="bg-card relative overflow-x-auto rounded-xl border">
            <table z-table class="min-w-[56rem]">
              <caption class="sr-only">Users</caption>
              <thead z-table-header>
                <tr z-table-row>
                  <th z-table-head scope="col">User</th>
                  <th z-table-head scope="col">Roles</th>
                  <th z-table-head scope="col">Art unit</th>
                  <th z-table-head scope="col">Status</th>
                  <th z-table-head scope="col" class="text-right">Applications</th>
                  <th z-table-head scope="col">Last sign-in</th>
                  <th z-table-head scope="col">Joined</th>
                  <th z-table-head scope="col" class="text-right"><span class="sr-only">Edit</span></th>
                </tr>
              </thead>
              <tbody z-table-body>
                @for (u of page.content; track u.id) {
                  <tr z-table-row>
                    <td z-table-cell>
                      <div class="flex items-center gap-3">
                        <z-avatar zSize="sm" [zFallback]="initials(u.displayName || u.email)" [zAlt]="u.displayName" />
                        <div class="min-w-0">
                          <div class="truncate font-medium">{{ u.displayName }}</div>
                          <div class="text-muted-foreground truncate text-xs">{{ u.email }}</div>
                        </div>
                      </div>
                    </td>
                    <td z-table-cell>
                      <div class="flex flex-wrap gap-1">
                        @for (r of u.roles; track r) {
                          <span class="rounded px-1.5 py-0.5 text-[10.5px] font-semibold tracking-wide uppercase" [class]="roleClass(r)">{{ roleLabel(r) }}</span>
                        }
                      </div>
                    </td>
                    <td z-table-cell class="font-mono text-sm">{{ u.artUnit ?? '—' }}</td>
                    <td z-table-cell>
                      @if (!u.enabled) {
                        <span class="text-muted-foreground text-xs font-medium">Disabled</span>
                      } @else if (u.locked) {
                        <span class="inline-flex items-center gap-1 text-xs font-medium text-amber-700 dark:text-amber-400"><ng-icon name="lucideLock" class="size-3.5" aria-hidden="true" /> Locked</span>
                      } @else {
                        <span class="text-xs font-medium text-emerald-700 dark:text-emerald-400">Active</span>
                      }
                    </td>
                    <td z-table-cell class="tabular text-right text-sm">{{ u.applicationCount }}</td>
                    <td z-table-cell class="text-muted-foreground text-xs">{{ u.lastLoginAt ? relative(u.lastLoginAt) : 'Never' }}</td>
                    <td z-table-cell class="text-muted-foreground text-xs">{{ date(u.createdAt) }}</td>
                    <td z-table-cell class="text-right">
                      <button type="button" z-button zType="ghost" zSize="sm" [attr.aria-label]="'Edit ' + u.email" (click)="edit(u)"><ng-icon name="lucidePencil" aria-hidden="true" /> Edit</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <app-pager [page]="page.page" [totalPages]="page.totalPages" [size]="page.size" [totalElements]="page.totalElements" ariaLabel="User pages" (pageChange)="pageIndex.set($event)" />
        }
      }
    </div>
  `,
})
export class AdminUsersPage {
  private readonly api = inject(AdminApi);
  private readonly dialog = inject(ZardDialogService);

  protected readonly roles = ROLES;
  protected readonly q = signal('');
  protected readonly qDraft = signal('');
  protected readonly role = signal<Role | ''>('');
  protected readonly pageIndex = signal(0);

  protected readonly users = rxResource({
    params: () => ({ q: this.q(), role: this.role() || undefined, page: this.pageIndex(), size: 20 }),
    stream: ({ params }) => this.api.users(params),
  });

  private debounce: ReturnType<typeof setTimeout> | null = null;

  protected onQuery(v: string): void {
    this.qDraft.set(v);
    if (this.debounce) clearTimeout(this.debounce);
    this.debounce = setTimeout(() => this.applySearch(), 350);
  }

  protected applySearch(): void {
    if (this.debounce) clearTimeout(this.debounce);
    this.q.set(this.qDraft().trim());
    this.pageIndex.set(0);
  }

  protected setRole(v: string | string[]): void {
    this.role.set(((Array.isArray(v) ? v[0] : v) ?? '') as Role | '');
    this.pageIndex.set(0);
  }

  protected create(): void {
    this.dialog.create<UserDialogComponent, UserDialogData>({
      zTitle: 'Create examiner account',
      zDescription: 'The user can sign in immediately with the initial password.',
      zContent: UserDialogComponent,
      zData: { onDone: () => this.users.reload() },
      zHideFooter: true,
      zCustomClasses: 'sm:max-w-lg',
    });
  }

  protected edit(user: AdminUser): void {
    this.dialog.create<UserDialogComponent, UserDialogData>({
      zTitle: 'Edit user',
      zDescription: 'Change roles, art unit, enablement or clear a lockout.',
      zContent: UserDialogComponent,
      zData: { user, onDone: () => this.users.reload() },
      zHideFooter: true,
      zCustomClasses: 'sm:max-w-lg',
    });
  }

  protected roleLabel(r: Role): string {
    return ROLE_LABELS[r] ?? r;
  }

  protected roleClass(r: Role): string {
    return r === 'ADMIN' ? 'bg-primary text-primary-foreground' : r === 'EXAMINER' ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground';
  }

  protected initials(s: string): string {
    return initialsOf(s);
  }

  protected date(v: string): string {
    return formatDate(v);
  }

  protected relative(v: string): string {
    return formatRelative(v);
  }
}
