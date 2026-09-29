import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideLockOpen } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCheckboxComponent } from '@/shared/components/checkbox';
import { injectDialogData, ZardDialogRef } from '@/shared/components/dialog';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSwitchComponent } from '@/shared/components/switch';

import { AdminApi } from '../../core/api/admin.api';
import { AuthStore } from '../../core/auth/auth.store';
import { fieldErrors, toProblem } from '../../core/http/problem';
import { type AdminUser, PASSWORD_RULE, type ProblemDetail, ROLE_LABELS, ROLES, type Role, type UpdateUserRequest } from '../../core/models';
import { Notifier } from '../../core/notify/notifier.service';
import { ART_UNIT_PATTERN } from '../applications/detail/workflow-forms';

export interface UserDialogData {
  /** Absent → create mode ("Create examiner"). */
  user?: AdminUser;
  onDone: (user: AdminUser) => void;
}

/** Only the fields that actually changed, so PATCH semantics are respected. */
export function buildUserPatch(user: AdminUser, next: { roles: Role[]; enabled: boolean; artUnit: string; unlock: boolean }): UpdateUserRequest {
  const patch: UpdateUserRequest = {};
  const sortedA = [...user.roles].sort().join(',');
  const sortedB = [...next.roles].sort().join(',');
  if (sortedA !== sortedB) patch.roles = [...next.roles];
  if (next.enabled !== user.enabled) patch.enabled = next.enabled;
  const au = next.artUnit.trim() || null;
  if (au !== (user.artUnit ?? null)) patch.artUnit = au;
  if (next.unlock) patch.unlock = true;
  return patch;
}

