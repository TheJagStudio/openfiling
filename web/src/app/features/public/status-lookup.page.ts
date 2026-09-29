import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideLock, lucideScanSearch, lucideSearch } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';
import { ZardInputComponent } from '@/shared/components/input';

import { APPLICATION_NUMBER_HINT, parseApplicationNumber } from '../../core/application-number';
import { PageHeaderComponent } from '../../ui/page-header.component';

/** Public status lookup by application number (`19/000,123` or `19000123`). */
@Component({
  selector: 'app-status-lookup',
  imports: [RouterLink, NgIcon, ZardButtonComponent, ZardInputComponent, ...ZardCardImports, PageHeaderComponent],
  viewProviders: [provideIcons({ lucideScanSearch, lucideLock, lucideSearch })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-3xl space-y-8 px-4 py-10 sm:px-6">
      <app-page-header
        eyebrow="Status lookup"
        heading="Check an application’s status"
        description="Enter an application number to see its current status, prosecution history and sanitized public documents."
      />

      <z-card>
        <z-card-content class="pt-6">
          <form novalidate class="space-y-3" (submit)="$event.preventDefault(); submit()">
            <label for="lookup-number" class="field-label">Application number</label>
            <div class="flex flex-col gap-2 sm:flex-row">
              <div class="relative flex-1">
                <ng-icon name="lucideScanSearch" class="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2" aria-hidden="true" />
                <input
                  id="lookup-number"
                  z-input
                  class="h-12 pl-10 font-mono text-lg tracking-wide"
                  inputmode="numeric"
                  autocomplete="off"
                  placeholder="19/000,123"
                  [value]="value()"
                  (input)="value.set($any($event.target).value); error.set(null)"
                  [attr.aria-invalid]="!!error()"
                  aria-describedby="lookup-number-msg"
                />
              </div>
              <button type="submit" z-button zSize="lg" class="h-12 px-6"><ng-icon name="lucideSearch" aria-hidden="true" /> Look up</button>
            </div>
            <p id="lookup-number-msg" [class]="error() ? 'field-error' : 'field-hint'">{{ error() ?? hint }}</p>
          </form>
        </z-card-content>
      </z-card>

      <div class="bg-muted/40 flex gap-3 rounded-xl border p-5 text-sm">
        <ng-icon name="lucideLock" class="text-primary mt-0.5 size-5 shrink-0" aria-hidden="true" />
        <div class="space-y-1">
          <p class="font-medium">Unpublished applications are confidential</p>
          <p class="text-muted-foreground">
            Status is public only once an application has been published (or a patent issued). If you are the applicant,
            <a routerLink="/login" class="text-primary font-medium hover:underline">sign in</a> to see the full record of your own applications.
          </p>
        </div>
      </div>
    </div>
  `,
})
export class StatusLookupPage {
  private readonly router = inject(Router);
  protected readonly hint = `Accepted formats: ${APPLICATION_NUMBER_HINT}.`;
  protected readonly value = signal('');
  protected readonly error = signal<string | null>(null);

  protected submit(): void {
    const n = parseApplicationNumber(this.value());
    if (!n) {
      this.error.set(`That doesn’t look like an application number. Use 8 digits, ${APPLICATION_NUMBER_HINT}.`);
      setTimeout(() => document.getElementById('lookup-number')?.focus());
      return;
    }
    void this.router.navigate(['/status', n]);
  }
}
