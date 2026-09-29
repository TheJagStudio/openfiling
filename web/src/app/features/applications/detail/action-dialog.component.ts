import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideFileText, lucideTriangleAlert } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCheckboxComponent } from '@/shared/components/checkbox';
import { injectDialogData, ZardDialogRef } from '@/shared/components/dialog';
import { ZardInputComponent } from '@/shared/components/input';
import { ZardTextareaComponent } from '@/shared/components/textarea';

import { ApplicationsApi } from '../../../core/api/applications.api';
import { formatDate } from '../../../core/format';
import { fieldErrors, toProblem } from '../../../core/http/problem';
import { APPLICATION_LIMITS, type ApplicationDetail, type AvailableAction, type DocumentResponse, type ProblemDetail, STATUS_META } from '../../../core/models';
import { Notifier } from '../../../core/notify/notifier.service';
import { type ActionFieldErrors, actionErrors, buildActionRequest, candidateDocuments } from './workflow-forms';

export interface ActionDialogData {
  application: ApplicationDetail;
  action: AvailableAction;
  documents: DocumentResponse[];
  onDone: (updated: ApplicationDetail) => void;
}

/**
 * Confirmation dialog for one workflow action (from `availableActions`): note when required,
 * art unit for DOCKET, optional examiner documents when `acceptsDocuments`, destructive styling
 * for ABANDON. POSTs `/actions` with the current `version`; 403/409/422 are shown inline.
 */