@Component({
  selector: 'app-user-dialog',
  imports: [FormsModule, NgIcon, ZardButtonComponent, ZardInputComponent, ZardCheckboxComponent, ZardSwitchComponent, ZardAlertComponent],
  viewProviders: [provideIcons({ lucideCircleAlert, lucideLockOpen })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="space-y-5" novalidate (submit)="$event.preventDefault(); save()">
      @if (problem(); as p) {
        <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="p.title ?? 'Could not save'" [zDescription]="p.detail ?? ''" />
      }

      @if (!user) {
        <div class="grid gap-4 sm:grid-cols-2">
          <div class="space-y-1.5 sm:col-span-2">
            <label for="u-email" class="field-label">Email</label>
            <input id="u-email" z-input type="email" autocomplete="off" [value]="email()" (input)="email.set($any($event.target).value)" [attr.aria-invalid]="!!err('email')" aria-describedby="u-email-msg" />
            @if (err('email'); as e) {
              <p id="u-email-msg" class="field-error">{{ e }}</p>
            }
          </div>
          <div class="space-y-1.5">
            <label for="u-name" class="field-label">Display name</label>
            <input id="u-name" z-input autocomplete="off" [value]="displayName()" (input)="displayName.set($any($event.target).value)" [attr.aria-invalid]="!!err('displayName')" aria-describedby="u-name-msg" />
            @if (err('displayName'); as e) {
              <p id="u-name-msg" class="field-error">{{ e }}</p>
            }
          </div>
          <div class="space-y-1.5">
            <label for="u-pw" class="field-label">Initial password</label>
            <input id="u-pw" z-input type="password" autocomplete="new-password" [value]="password()" (input)="password.set($any($event.target).value)" [attr.aria-invalid]="!!err('password')" aria-describedby="u-pw-msg" />
            <p id="u-pw-msg" [class]="err('password') ? 'field-error' : 'field-hint'">{{ err('password') ?? '10–128 chars, a letter and a digit.' }}</p>
          </div>
        </div>
      } @else {
        <div class="bg-muted/40 rounded-lg border p-3 text-sm">
          <p class="font-medium">{{ user.displayName }}</p>
          <p class="text-muted-foreground text-xs">{{ user.email }}</p>
        </div>
      }

      <fieldset class="space-y-2">
        <legend class="field-label">Roles</legend>
        <div class="flex flex-wrap gap-4">
          @for (r of allRoles; track r) {
            <z-checkbox [zId]="'u-role-' + r" [zDisabled]="r === 'ADMIN' && isSelf()" [ngModel]="roles().includes(r)" [ngModelOptions]="{ standalone: true }" (ngModelChange)="toggleRole(r, $event)">
              {{ roleLabel(r) }}
            </z-checkbox>
          }
        </div>
        @if (err('roles'); as e) {
          <p class="field-error">{{ e }}</p>
        } @else if (isSelf()) {
          <p class="field-hint">You cannot remove your own administrator role.</p>
        }
      </fieldset>

      <div class="space-y-1.5">
        <label for="u-au" class="field-label">Art unit</label>
        <input id="u-au" z-input class="w-40 font-mono" maxlength="4" inputmode="numeric" placeholder="2100" [value]="artUnit()" (input)="artUnit.set($any($event.target).value)" [attr.aria-invalid]="!!err('artUnit')" aria-describedby="u-au-msg" />
        <p id="u-au-msg" [class]="err('artUnit') ? 'field-error' : 'field-hint'">{{ err('artUnit') ?? 'Examiners only — 4 digits.' }}</p>
      </div>

      @if (user) {
        <div class="flex flex-wrap items-center gap-6">
          <z-switch zId="u-enabled" [zChecked]="enabled()" [zDisabled]="isSelf()" (zCheckedChange)="enabled.set($event)">Account enabled</z-switch>
          @if (user.locked) {
            <z-checkbox zId="u-unlock" (checkChange)="unlock.set($event)"><ng-icon name="lucideLockOpen" class="size-3.5" aria-hidden="true" /> Unlock (clear failed sign-ins)</z-checkbox>
          }
        </div>
      }

      <div class="flex justify-end gap-2 pt-2">
        <button type="button" z-button zType="outline" [zDisabled]="busy()" (click)="ref.close()">Cancel</button>
        <button type="submit" z-button [zLoading]="busy()" [zDisabled]="busy()">{{ user ? 'Save changes' : 'Create user' }}</button>
      </div>
    </form>
  `,
})
export class UserDialogComponent {
  private readonly data = injectDialogData<UserDialogData>();
  protected readonly ref = inject(ZardDialogRef);
  private readonly api = inject(AdminApi);
  private readonly notifier = inject(Notifier);
  private readonly auth = inject(AuthStore);

  protected readonly user = this.data.user;
  protected readonly allRoles = ROLES;
  protected readonly isSelf = computed(() => !!this.user && this.user.id === this.auth.user()?.id);

  protected readonly email = signal('');
  protected readonly displayName = signal('');
  protected readonly password = signal('');
  protected readonly roles = signal<Role[]>(this.user ? [...this.user.roles] : ['EXAMINER']);
  protected readonly artUnit = signal(this.user?.artUnit ?? '');
  protected readonly enabled = signal(this.user?.enabled ?? true);
  protected readonly unlock = signal(false);
  protected readonly attempted = signal(false);
  protected readonly busy = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);
  private readonly serverErrors = signal<Record<string, string>>({});

  private readonly errors = computed(() => {
    const e: Record<string, string> = {};
    if (!this.user) {
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(this.email().trim())) e['email'] = 'Enter a valid email address.';
      if (!this.displayName().trim()) e['displayName'] = 'Display name is required.';
      const pw = this.password();
      if (pw.length < PASSWORD_RULE.minLength || pw.length > PASSWORD_RULE.maxLength || !PASSWORD_RULE.pattern.test(pw)) {
        e['password'] = 'Password does not meet the policy.';
      }
    }
    if (!this.roles().length) e['roles'] = 'Select at least one role.';
    if (this.artUnit().trim() && !ART_UNIT_PATTERN.test(this.artUnit().trim())) e['artUnit'] = 'Art unit must be 4 digits.';
    return e;
  });

  protected err(field: string): string | null {
    return this.serverErrors()[field] ?? (this.attempted() ? (this.errors()[field] ?? null) : null);
  }

  protected roleLabel(r: Role): string {
    return ROLE_LABELS[r];
  }

  protected toggleRole(r: Role, checked: boolean): void {
    this.roles.update((list) => (checked ? [...new Set([...list, r])] : list.filter((x) => x !== r)));
  }

  protected save(): void {
    this.attempted.set(true);
    this.problem.set(null);
    this.serverErrors.set({});
    if (Object.keys(this.errors()).length) return;
    this.busy.set(true);
    const req$ = this.user
      ? this.api.updateUser(this.user.id, buildUserPatch(this.user, { roles: this.roles(), enabled: this.enabled(), artUnit: this.artUnit(), unlock: this.unlock() }))
      : this.api.createUser({
          email: this.email().trim(),
          displayName: this.displayName().trim(),
          password: this.password(),
          roles: this.roles(),
          artUnit: this.artUnit().trim() || null,
        });
    req$.subscribe({
      next: (saved) => {
        this.notifier.success(this.user ? 'User updated' : 'User created', { description: saved.email });
        this.data.onDone(saved);
        this.ref.close();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        const p = toProblem(err);
        this.serverErrors.set(fieldErrors(p));
        this.problem.set(p.status === 409 ? { ...p, title: 'Email already registered', detail: 'Choose a different email address.' } : p);
      },
    });
  }
}
