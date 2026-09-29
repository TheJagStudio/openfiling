import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideRefreshCw, lucideRotateCcw, lucideSave } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';

import { ApplicationsApi } from '../../../core/api/applications.api';
import { formatApplicationNumber } from '../../../core/application-number';
import { formatDate, formatDateTime } from '../../../core/format';
import { fieldErrors, toProblem } from '../../../core/http/problem';
import { APPLICATION_TYPES, type ApplicationDetail, ENTITY_STATUSES, type ProblemDetail } from '../../../core/models';
import { Notifier } from '../../../core/notify/notifier.service';
import { biblioGroup, toCreateRequest } from '../application-form';
import { BiblioFieldsComponent } from '../biblio-fields.component';

/** Friendly problem for a failed PUT (409 = optimistic-lock conflict). */
export function describeSaveError(err: unknown): ProblemDetail {
  const p = toProblem(err);
  if (p.status === 409) {
    return {
      ...p,
      title: 'This application was changed by someone else',
      detail: 'Your copy is out of date (version conflict), so nothing was saved. Reload the latest version, then re-apply your changes.',
    };
  }
  const fields = Object.entries(fieldErrors(p)).map(([f, m]) => `${f}: ${m}`);
  return { ...p, detail: [p.detail, ...fields].filter(Boolean).join(' · ') };
}

@Component({
  selector: 'app-biblio-tab',
  imports: [ReactiveFormsModule, NgIcon, ZardButtonComponent, ZardAlertComponent, BiblioFieldsComponent],
  viewProviders: [provideIcons({ lucideSave, lucideRotateCcw, lucideCircleAlert, lucideRefreshCw })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (app().permissions.canEdit) {
      <form novalidate class="space-y-6" (submit)="$event.preventDefault(); save()">
        @if (problem(); as p) {
          <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="p.title ?? 'Could not save'" [zDescription]="conflictTpl" />
          <ng-template #conflictTpl>
            <p>{{ p.detail }}</p>
            @if (p.status === 409) {
              <button type="button" z-button zType="outline" zSize="sm" class="mt-2" (click)="reloadRequested.emit()">
                <ng-icon name="lucideRefreshCw" aria-hidden="true" /> Reload latest version
              </button>
            }
          </ng-template>
        }
        <app-biblio-fields [form]="form" [submitted]="attempted()" [lockType]="app().status !== 'DRAFT'" />
        <div class="flex flex-wrap items-center justify-between gap-3 border-t pt-4">
          <p class="text-muted-foreground text-xs">Version {{ app().version }} · last updated {{ dateTime(app().updatedAt) }}</p>
          <div class="flex gap-2">
            <button type="button" z-button zType="ghost" [zDisabled]="!dirty() || busy()" (click)="reset()">
              <ng-icon name="lucideRotateCcw" aria-hidden="true" /> Discard changes
            </button>
            <button type="submit" z-button [zLoading]="busy()" [zDisabled]="busy() || !dirty()"><ng-icon name="lucideSave" aria-hidden="true" /> Save changes</button>
          </div>
        </div>
      </form>
    } @else {
      <dl class="grid gap-x-8 gap-y-5 text-sm sm:grid-cols-2">
        <div class="sm:col-span-2"><dt class="text-muted-foreground text-xs font-medium uppercase">Title of invention</dt><dd class="mt-1 text-base font-medium">{{ app().title }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Application number</dt><dd class="mt-1 font-mono">{{ number() }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Confirmation number</dt><dd class="mt-1 font-mono">{{ app().confirmationNumber ?? '—' }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Application type</dt><dd class="mt-1">{{ typeLabel() }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Entity status</dt><dd class="mt-1">{{ entityLabel() }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Filing date</dt><dd class="mt-1">{{ date(app().filingDate) }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Attorney docket number</dt><dd class="mt-1 font-mono">{{ app().attorneyDocketNumber ?? '—' }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Technology field</dt><dd class="mt-1">{{ app().technologyField ?? '—' }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Art unit</dt><dd class="mt-1 font-mono">{{ app().artUnit ?? '—' }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Publication</dt><dd class="mt-1">{{ app().publicationNumber ? app().publicationNumber + ' · ' + date(app().publicationDate) : 'Not published' }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Patent</dt><dd class="mt-1">{{ app().patentNumber ? app().patentNumber + ' · issued ' + date(app().issueDate) : '—' }}</dd></div>
        <div><dt class="text-muted-foreground text-xs font-medium uppercase">Signature</dt><dd class="mt-1 font-mono">{{ app().signature ?? '—' }}</dd></div>
        <div class="sm:col-span-2">
          <dt class="text-muted-foreground text-xs font-medium uppercase">Abstract</dt>
          <dd class="mt-1 leading-relaxed whitespace-pre-line">{{ app().abstractText ?? '—' }}</dd>
        </div>
      </dl>
    }
  `,
})
export class BiblioTabComponent {
  private readonly api = inject(ApplicationsApi);
  private readonly notifier = inject(Notifier);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly app = input.required<ApplicationDetail>();
  readonly saved = output<ApplicationDetail>();
  readonly reloadRequested = output<void>();

  protected readonly form = biblioGroup(this.fb);
  protected readonly attempted = signal(false);
  protected readonly busy = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);
  protected readonly dirty = signal(false);

  protected readonly number = computed(() => formatApplicationNumber(this.app().applicationNumber));
  protected readonly typeLabel = computed(() => APPLICATION_TYPES.find((t) => t.code === this.app().applicationType)?.label ?? this.app().applicationType);
  protected readonly entityLabel = computed(() => ENTITY_STATUSES.find((t) => t.code === this.app().entityStatus)?.label ?? this.app().entityStatus);

  constructor() {
    // Reset the form whenever a new version of the application arrives.
    effect(() => {
      const app = this.app();
      untracked(() => this.load(app));
    });
    this.form.valueChanges.subscribe(() => this.dirty.set(this.form.dirty));
  }

  private load(app: ApplicationDetail): void {
    this.form.reset({
      title: app.title,
      applicationType: app.applicationType,
      entityStatus: app.entityStatus,
      abstractText: app.abstractText ?? '',
      technologyField: app.technologyField ?? '',
      attorneyDocketNumber: app.attorneyDocketNumber ?? '',
    });
    if (app.status !== 'DRAFT') this.form.controls.applicationType.disable({ emitEvent: false });
    else this.form.controls.applicationType.enable({ emitEvent: false });
    this.attempted.set(false);
    this.dirty.set(false);
    this.problem.set(null);
  }

  protected reset(): void {
    this.load(this.app());
  }

  protected save(): void {
    this.attempted.set(true);
    this.problem.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      setTimeout(() => (document.querySelector('app-biblio-tab [aria-invalid="true"]') as HTMLElement | null)?.focus());
      return;
    }
    const app = this.app();
    this.busy.set(true);
    const body = { ...toCreateRequest(this.form.getRawValue(), app.inventors), version: app.version };
    this.api.update(app.id, body).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.notifier.success('Bibliographic data saved');
        this.saved.emit(updated);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.problem.set(describeSaveError(err));
      },
    });
  }

  protected date(v: string | null): string {
    return formatDate(v);
  }

  protected dateTime(v: string | null): string {
    return formatDateTime(v);
  }
}
