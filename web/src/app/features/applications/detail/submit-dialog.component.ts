import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideCircleCheck, lucideCircleX, lucideSend } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCheckboxComponent } from '@/shared/components/checkbox';
import { injectDialogData, ZardDialogRef } from '@/shared/components/dialog';
import { ZardInputComponent } from '@/shared/components/input';

import { ApplicationsApi } from '../../../core/api/applications.api';
import { ReferenceStore } from '../../../core/api/reference.api';
import { AuthStore } from '../../../core/auth/auth.store';
import { toProblem } from '../../../core/http/problem';
import type { ApplicationDetail, DocumentResponse, FieldError, ProblemDetail } from '../../../core/models';
import { Notifier } from '../../../core/notify/notifier.service';
import { buildSubmitRequest, signatureError, submissionChecklist, suggestSignature } from './workflow-forms';

export interface SubmitDialogData {
  application: ApplicationDetail;
  documents: DocumentResponse[];
  onDone: (updated: ApplicationDetail) => void;
}

/**
 * "Submit application" dialog: required-documents checklist, certification checkbox and an
 * S-signature (`/First Last/`). 422 business-rule errors from the API are listed inline.
 */
@Component({
  selector: 'app-submit-dialog',
  imports: [NgIcon, ZardButtonComponent, ZardCheckboxComponent, ZardInputComponent, ZardAlertComponent],
  viewProviders: [provideIcons({ lucideCircleCheck, lucideCircleX, lucideCircleAlert, lucideSend })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="space-y-5" novalidate (submit)="$event.preventDefault(); submit()">
      <section aria-labelledby="sub-check-h" class="space-y-2">
        <h3 id="sub-check-h" class="text-sm font-semibold">Required documents</h3>
        <ul class="divide-y rounded-lg border text-sm">
          @for (item of checklist().items; track item.code) {
            <li class="flex items-center gap-2 px-3 py-2">
              <ng-icon
                [name]="item.present ? 'lucideCircleCheck' : 'lucideCircleX'"
                class="size-4 shrink-0"
                [class]="item.present ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'"
                aria-hidden="true"
              />
              <span class="font-mono text-xs font-semibold">{{ item.code }}</span>
              <span>{{ item.label }}</span>
              <span class="ml-auto text-xs" [class]="item.present ? 'text-muted-foreground' : 'text-destructive font-medium'">{{ item.present ? 'Uploaded' : 'Missing' }}</span>
            </li>
          }
        </ul>
        @if (checklist().blocking.length) {
          <p class="field-error">
            {{ checklist().blocking.length }} document(s) are not finished uploading or were rejected. Remove or re-upload them before submitting.
          </p>
        }
      </section>

      @if (serverErrors().length || problem()) {
        <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="problem()?.title ?? 'The application cannot be submitted yet'" [zDescription]="errorList" />
        <ng-template #errorList>
          @if (problem()?.detail) {
            <p>{{ problem()?.detail }}</p>
          }
          <ul class="mt-1 list-disc space-y-0.5 pl-4">
            @for (e of serverErrors(); track $index) {
              <li>{{ e.message }}</li>
            }
          </ul>
        </ng-template>
      }

      <div class="bg-muted/40 space-y-3 rounded-lg border p-4">
        <z-checkbox zId="sub-certify" [zInvalid]="attempted() && !certify()" (checkChange)="certify.set($event)">
          <span class="text-sm leading-snug">
            I certify that the information in this application is true and complete to the best of my knowledge, and that I am authorized to file it.
          </span>
        </z-checkbox>
        @if (attempted() && !certify()) {
          <p class="field-error" role="alert">You must certify the application before submitting.</p>
        }
      </div>

      <div class="space-y-1.5">
        <label for="sub-signature" class="field-label">Electronic signature (S-signature) <span class="text-destructive" aria-hidden="true">*</span></label>
        <input
          id="sub-signature"
          z-input
          class="font-mono"
          autocomplete="off"
          spellcheck="false"
          [placeholder]="placeholder"
          [value]="signature()"
          (input)="signature.set($any($event.target).value)"
          aria-required="true"
          [attr.aria-invalid]="!!shownSignatureError()"
          aria-describedby="sub-signature-msg"
        />
        <p id="sub-signature-msg" [class]="shownSignatureError() ? 'field-error' : 'field-hint'">
          {{ shownSignatureError() ?? 'Type your name between forward slashes, e.g. ' + placeholder + '. This has the effect of a handwritten signature.' }}
        </p>
      </div>

      <p class="text-muted-foreground text-xs">
        On submission the application receives an application number, a confirmation number and today’s filing date. Bibliographic data and
        documents can no longer be changed freely.
      </p>

      <div class="flex justify-end gap-2">
        <button type="button" z-button zType="outline" [zDisabled]="busy()" (click)="ref.close()">Cancel</button>
        <button type="submit" z-button [zLoading]="busy()" [zDisabled]="busy()"><ng-icon name="lucideSend" aria-hidden="true" /> Submit application</button>
      </div>
    </form>
  `,
})
export class SubmitDialogComponent {
  private readonly data = injectDialogData<SubmitDialogData>();
  protected readonly ref = inject(ZardDialogRef);
  private readonly api = inject(ApplicationsApi);
  private readonly reference = inject(ReferenceStore);
  private readonly notifier = inject(Notifier);

  protected readonly placeholder = suggestSignature(inject(AuthStore).user()?.displayName) || '/Ada Lovelace/';
  protected readonly checklist = computed(() =>
    submissionChecklist(this.data.application.applicationType, this.data.documents, (c) => this.reference.docLabel(c)),
  );

  protected readonly certify = signal(false);
  protected readonly signature = signal('');
  protected readonly attempted = signal(false);
  protected readonly busy = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);
  protected readonly serverErrors = signal<FieldError[]>([]);

  private readonly sigError = computed(() => signatureError(this.signature()));
  protected readonly shownSignatureError = computed(() => (this.attempted() ? this.sigError() : null));

  protected submit(): void {
    this.attempted.set(true);
    this.problem.set(null);
    this.serverErrors.set([]);
    if (!this.certify() || this.sigError()) {
      setTimeout(() => document.getElementById(this.certify() ? 'sub-signature' : 'sub-certify')?.focus());
      return;
    }
    this.busy.set(true);
    const app = this.data.application;
    this.api.submit(app.id, buildSubmitRequest(this.signature(), app.version)).subscribe({
      next: (updated) => {
        this.notifier.success('Application submitted', {
          description: `Application number ${updated.applicationNumberDisplay ?? ''} · confirmation ${updated.confirmationNumber ?? ''}`,
        });
        this.data.onDone(updated);
        this.ref.close();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        const p = toProblem(err);
        this.serverErrors.set(p.errors ?? []);
        this.problem.set(
          p.status === 409
            ? { ...p, title: 'This application changed', detail: 'It was updated elsewhere. Close this dialog and try again after the page reloads.' }
            : p.status === 422
              ? { ...p, title: 'The application is incomplete' }
              : p,
        );
        if (p.status === 409) this.data.onDone(app);
      },
    });
  }
}
