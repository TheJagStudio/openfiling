import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormArray, FormBuilder } from '@angular/forms';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleAlert, lucideRefreshCw, lucideRotateCcw, lucideSave, lucideStar } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardTableImports } from '@/shared/components/table';

import { ApplicationsApi } from '../../../core/api/applications.api';
import type { ApplicationDetail, ProblemDetail } from '../../../core/models';
import { Notifier } from '../../../core/notify/notifier.service';
import { inventorGroup, type InventorForm, requestFromDetail, toInventorDto } from '../application-form';
import { InventorsEditorComponent } from '../inventors-editor.component';
import { describeSaveError } from './biblio-tab.component';

@Component({
  selector: 'app-inventors-tab',
  imports: [NgIcon, ZardButtonComponent, ZardAlertComponent, ...ZardTableImports, InventorsEditorComponent],
  viewProviders: [provideIcons({ lucideSave, lucideRotateCcw, lucideCircleAlert, lucideRefreshCw, lucideStar })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (app().permissions.canEdit) {
      <form novalidate class="space-y-5" (submit)="$event.preventDefault(); save()">
        @if (problem(); as p) {
          <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="p.title ?? 'Could not save'" [zDescription]="tpl" />
          <ng-template #tpl>
            <p>{{ p.detail }}</p>
            @if (p.status === 409) {
              <button type="button" z-button zType="outline" zSize="sm" class="mt-2" (click)="reloadRequested.emit()">
                <ng-icon name="lucideRefreshCw" aria-hidden="true" /> Reload latest version
              </button>
            }
          </ng-template>
        }
        <app-inventors-editor [array]="array()" [submitted]="attempted()" />
        <div class="flex justify-end gap-2 border-t pt-4">
          <button type="button" z-button zType="ghost" [zDisabled]="busy()" (click)="reset()"><ng-icon name="lucideRotateCcw" aria-hidden="true" /> Discard changes</button>
          <button type="submit" z-button [zLoading]="busy()" [zDisabled]="busy()"><ng-icon name="lucideSave" aria-hidden="true" /> Save inventors</button>
        </div>
      </form>
    } @else {
      <div class="bg-card relative overflow-x-auto rounded-xl border">
        <table z-table class="min-w-[40rem]">
          <caption class="sr-only">Inventors, in order; the first is the first named inventor</caption>
          <thead z-table-header>
            <tr z-table-row>
              <th z-table-head scope="col" class="w-10">#</th>
              <th z-table-head scope="col">Name</th>
              <th z-table-head scope="col">Residence</th>
              <th z-table-head scope="col">Citizenship</th>
            </tr>
          </thead>
          <tbody z-table-body>
            @for (inv of app().inventors; track $index; let i = $index) {
              <tr z-table-row>
                <td z-table-cell class="text-muted-foreground tabular">{{ i + 1 }}</td>
                <td z-table-cell>
                  <span class="font-medium">{{ inv.givenName }} {{ inv.familyName }}</span>
                  @if (i === 0) {
                    <span class="text-muted-foreground ml-2 inline-flex items-center gap-1 text-xs"><ng-icon name="lucideStar" class="text-seal size-3" aria-hidden="true" /> First named</span>
                  }
                </td>
                <td z-table-cell class="text-sm">{{ place(inv.city, inv.state, inv.country) }}</td>
                <td z-table-cell class="font-mono text-sm">{{ inv.citizenship }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class InventorsTabComponent {
  private readonly api = inject(ApplicationsApi);
  private readonly notifier = inject(Notifier);
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly app = input.required<ApplicationDetail>();
  readonly saved = output<ApplicationDetail>();
  readonly reloadRequested = output<void>();

  protected readonly array = signal(new FormArray<InventorForm>([]));
  protected readonly attempted = signal(false);
  protected readonly busy = signal(false);
  protected readonly problem = signal<ProblemDetail | null>(null);

  constructor() {
    effect(() => {
      const app = this.app();
      untracked(() => this.load(app));
    });
  }

  private load(app: ApplicationDetail): void {
    const arr = new FormArray<InventorForm>((app.inventors.length ? app.inventors : [{}]).map((i) => inventorGroup(this.fb, i)));
    this.array.set(arr);
    this.attempted.set(false);
    this.problem.set(null);
  }

  protected reset(): void {
    this.load(this.app());
  }

  protected save(): void {
    this.attempted.set(true);
    this.problem.set(null);
    const arr = this.array();
    if (arr.invalid) {
      arr.markAllAsTouched();
      return;
    }
    const app = this.app();
    this.busy.set(true);
    const body = { ...requestFromDetail(app), inventors: arr.getRawValue().map((v) => toInventorDto(v)), version: app.version };
    this.api.update(app.id, body).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.notifier.success('Inventors saved');
        this.saved.emit(updated);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.problem.set(describeSaveError(err));
      },
    });
  }

  protected place(...parts: (string | null)[]): string {
    return parts.filter((p) => !!p).join(', ') || '—';
  }
}
