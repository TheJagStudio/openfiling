import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideFileLock2, lucideScanEye, lucideShieldCheck } from '@ng-icons/lucide';

import { BrandMarkComponent } from '../../ui/brand-mark.component';

/** Two-column frame for sign-in / sign-up: form on the right, trust points on a navy panel. */
@Component({
  selector: 'app-auth-layout',
  imports: [NgIcon, BrandMarkComponent],
  viewProviders: [provideIcons({ lucideFileLock2, lucideShieldCheck, lucideScanEye })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto grid max-w-6xl gap-10 px-4 py-10 sm:px-6 lg:grid-cols-2 lg:py-16">
      <aside class="bg-navy text-navy-foreground relative hidden overflow-hidden rounded-2xl p-10 lg:flex lg:flex-col" aria-label="About OpenFiling">
        <div class="paper-grid pointer-events-none absolute inset-0 opacity-30" aria-hidden="true"></div>
        <div class="relative flex items-center gap-3">
          <app-brand-mark [size]="36" />
          <span class="text-lg font-bold tracking-tight">OpenFiling</span>
        </div>
        <h2 class="relative mt-10 text-3xl leading-tight font-semibold text-balance">{{ tagline() }}</h2>
        <ul class="relative mt-8 space-y-5 text-sm">
          <li class="flex gap-3">
            <ng-icon name="lucideFileLock2" class="text-seal mt-0.5 size-5 shrink-0" aria-hidden="true" />
            <span><strong class="font-semibold">Direct-to-storage filing.</strong> Your PDFs travel straight to encrypted object storage over short-lived signed links.</span>
          </li>
          <li class="flex gap-3">
            <ng-icon name="lucideShieldCheck" class="text-seal mt-0.5 size-5 shrink-0" aria-hidden="true" />
            <span><strong class="font-semibold">Confidential until published.</strong> Only you and the assigned examiner can see an unpublished application.</span>
          </li>
          <li class="flex gap-3">
            <ng-icon name="lucideScanEye" class="text-seal mt-0.5 size-5 shrink-0" aria-hidden="true" />
            <span><strong class="font-semibold">Automatic redaction.</strong> Public copies are scrubbed of personal and controlled information before release.</span>
          </li>
        </ul>
        <p class="relative mt-auto pt-10 text-xs opacity-70">Demo accounts (dev profile): inventor&#64;openfiling.local, examiner&#64;openfiling.local, admin&#64;openfiling.local.</p>
      </aside>
      <div class="flex items-center justify-center">
        <div class="w-full max-w-md"><ng-content /></div>
      </div>
    </div>
  `,
})
export class AuthLayoutComponent {
  readonly tagline = input('File, track and publish patent applications — securely.');
}
