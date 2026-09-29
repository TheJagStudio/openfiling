import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideEye, lucideEyeOff, lucideTimer } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';
import { ZardInputComponent } from '@/shared/components/input';

import { AuthApi } from '../../core/api/auth.api';
import { AuthStore } from '../../core/auth/auth.store';
import { retryAfterSeconds, toProblem } from '../../core/http/problem';
import type { ProblemDetail } from '../../core/models';
import { Notifier } from '../../core/notify/notifier.service';
import { AuthLayoutComponent } from './auth-layout.component';
import { safeReturnUrl } from './return-url';

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, RouterLink, NgIcon, ZardButtonComponent, ZardInputComponent, ZardAlertComponent, AuthLayoutComponent, ...ZardCardImports],
  viewProviders: [provideIcons({ lucideEye, lucideEyeOff, lucideCircleAlert, lucideTimer })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-auth-layout>
      <z-card>
        <z-card-header>
          <z-card-title class="text-2xl"><h1>Sign in</h1></z-card-title>
          <z-card-description>Access your applications, documents and examiner queue.</z-card-description>
        </z-card-header>
        <z-card-content>
          <form [formGroup]="form" class="space-y-4" novalidate (ngSubmit)="submit()">
            @if (problem(); as p) {
              <z-alert
                zType="destructive"
                [zIcon]="locked() ? 'lucideTimer' : 'lucideCircleAlert'"
                zRole="alert"
                [zTitle]="p.title ?? 'Sign-in failed'"
                [zDescription]="p.detail ?? ''"
              />
            }
            <div class="space-y-1.5">
              <label for="login-email" class="field-label">Email address</label>
              <input
                id="login-email"
                z-input
                type="email"
                autocomplete="username"
                formControlName="email"
                [attr.aria-invalid]="showError('email')"
                [attr.aria-describedby]="showError('email') ? 'login-email-err' : null"
              />
              @if (showError('email')) {
                <p id="login-email-err" class="field-error">Enter a valid email address.</p>
              }
            </div>
            <div class="space-y-1.5">
              <label for="login-password" class="field-label">Password</label>
              <div class="relative">
                <input
                  id="login-password"
                  z-input
                  [type]="reveal() ? 'text' : 'password'"
                  autocomplete="current-password"
                  formControlName="password"
                  class="pr-10"
                  [attr.aria-invalid]="showError('password')"
                  [attr.aria-describedby]="showError('password') ? 'login-password-err' : null"
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
              @if (showError('password')) {
                <p id="login-password-err" class="field-error">Password is required.</p>
              }
            </div>
            <button type="submit" z-button class="w-full" zSize="lg" [zLoading]="busy()" [zDisabled]="busy()">Sign in</button>
          </form>
        </z-card-content>
        <z-card-footer class="justify-center text-sm">
          <span class="text-muted-foreground">New to OpenFiling?</span>
          <a routerLink="/register" [queryParams]="returnUrl() ? { returnUrl: returnUrl() } : {}" class="text-primary ml-1 font-medium hover:underline"
            >Create a free account</a
          >
        </z-card-footer>
      </z-card>
    </app-auth-layout>
  `,
})
export class LoginPage {
  private readonly api = inject(AuthApi);
  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  private readonly notifier = inject(Notifier);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly returnUrl = input<string>();

  protected readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  protected readonly busy = signal(false);
  protected readonly reveal = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);
  protected readonly locked = signal(false);
  private readonly submitted = signal(false);
  protected readonly target = computed(() => safeReturnUrl(this.returnUrl()));

  protected showError(name: 'email' | 'password'): boolean {
    const c = this.form.controls[name];
    return c.invalid && (c.touched || this.submitted());
  }

  protected submit(): void {
    this.submitted.set(true);
    this.problem.set(null);
    this.locked.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.busy.set(true);
    const v = this.form.getRawValue();
    this.api.login({ email: v.email.trim(), password: v.password }).subscribe({
      next: (res) => {
        this.auth.signIn(res);
        this.notifier.success(`Welcome back, ${res.user.displayName}`);
        const target = this.target();
        void this.router.navigateByUrl(target === '/' ? '/dashboard' : target);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.problem.set(describeLoginError(err));
        this.locked.set(toProblem(err).status === 429);
      },
    });
  }
}

/** Friendly problem for login failures: 401 bad credentials, 429 lockout / rate limit (with Retry-After). */
export function describeLoginError(err: unknown): ProblemDetail {
  const p = toProblem(err);
  if (p.status === 401) {
    return { ...p, title: 'Incorrect email or password', detail: 'Check your details and try again. After 5 failed attempts the account is locked for 15 minutes.' };
  }
  if (p.status === 429) {
    const headers = (err as { headers?: { get(name: string): string | null } } | null)?.headers;
    const seconds = retryAfterSeconds(headers?.get?.('Retry-After') ?? null);
    const minutes = seconds !== null ? Math.max(1, Math.ceil(seconds / 60)) : null;
    return {
      ...p,
      title: 'Account temporarily locked',
      detail:
        minutes !== null
          ? `Too many sign-in attempts. Try again in ${minutes} minute${minutes === 1 ? '' : 's'}.`
          : (p.detail ?? 'Too many sign-in attempts. Try again in 15 minutes.'),
    };
  }
  return p;
}
