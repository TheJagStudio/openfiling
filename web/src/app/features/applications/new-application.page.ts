import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowLeft, lucideArrowRight, lucideCheck, lucideCircleAlert, lucideFilePlus2, lucidePencil } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';

import { ApplicationsApi } from '../../core/api/applications.api';
import { fieldErrors, toProblem } from '../../core/http/problem';
import { APPLICATION_TYPES, ENTITY_STATUSES, type ProblemDetail, REQUIRED_DOCUMENTS } from '../../core/models';
import { Notifier } from '../../core/notify/notifier.service';
import { BreadcrumbsComponent } from '../../ui/breadcrumbs.component';
import { PageHeaderComponent } from '../../ui/page-header.component';
import { biblioGroup, inventorsArray, toCreateRequest } from './application-form';
import { BiblioFieldsComponent } from './biblio-fields.component';
import { InventorsEditorComponent } from './inventors-editor.component';

type Step = 0 | 1 | 2;

/** 3-step wizard: bibliographic data → inventors → review; creates a DRAFT and opens its Documents tab. */
@Component({
  selector: 'app-new-application',
  imports: [
    ReactiveFormsModule,
    NgIcon,
    ZardButtonComponent,
    ZardAlertComponent,
    ...ZardCardImports,
    PageHeaderComponent,
    BreadcrumbsComponent,
    BiblioFieldsComponent,
    InventorsEditorComponent,
  ],
  viewProviders: [provideIcons({ lucideArrowLeft, lucideArrowRight, lucideCheck, lucideCircleAlert, lucideFilePlus2, lucidePencil })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto max-w-4xl space-y-8 px-4 py-8 sm:px-6">
      <app-page-header
        eyebrow="New application"
        heading="Start a patent application"
        description="Your application is saved as a private draft. You’ll upload documents and submit it on the next screen."
      >
        <app-breadcrumbs breadcrumb [items]="[{ label: 'My applications', link: '/applications' }, { label: 'New application' }]" />
      </app-page-header>

      <!-- Stepper -->
      <nav aria-label="Progress">
        <ol class="grid grid-cols-3 gap-2">
          @for (s of steps; track s.label; let i = $index) {
            <li>
              <button
                type="button"
                class="group flex w-full flex-col gap-2 text-left outline-none disabled:cursor-not-allowed"
                [disabled]="i > maxReached()"
                [attr.aria-current]="step() === i ? 'step' : null"
                (click)="goTo($any(i))"
              >
                <span class="h-1.5 rounded-full transition-colors" [class]="i <= step() ? 'bg-primary' : 'bg-muted'"></span>
                <span class="flex items-center gap-2 text-sm">
                  <span
                    class="grid size-6 shrink-0 place-items-center rounded-full text-xs font-semibold group-focus-visible:ring-2 group-focus-visible:ring-ring/50"
                    [class]="i < step() ? 'bg-primary text-primary-foreground' : i === step() ? 'border-primary text-primary border-2' : 'bg-muted text-muted-foreground'"
                  >
                    @if (i < step()) {
                      <ng-icon name="lucideCheck" class="size-3.5" aria-hidden="true" />
                    } @else {
                      {{ i + 1 }}
                    }
                  </span>
                  <span [class]="i === step() ? 'font-semibold' : 'text-muted-foreground'">{{ s.label }}</span>
                  <span class="sr-only">{{ i < step() ? '(completed)' : i === step() ? '(current step)' : '' }}</span>
                </span>
              </button>
            </li>
          }
        </ol>
      </nav>

      @if (problem(); as p) {
        <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="p.title ?? 'Could not create the application'" [zDescription]="problemText()" />
      }

      <form novalidate (submit)="$event.preventDefault(); next()">
        <z-card>
          <z-card-header>
            <z-card-title><h2 #stepHeading tabindex="-1" class="outline-none">{{ steps[step()].title }}</h2></z-card-title>
            <z-card-description>{{ steps[step()].description }}</z-card-description>
          </z-card-header>
          <z-card-content>
            @switch (step()) {
              @case (0) {
                <app-biblio-fields [form]="biblio" [submitted]="attempted()[0]" />
              }
              @case (1) {
                <app-inventors-editor [array]="inventors" [submitted]="attempted()[1]" />
              }
              @case (2) {
                @let r = review();
                <div class="space-y-6">
                  <section aria-labelledby="rev-biblio" class="space-y-3">
                    <div class="flex items-center justify-between">
                      <h3 id="rev-biblio" class="font-semibold">Bibliographic data</h3>
                      <button type="button" z-button zType="ghost" zSize="sm" (click)="goTo(0)"><ng-icon name="lucidePencil" aria-hidden="true" /> Edit</button>
                    </div>
                    <dl class="grid gap-x-6 gap-y-3 rounded-lg border p-4 text-sm sm:grid-cols-2">
                      <div class="sm:col-span-2"><dt class="text-muted-foreground text-xs">Title</dt><dd class="font-medium">{{ r.title }}</dd></div>
                      <div><dt class="text-muted-foreground text-xs">Application type</dt><dd>{{ typeLabel(r.applicationType) }}</dd></div>
                      <div><dt class="text-muted-foreground text-xs">Entity status</dt><dd>{{ entityLabel(r.entityStatus) }}</dd></div>
                      <div><dt class="text-muted-foreground text-xs">Technology field</dt><dd>{{ r.technologyField ?? '—' }}</dd></div>
                      <div><dt class="text-muted-foreground text-xs">Attorney docket number</dt><dd class="font-mono">{{ r.attorneyDocketNumber ?? '—' }}</dd></div>
                      <div class="sm:col-span-2">
                        <dt class="text-muted-foreground text-xs">Abstract</dt>
                        <dd class="whitespace-pre-line">{{ r.abstractText ?? '—' }}</dd>
                      </div>
                    </dl>
                  </section>
                  <section aria-labelledby="rev-inv" class="space-y-3">
                    <div class="flex items-center justify-between">
                      <h3 id="rev-inv" class="font-semibold">Inventors ({{ r.inventors.length }})</h3>
                      <button type="button" z-button zType="ghost" zSize="sm" (click)="goTo(1)"><ng-icon name="lucidePencil" aria-hidden="true" /> Edit</button>
                    </div>
                    <ol class="divide-y rounded-lg border text-sm">
                      @for (inv of r.inventors; track $index; let i = $index) {
                        <li class="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-2.5">
                          <span class="font-medium">{{ inv.givenName }} {{ inv.familyName }}</span>
                          @if (i === 0) {
                            <span class="text-muted-foreground text-xs">First named inventor</span>
                          }
                          <span class="text-muted-foreground ml-auto text-xs">
                            {{ place(inv) }} · Citizenship {{ inv.citizenship }}
                          </span>
                        </li>
                      }
                    </ol>
                  </section>
                  <div class="bg-muted/50 rounded-lg p-4 text-sm">
                    <p class="font-medium">Next: upload documents</p>
                    <p class="text-muted-foreground mt-1">
                      A {{ typeLabel(r.applicationType).toLowerCase() }} application needs
                      <strong class="text-foreground">{{ requiredDocs().join(', ') }}</strong> before it can be submitted. Additional documents (drawings,
                      data sheet, declaration, IDS) are optional.
                    </p>
                  </div>
                </div>
              }
            }
          </z-card-content>
          <z-card-footer class="justify-between gap-2 border-t pt-6">
            <button type="button" z-button zType="ghost" [zDisabled]="step() === 0 || busy()" (click)="back()">
              <ng-icon name="lucideArrowLeft" aria-hidden="true" /> Back
            </button>
            @if (step() < 2) {
              <button type="submit" z-button>Continue <ng-icon name="lucideArrowRight" aria-hidden="true" /></button>
            } @else {
              <button type="submit" z-button [zLoading]="busy()" [zDisabled]="busy()">
                <ng-icon name="lucideFilePlus2" aria-hidden="true" /> Create draft &amp; upload documents
              </button>
            }
          </z-card-footer>
        </z-card>
      </form>
    </div>
  `,
})
export class NewApplicationPage {
  private readonly api = inject(ApplicationsApi);
  private readonly router = inject(Router);
  private readonly notifier = inject(Notifier);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly biblio = biblioGroup(this.fb);
  protected readonly inventors = inventorsArray(this.fb);

  protected readonly steps = [
    { label: 'Bibliographic data', title: 'Bibliographic data', description: 'Describe the invention and the kind of protection you are seeking.' },
    { label: 'Inventors', title: 'Inventors', description: 'List every inventor (1–20). The first inventor is the first named inventor.' },
    { label: 'Review', title: 'Review and create draft', description: 'Check the details. You can still edit them while the application is a draft.' },
  ] as const;

  protected readonly step = signal<Step>(0);
  protected readonly maxReached = signal<number>(0);
  protected readonly attempted = signal<[boolean, boolean, boolean]>([false, false, false]);
  protected readonly busy = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);
  private readonly reviewTick = signal(0);

  protected readonly review = computed(() => {
    this.reviewTick();
    return toCreateRequest(this.biblio.getRawValue(), this.inventors.getRawValue());
  });
  protected readonly requiredDocs = computed(() => REQUIRED_DOCUMENTS[this.review().applicationType]);
  protected readonly problemText = computed(() => {
    const p = this.problem();
    if (!p) return '';
    const fields = Object.entries(fieldErrors(p)).map(([f, m]) => `${f}: ${m}`);
    return [p.detail, ...fields].filter(Boolean).join(' · ');
  });

  protected place(inv: { city: string | null; state: string | null; country: string }): string {
    return [inv.city, inv.state, inv.country].filter((x) => !!x).join(', ');
  }

  protected typeLabel(code: string): string {
    return APPLICATION_TYPES.find((t) => t.code === code)?.label ?? code;
  }

  protected entityLabel(code: string): string {
    return ENTITY_STATUSES.find((t) => t.code === code)?.label ?? code;
  }

  private stepValid(step: Step): boolean {
    if (step === 0) return this.biblio.valid;
    if (step === 1) return this.inventors.valid;
    return this.biblio.valid && this.inventors.valid;
  }

  protected next(): void {
    const s = this.step();
    this.attempted.update((a) => {
      const copy = [...a] as [boolean, boolean, boolean];
      copy[s] = true;
      return copy;
    });
    if (!this.stepValid(s)) {
      (s === 0 ? this.biblio : this.inventors).markAllAsTouched();
      setTimeout(() => (document.querySelector('[aria-invalid="true"]') as HTMLElement | null)?.focus());
      return;
    }
    if (s < 2) {
      this.goTo((s + 1) as Step);
      return;
    }
    this.create();
  }

  protected back(): void {
    if (this.step() > 0) this.goTo((this.step() - 1) as Step);
  }

  protected goTo(step: Step): void {
    if (step > this.maxReached() && !this.stepValid(this.step())) return;
    this.reviewTick.update((v) => v + 1);
    this.step.set(step);
    this.maxReached.update((m) => Math.max(m, step));
    setTimeout(() => (document.querySelector('h2[tabindex="-1"]') as HTMLElement | null)?.focus());
  }

  private create(): void {
    this.busy.set(true);
    this.problem.set(null);
    this.api.create(this.review()).subscribe({
      next: (app) => {
        this.notifier.success('Draft created', { description: 'Upload the required documents, then submit your application.' });
        void this.router.navigate(['/applications', app.id], { queryParams: { tab: 'documents' } });
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.problem.set(toProblem(err));
      },
    });
  }
}