@Component({
  selector: 'app-action-dialog',
  imports: [NgIcon, ZardButtonComponent, ZardTextareaComponent, ZardInputComponent, ZardCheckboxComponent, ZardAlertComponent],
  viewProviders: [provideIcons({ lucideCircleAlert, lucideTriangleAlert, lucideFileText })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="space-y-4" novalidate (submit)="$event.preventDefault(); submit()">
      @if (action.toStatus) {
        <p class="text-sm">
          <span class="text-muted-foreground">Status change:</span>
          <strong>{{ statusShort(app.status) }}</strong> <span aria-hidden="true">→</span><span class="sr-only">to</span> <strong>{{ statusShort(action.toStatus) }}</strong>
        </p>
      } @else {
        <p class="text-muted-foreground text-sm">This action does not change the application status.</p>
      }

      @if (action.destructive) {
        <z-alert
          zType="destructive"
          zIcon="lucideTriangleAlert"
          zTitle="This cannot be undone"
          zDescription="Abandoned applications are no longer pending. The owner and the assigned examiner will be notified."
        />
      }

      @if (problem(); as p) {
        <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="p.title ?? 'The action failed'" [zDescription]="problemText()" />
      }

      @if (action.action === 'DOCKET') {
        <div class="space-y-1.5">
          <label for="act-artunit" class="field-label">Art unit</label>
          <input
            id="act-artunit"
            z-input
            class="w-40 font-mono"
            inputmode="numeric"
            maxlength="4"
            placeholder="2100"
            [value]="artUnit()"
            (input)="artUnit.set($any($event.target).value)"
            [attr.aria-invalid]="!!shownErrors().artUnit"
            aria-describedby="act-artunit-msg"
          />
          <p id="act-artunit-msg" [class]="shownErrors().artUnit ? 'field-error' : 'field-hint'">
            {{ shownErrors().artUnit ?? 'Optional — defaults to your own art unit. You are assigned if the case is unassigned.' }}
          </p>
        </div>
      }

      @if (action.acceptsDocuments) {
        <fieldset class="space-y-2">
          <legend class="field-label">Attach examiner documents <span class="text-muted-foreground font-normal">(optional)</span></legend>
          @if (candidates().length) {
            <ul class="max-h-48 space-y-1 overflow-y-auto rounded-lg border p-2">
              @for (d of candidates(); track d.id) {
                <li class="hover:bg-muted/50 rounded-md px-2 py-1.5">
                  <z-checkbox [zId]="'act-doc-' + d.id" (checkChange)="toggleDoc(d.id, $event)">
                    <span class="flex flex-col">
                      <span class="text-sm font-medium">{{ d.docCode }} — {{ d.docCodeLabel }}</span>
                      <span class="text-muted-foreground text-xs">{{ d.fileName }} · uploaded {{ date(d.uploadedAt) }}</span>
                    </span>
                  </z-checkbox>
                </li>
              }
            </ul>
          } @else {
            <p class="text-muted-foreground flex items-start gap-2 rounded-lg border border-dashed p-3 text-xs">
              <ng-icon name="lucideFileText" class="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              No examiner documents uploaded yet. Upload the office action (e.g. CTNF, CTFR, NOA) on the Documents tab first if you want to attach it.
            </p>
          }
        </fieldset>
      }

      <div class="space-y-1.5">
        <label for="act-note" class="field-label">
          {{ action.requiresNote ? 'Note' : 'Note (optional)' }}
          @if (action.requiresNote) {
            <span class="text-destructive" aria-hidden="true">*</span>
          }
        </label>
        <textarea
          id="act-note"
          z-textarea
          rows="4"
          [attr.maxlength]="noteMax"
          [attr.aria-required]="action.requiresNote"
          [value]="note()"
          (input)="note.set($any($event.target).value)"
          [attr.aria-invalid]="!!shownErrors().note"
          aria-describedby="act-note-msg"
          [placeholder]="notePlaceholder()"
        ></textarea>
        <div id="act-note-msg" class="flex justify-between gap-3">
          <p [class]="shownErrors().note ? 'field-error' : 'field-hint'">
            {{ shownErrors().note ?? 'Recorded in the prosecution history and included in the notification.' }}
          </p>
          <span class="field-hint tabular shrink-0">{{ note().length }} / {{ noteMax }}</span>
        </div>
      </div>

      <div class="flex justify-end gap-2 pt-2">
        <button type="button" z-button zType="outline" [zDisabled]="busy()" (click)="ref.close()">Cancel</button>
        <button type="submit" z-button [zType]="action.destructive ? 'destructive' : 'default'" [class]="action.destructive ? '' : ''" [zLoading]="busy()" [zDisabled]="busy()">
          {{ action.label }}
        </button>
      </div>
    </form>
  `,
})
export class ActionDialogComponent {
  private readonly data = injectDialogData<ActionDialogData>();
  protected readonly ref = inject(ZardDialogRef);
  private readonly api = inject(ApplicationsApi);
  private readonly notifier = inject(Notifier);

  protected readonly app = this.data.application;
  protected readonly action = this.data.action;
  protected readonly noteMax = APPLICATION_LIMITS.noteMax;
  protected readonly candidates = computed(() => candidateDocuments(this.action, this.data.documents));

  protected readonly note = signal('');
  protected readonly artUnit = signal('');
  protected readonly documentIds = signal<string[]>([]);
  protected readonly attempted = signal(false);
  protected readonly busy = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);

  private readonly errors = computed<ActionFieldErrors>(() =>
    actionErrors(this.action, { note: this.note(), artUnit: this.artUnit(), documentIds: this.documentIds() }),
  );
  protected readonly shownErrors = computed<ActionFieldErrors>(() => (this.attempted() ? this.errors() : {}));
  protected readonly problemText = computed(() => {
    const p = this.problem();
    if (!p) return '';
    if (p.status === 409) return 'Someone else updated this application while you were working. Close this dialog — the page will reload the latest version.';
    if (p.status === 403) return p.detail ?? 'You are not allowed to perform this action in the current state.';
    const extra = Object.values(fieldErrors(p));
    return [p.detail, ...extra].filter(Boolean).join(' ');
  });

  protected notePlaceholder(): string {
    switch (this.action.action) {
      case 'NOTICE_MISSING_PARTS':
        return 'List the missing parts, e.g. “Claims (CLM) not received.”';
      case 'ISSUE_NON_FINAL_REJECTION':
      case 'ISSUE_FINAL_REJECTION':
        return 'Summarize the grounds of rejection, e.g. “Claims 1–5 rejected under 35 U.S.C. 103 over Smith in view of Jones.”';
      case 'FILE_RESPONSE':
        return 'Summarize your response and amendments. Make sure the RESP document is uploaded.';
      case 'ABANDON':
        return 'Reason for abandonment.';
      default:
        return '';
    }
  }

  protected statusShort(code: string): string {
    return STATUS_META[code as keyof typeof STATUS_META]?.short ?? code;
  }

  protected date(v: string | null): string {
    return formatDate(v);
  }

  protected toggleDoc(id: string, checked: boolean): void {
    this.documentIds.update((ids) => (checked ? [...new Set([...ids, id])] : ids.filter((x) => x !== id)));
  }

  protected submit(): void {
    this.attempted.set(true);
    this.problem.set(null);
    if (Object.keys(this.errors()).length) {
      setTimeout(() => (document.querySelector('z-dialog [aria-invalid="true"]') as HTMLElement | null)?.focus());
      return;
    }
    this.busy.set(true);
    const body = buildActionRequest(this.action, { note: this.note(), artUnit: this.artUnit(), documentIds: this.documentIds() }, this.app.version);
    this.api.action(this.app.id, body).subscribe({
      next: (updated) => {
        this.notifier.success(this.action.label, { description: updated.statusLabel });
        this.data.onDone(updated);
        this.ref.close();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        const p = toProblem(err);
        this.problem.set(p);
        if (p.status === 409) this.data.onDone(this.app); // trigger a reload behind the dialog
      },
    });
  }
}
