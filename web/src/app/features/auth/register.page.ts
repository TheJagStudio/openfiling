import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCheck, lucideCircleAlert, lucideEye, lucideEyeOff, lucideX } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';
import { ZardInputComponent } from '@/shared/components/input';

import { AuthApi } from '../../core/api/auth.api';
import { AuthStore } from '../../core/auth/auth.store';
import { fieldErrors, toProblem } from '../../core/http/problem';
import { PASSWORD_RULE, passwordChecks, type ProblemDetail } from '../../core/models';
import { Notifier } from '../../core/notify/notifier.service';
import { AuthLayoutComponent } from './auth-layout.component';
import { safeReturnUrl } from './return-url';

type Field = 'displayName' | 'email' | 'password';

@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink, NgIcon, ZardButtonComponent, ZardInputComponent, ZardAlertComponent, AuthLayoutComponent, ...ZardCardImports],
  viewProviders: [provideIcons({ lucideCheck, lucideX, lucideCircleAlert, lucideEye, lucideEyeOff })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-auth-layout tagline="Create your free filer account — no fees, no payment details.">
      <z-card>
        <z-card-header>
          <z-card-title class="text-2xl"><h1>Create your account</h1></z-card-title>
          <z-card-description>New accounts are inventor accounts. Examiner access is granted by an administrator.</z-card-description>
        </z-card-header>
        <z-card-content>
          <form [formGroup]="form" class="space-y-4" novalidate (ngSubmit)="submit()">
            @if (problem(); as p) {
              <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="p.title ?? 'Registration failed'" [zDescription]="p.detail ?? ''" />
            }
            <div class="space-y-1.5">
              <label for="reg-name" class="field-label">Full name</label>
              <input
                id="reg-name"
                z-input
                autocomplete="name"
                formControlName="displayName"
                [attr.aria-invalid]="!!error('displayName')"
                [attr.aria-describedby]="error('displayName') ? 'reg-name-err' : null"
              />
              @if (error('displayName'); as e) {
                <p id="reg-name-err" class="field-error">{{ e }}</p>
              }
            </div>
            <div class="space-y-1.5">
              <label for="reg-email" class="field-label">Email address</label>
              <input
                id="reg-email"
                z-input
                type="email"
                autocomplete="email"
                formControlName="email"
                [attr.aria-invalid]="!!error('email')"
                [attr.aria-describedby]="error('email') ? 'reg-email-err' : null"
              />
              @if (error('email'); as e) {
                <p id="reg-email-err" class="field-error">{{ e }}</p>
              }
            </div>
            <div class="space-y-1.5">
              <label for="reg-password" class="field-label">Password</label>
              <div class="relative">
                <input
                  id="reg-password"
                  z-input
                  [type]="reveal() ? 'text' : 'password'"
                  autocomplete="new-password"
                  formControlName="password"
                  class="pr-10"
                  [attr.aria-invalid]="!!error('password')"
                  aria-describedby="reg-password-rules reg-password-err"
                />
                <button
                  type="button"
                  class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 absolute top-1/2 right-1 grid size-7 -translate-y-1/2 place-items-center rounded-md outline-none focus-visible:ring-2"
                  [attr.aria-label]="reveal() ? 'Hide password' : 'Show password'"
                  [attr.aria-pressed]="reveal()"
                  (click)="reveal.set(!reveal())"
                >
                  <ng-icon [name]="reveal() ? 'lucideEyeOff' : 'lucideEye'" aria-hidden="true" />
                </button>
              </div>
              <ul id="reg-password-rules" class="grid gap-0.5 text-xs sm:grid-cols-3" aria-live="polite">
                @for (r of rules(); track r.label) {
                  <li class="flex items-center gap-1.5" [class]="r.ok ? 'text-emerald-700 dark:text-emerald-400' : 'text-muted-foreground'">
                    <ng-icon [name]="r.ok ? 'lucideCheck' : 'lucideX'" class="size-3.5 shrink-0" aria-hidden="true" />
                    {{ r.label }}<span class="sr-only">{{ r.ok ? ' — met' : ' — not met' }}</span>
                  </li>
                }
              </ul>
              @if (error('password'); as e) {
                <p id="reg-password-err" class="field-error">{{ e }}</p>
              }
            </div>
            <button type="submit" z-button zSize="lg" class="w-full" [zLoading]="busy()" [zDisabled]="busy()">Create account</button>
            <p class="field-hint text-center">By creating an account you agree that documents you mark public may be published after redaction.</p>
          </form>
        </z-card-content>
        <z-card-footer class="justify-center text-sm">
          <span class="text-muted-foreground">Already registered?</span>
          <a routerLink="/login" [queryParams]="returnUrl() ? { returnUrl: returnUrl() } : {}" class="text-primary ml-1 font-medium hover:underline">Sign in</a>
        </z-card-footer>
      </z-card>
    </app-auth-layout>
  `,
})
export class RegisterPage {
  private readonly api = inject(AuthApi);
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly notifier = inject(Notifier);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly returnUrl = input<string>();

  /** Mirrors backend validation: password 10–128 chars with at least one letter and one digit. */
  protected readonly form = this.fb.group({
    displayName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: [
      '',
      [Validators.required, Validators.minLength(PASSWORD_RULE.minLength), Validators.maxLength(PASSWORD_RULE.maxLength), Validators.pattern(PASSWORD_RULE.pattern)],
    ],
  });
  protected readonly busy = signal(false);
  protected readonly reveal = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);
  protected readonly serverErrors = signal<Record<string, string>>({});
  private readonly submitted = signal(false);
  private readonly password = toSignal(this.form.controls.password.valueChanges, { initialValue: '' });
  protected readonly rules = computed(() => passwordChecks(this.password()));

  protected error(name: Field): string | null {
    const server = this.serverErrors()[name];
    if (server) return server;
    const c = this.form.controls[name];
    if (!c.invalid || !(c.touched || this.submitted())) return null;
    if (c.hasError('required')) return 'Required.';
    if (c.hasError('email')) return 'Enter a valid email address.';
    if (name === 'password') return 'Password does not meet the policy above.';
    if (c.hasError('maxlength')) return 'Too long.';
    return 'Invalid value.';
  }

  protected submit(): void {
    this.submitted.set(true);
    this.problem.set(null);
    this.serverErrors.set({});
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    const v = this.form.getRawValue();
    this.api.register({ email: v.email.trim(), password: v.password, displayName: v.displayName.trim() }).subscribe({
      next: (res) => {
        this.auth.signIn(res);
        this.notifier.success('Account created', { description: 'You are signed in. Start your first application from the dashboard.' });
        const target = safeReturnUrl(this.returnUrl());
        void this.router.navigateByUrl(target === '/' ? '/dashboard' : target);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        const p = toProblem(err);
        this.serverErrors.set(fieldErrors(p));
        this.problem.set(
          p.status === 409
            ? { ...p, title: 'Email already registered', detail: 'Sign in instead, or use a different email address.' }
            : p.status === 429
              ? { ...p, title: 'Too many requests', detail: 'Please wait a minute and try again.' }
              : p,
        );
      },
    });
  }
}
