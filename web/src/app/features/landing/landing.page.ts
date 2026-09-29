import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowRight,
  lucideBadgeCheck,
  lucideCloudUpload,
  lucideFileLock2,
  lucideFilePen,
  lucideGavel,
  lucideListChecks,
  lucideScanEye,
  lucideScanSearch,
  lucideSearch,
  lucideSend,
  lucideShieldCheck,
} from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';

import { PublicApi } from '../../core/api/public.api';
import { parseApplicationNumber } from '../../core/application-number';
import { AuthStore } from '../../core/auth/auth.store';
import { formatNumber } from '../../core/format';

@Component({
  selector: 'app-landing',
  imports: [RouterLink, NgIcon, ZardButtonComponent, ZardInputComponent, ZardSkeletonComponent],
  viewProviders: [
    provideIcons({
      lucideArrowRight,
      lucideCloudUpload,
      lucideFileLock2,
      lucideShieldCheck,
      lucideScanEye,
      lucideFilePen,
      lucideSend,
      lucideGavel,
      lucideBadgeCheck,
      lucideSearch,
      lucideScanSearch,
      lucideListChecks,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './landing.page.html',
})
export class LandingPage {
  private readonly api = inject(PublicApi);
  private readonly router = inject(Router);
  protected readonly auth = inject(AuthStore);

  protected readonly stats = rxResource({ stream: () => this.api.stats() });
  protected readonly lookup = signal('');
  protected readonly lookupError = signal<string | null>(null);
  protected readonly fmt = formatNumber;

  protected readonly statTiles = computed(() => {
    if (!this.stats.hasValue()) return [];
    const s = this.stats.value();
    return [
      { label: 'Applications filed', value: s.applicationsFiled },
      { label: 'Published', value: s.published },
      { label: 'Patents issued', value: s.issued },
      { label: 'Documents sanitized', value: s.documentsProcessed },
      { label: 'Redactions applied', value: s.redactionsApplied },
    ];
  });

  protected readonly capabilities = [
    {
      icon: 'lucideFileLock2',
      title: 'Secure direct-to-storage filing',
      body: 'Large PDFs upload straight from your browser to encrypted object storage using pre-signed, 15-minute links. The application server never touches the file bytes, and every upload is verified on arrival.',
      points: ['Up to 500 MB per document', 'Progress, cancel & retry', 'PDF header verification'],
    },
    {
      icon: 'lucideListChecks',
      title: 'Role-based status tracking',
      body: 'A TSDR-style prosecution history for every application. Inventors see only their own filings; examiners work a shared queue and move cases through pre-exam, examination and allowance.',
      points: ['Complete audit trail', 'Notifications on every action', 'Optimistic-locking safety'],
    },
    {
      icon: 'lucideScanEye',
      title: 'AI redaction of PII & CUI',
      body: 'When a public document lands, a serverless function combines Claude with deterministic rules to find personal and controlled information, then burns the redactions into a sanitized Public Copy.',
      points: ['SSNs, addresses, account numbers', 'CUI & export-control markings', 'Image-only output — nothing hidden survives'],
    },
  ];

  protected readonly steps = [
    { icon: 'lucideFilePen', title: 'Prepare', body: 'Enter bibliographic data and inventors. A draft is saved immediately.' },
    { icon: 'lucideCloudUpload', title: 'Upload', body: 'Drag in your specification, claims, abstract and drawings.' },
    { icon: 'lucideSend', title: 'Submit', body: 'Certify with your S-signature and receive an application number and receipt.' },
    { icon: 'lucideGavel', title: 'Examination', body: 'Track office actions, respond with documents, and follow every status change.' },
    { icon: 'lucideBadgeCheck', title: 'Publish', body: 'Published applications expose only sanitized public copies to the world.' },
  ];

  protected submitLookup(event: Event): void {
    event.preventDefault();
    const n = parseApplicationNumber(this.lookup());
    if (!n) {
      this.lookupError.set('Enter an 8-digit application number, e.g. 19/000,123.');
      return;
    }
    this.lookupError.set(null);
    void this.router.navigate(['/status', n]);
  }
}
