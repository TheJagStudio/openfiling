import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { type AbstractControl, FormBuilder, ReactiveFormsModule, type ValidationErrors, Validators } from '@angular/forms';
import { Router } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCheck, lucideCircleAlert, lucideLaptop, lucideLogOut, lucideMonitorSmartphone, lucideSave, lucideX } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardAlertDialogService } from '@/shared/components/alert-dialog';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';

import { AccountApi } from '../../core/api/account.api';
import { AuthStore } from '../../core/auth/auth.store';
import { SessionService } from '../../core/auth/session.service';
import { describeUserAgent, formatDate, formatDateTime, formatRelative } from '../../core/format';
import { fieldErrors, toProblem } from '../../core/http/problem';
import { PASSWORD_RULE, passwordChecks, type ProblemDetail, ROLE_LABELS, type SessionResponse } from '../../core/models';
import { Notifier } from '../../core/notify/notifier.service';
import { ErrorStateComponent } from '../../ui/error-state.component';
import { PageHeaderComponent } from '../../ui/page-header.component';

function matchesNewPassword(group: AbstractControl): ValidationErrors | null {
  const a = group.get('newPassword')?.value;
  const b = group.get('confirm')?.value;
  return a && b && a !== b ? { mismatch: true } : null;
}

@Component({
  selector: 'app-account',
  imports: [
    ReactiveFormsModule,
    NgIcon,
    ZardButtonComponent,
    ZardInputComponent,
    ZardAlertComponent,
    ZardSkeletonComponent,
    ...ZardCardImports,
    PageHeaderComponent,
    ErrorStateComponent,
  ],
  viewProviders: [provideIcons({ lucideSave, lucideCheck, lucideX, lucideCircleAlert, lucideLogOut, lucideLaptop, lucideMonitorSmartphone })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-4xl space-y-6 px-4 py-8 sm:px-6">
      <app-page-header eyebrow="Account" heading="Account & security" description="Manage your profile, password and signed-in devices." />

      @if (auth.user(); as u) {
        <z-card>
          <z-card-header>
            <z-card-title><h2>Profile</h2></z-card-title>
            <z-card-description>Your name appears on filings, history entries and notifications.</z-card-description>
          </z-card-header>
          <z-card-content>
            <form [formGroup]="profile" novalidate class="grid gap-5 sm:grid-cols-2" (ngSubmit)="saveProfile()">
              <div class="space-y-1.5">
                <label for="acc-name" class="field-label">Display name</label>
                <input id="acc-name" z-input formControlName="displayName" autocomplete="name" [attr.aria-invalid]="!!profileError()" aria-describedby="acc-name-msg" />
                <p id="acc-name-msg" [class]="profileError() ? 'field-error' : 'field-hint'">{{ profileError() ?? 'Up to 100 characters.' }}</p>
              </div>
              <div class="space-y-1.5">
                <span class="field-label block">Email</span>
                <p class="bg-muted/50 rounded-md border px-3 py-1.5 text-sm">{{ u.email }}</p>
                <p class="field-hint">Contact an administrator to change your email.</p>
              </div>
              <dl class="text-muted-foreground grid grid-cols-2 gap-2 text-xs sm:col-span-2 sm:grid-cols-4">
                <div><dt>Roles</dt><dd class="text-foreground font-medium">{{ roles() }}</dd></div>
                <div><dt>Art unit</dt><dd class="text-foreground font-mono">{{ u.artUnit ?? '—' }}</dd></div>
                <div><dt>Member since</dt><dd class="text-foreground">{{ date(u.createdAt) }}</dd></div>
                <div><dt>Last sign-in</dt><dd class="text-foreground">{{ dateTime(u.lastLoginAt) }}</dd></div>
              </dl>
              <div class="flex justify-end sm:col-span-2">
                <button type="submit" z-button [zLoading]="savingProfile()" [zDisabled]="savingProfile() || profile.pristine"><ng-icon name="lucideSave" aria-hidden="true" /> Save profile</button>
              </div>
            </form>
          </z-card-content>
        </z-card>
      }

      <z-card>
        <z-card-header>
          <z-card-title><h2>Change password</h2></z-card-title>
          <z-card-description>Changing your password signs you out of every device, including this one.</z-card-description>
        </z-card-header>
        <z-card-content>
          <form [formGroup]="password" novalidate class="space-y-4" (ngSubmit)="changePassword()">
            @if (pwProblem(); as p) {
              <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="p.title ?? 'Password not changed'" [zDescription]="p.detail ?? ''" />
            }
            <div class="grid gap-4 sm:grid-cols-3">
              <div class="space-y-1.5">
                <label for="pw-current" class="field-label">Current password</label>
                <input id="pw-current" z-input type="password" autocomplete="current-password" formControlName="currentPassword" [attr.aria-invalid]="pwAttempted() && password.controls.currentPassword.invalid" />
              </div>
              <div class="space-y-1.5">
                <label for="pw-new" class="field-label">New password</label>
                <input id="pw-new" z-input type="password" autocomplete="new-password" formControlName="newPassword" aria-describedby="pw-rules" [attr.aria-invalid]="pwAttempted() && password.controls.newPassword.invalid" />
              </div>
              <div class="space-y-1.5">
                <label for="pw-confirm" class="field-label">Confirm new password</label>
                <input id="pw-confirm" z-input type="password" autocomplete="new-password" formControlName="confirm" aria-describedby="pw-confirm-msg" [attr.aria-invalid]="pwAttempted() && (password.controls.confirm.invalid || password.hasError('mismatch'))" />
              </div>
            </div>
            <ul id="pw-rules" class="flex flex-wrap gap-x-4 gap-y-1 text-xs" aria-live="polite">
              @for (r of rules(); track r.label) {
                <li class="flex items-center gap-1.5" [class]="r.ok ? 'text-emerald-700 dark:text-emerald-400' : 'text-muted-foreground'">
                  <ng-icon [name]="r.ok ? 'lucideCheck' : 'lucideX'" class="size-3.5" aria-hidden="true" /> {{ r.label }}<span class="sr-only">{{ r.ok ? ' — met' : ' — not met' }}</span>
                </li>
              }
            </ul>
            @if (pwAttempted() && password.hasError('mismatch')) {
              <p id="pw-confirm-msg" class="field-error">The new passwords do not match.</p>
            } @else if (pwAttempted() && password.invalid) {
              <p id="pw-confirm-msg" class="field-error">Fill in all fields; the new password must meet the policy.</p>
            }
            <div class="flex justify-end">
              <button type="submit" z-button [zLoading]="changingPw()" [zDisabled]="changingPw()">Change password</button>
            </div>
          </form>
        </z-card-content>
      </z-card>

      <z-card>
        <z-card-header>
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div class="space-y-1.5">
              <z-card-title><h2>Active sessions</h2></z-card-title>
              <z-card-description>Devices signed in to your account (one per sign-in; refreshed automatically).</z-card-description>
            </div>
            <button type="button" z-button zType="destructive" zSize="sm" [zDisabled]="!sessions.hasValue() || !sessions.value().length" (click)="signOutEverywhere()">
              <ng-icon name="lucideLogOut" aria-hidden="true" /> Sign out everywhere
            </button>
          </div>
        </z-card-header>
        <z-card-content>
          @if (sessions.isLoading() && !sessions.hasValue()) {
            <div class="space-y-2">@for (i of [1, 2]; track i) {<z-skeleton class="h-14 w-full" />}</div>
          } @else if (sessions.error()) {
            <app-error-state [error]="sessions.error()" (retry)="sessions.reload()" />
          } @else if (sessions.hasValue()) {
            <ul class="divide-y">
              @for (s of sessions.value(); track s.id) {
                <li class="flex flex-wrap items-center gap-3 py-3">
                  <span class="bg-muted grid size-9 place-items-center rounded-lg"><ng-icon name="lucideMonitorSmartphone" class="size-4" aria-hidden="true" /></span>
                  <div class="min-w-0 flex-1">
                    <p class="text-sm font-medium">{{ device(s.userAgent) }}</p>
                    <p class="text-muted-foreground text-xs">
                      {{ s.ip ?? 'Unknown IP' }} · signed in {{ date(s.createdAt) }} · last active {{ relative(s.lastUsedAt ?? s.createdAt) }} · expires {{ date(s.expiresAt) }}
                    </p>
                  </div>
                  <button type="button" z-button zType="outline" zSize="sm" [zLoading]="revoking() === s.id" (click)="revoke(s)">Revoke</button>
                </li>
              } @empty {
                <li class="text-muted-foreground py-4 text-sm">No active sessions.</li>
              }
            </ul>
          }
        </z-card-content>
      </z-card>
    </div>
  `,
})
export class AccountPage {
  private readonly api = inject(AccountApi);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly notifier = inject(Notifier);
  private readonly confirm = inject(ZardAlertDialogService);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly auth = inject(AuthStore);

  protected readonly profile = this.fb.group({
    displayName: [this.auth.user()?.displayName ?? '', [Validators.required, Validators.maxLength(100)]],
  });
  protected readonly password = this.fb.group(
    {
      currentPassword: ['', Validators.required],
      newPassword: ['', [Validators.required, Validators.minLength(PASSWORD_RULE.minLength), Validators.maxLength(PASSWORD_RULE.maxLength), Validators.pattern(PASSWORD_RULE.pattern)]],
      confirm: ['', Validators.required],
    },
    { validators: matchesNewPassword },
  );

  protected readonly savingProfile = signal(false);
  protected readonly profileServerError = signal<string | null>(null);
  protected readonly changingPw = signal(false);
  protected readonly pwAttempted = signal(false);
  protected readonly pwProblem = signal<ProblemDetail | null>(null);
  protected readonly revoking = signal<string | null>(null);

  private readonly newPw = toSignal(this.password.controls.newPassword.valueChanges, { initialValue: '' });
  protected readonly rules = computed(() => passwordChecks(this.newPw()));
  protected readonly roles = computed(() => (this.auth.user()?.roles ?? []).map((r) => ROLE_LABELS[r] ?? r).join(', '));

  protected readonly sessions = rxResource({ stream: () => this.api.sessions() });

  protected profileError(): string | null {
    if (this.profileServerError()) return this.profileServerError();
    const c = this.profile.controls.displayName;
    if (!c.invalid || !c.touched) return null;
    return c.hasError('required') ? 'Display name is required.' : 'At most 100 characters.';
  }

  protected saveProfile(): void {
    this.profileServerError.set(null);
    if (this.profile.invalid) {
      this.profile.markAllAsTouched();
      return;
    }
    this.savingProfile.set(true);
    this.api.updateProfile({ displayName: this.profile.getRawValue().displayName.trim() }).subscribe({
      next: (user) => {
        this.savingProfile.set(false);
        this.auth.updateUser(user);
        this.profile.markAsPristine();
        this.notifier.success('Profile updated');
      },
      error: (err: unknown) => {
        this.savingProfile.set(false);
        const p = toProblem(err);
        this.profileServerError.set(fieldErrors(p)['displayName'] ?? p.detail ?? 'Could not save the profile.');
      },
    });
  }

  protected changePassword(): void {
    this.pwAttempted.set(true);
    this.pwProblem.set(null);
    if (this.password.invalid) {
      this.password.markAllAsTouched();
      return;
    }
    this.changingPw.set(true);
    const v = this.password.getRawValue();
    this.api.changePassword({ currentPassword: v.currentPassword, newPassword: v.newPassword }).subscribe({
      next: () => {
        this.changingPw.set(false);
        this.notifier.success('Password changed', { description: 'All sessions were signed out. Please sign in with your new password.' });
        this.session.logout();
        void this.router.navigate(['/login'], { queryParams: { returnUrl: '/account' } });
      },
      error: (err: unknown) => {
        this.changingPw.set(false);
        const p = toProblem(err);
        const fields = Object.values(fieldErrors(p));
        this.pwProblem.set(
          p.status === 400 || p.status === 401 || p.status === 422
            ? { ...p, title: 'Password not changed', detail: fields[0] ?? p.detail ?? 'Check your current password and the password policy.' }
            : p,
        );
      },
    });
  }

  protected revoke(s: SessionResponse): void {
    this.revoking.set(s.id);
    this.api.revokeSession(s.id).subscribe({
      next: () => {
        this.revoking.set(null);
        this.notifier.success('Session revoked', { description: this.device(s.userAgent) });
        this.sessions.reload();
      },
      error: () => this.revoking.set(null),
    });
  }

  protected signOutEverywhere(): void {
    this.confirm.create({
      zTitle: 'Sign out everywhere?',
      zDescription: 'Every device — including this one — will be signed out immediately.',
      zOkText: 'Sign out everywhere',
      zOkDestructive: true,
      zCancelText: 'Cancel',
      zOnOk: () => {
        this.api.revokeAll().subscribe(() => {
          this.session.logout();
          this.notifier.info('Signed out of all devices');
          void this.router.navigateByUrl('/login');
        });
      },
    });
  }

  protected device(ua: string | null): string {
    return describeUserAgent(ua);
  }

  protected date(v: string | null): string {
    return formatDate(v);
  }

  protected dateTime(v: string | null): string {
    return formatDateTime(v);
  }

  protected relative(v: string | null): string {
    return formatRelative(v);
  }
}
