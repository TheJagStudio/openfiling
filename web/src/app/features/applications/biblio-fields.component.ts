import { ChangeDetectionStrategy, Component, computed, effect, input, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';

import { ZardInputComponent } from '@/shared/components/input';
import { ZardSelectImports } from '@/shared/components/select';
import { ZardTextareaComponent } from '@/shared/components/textarea';

import { APPLICATION_LIMITS, APPLICATION_TYPES, ENTITY_STATUSES } from '../../core/models';
import { type BiblioForm, controlMessage } from './application-form';

let seq = 0;

/** Bibliographic data fields (title, type, entity status, abstract, field, docket) bound to a {@link BiblioForm}. */
@Component({
  selector: 'app-biblio-fields',
  imports: [ReactiveFormsModule, ZardInputComponent, ZardTextareaComponent, ...ZardSelectImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="grid gap-5" [formGroup]="form()">
      <div class="space-y-1.5">
        <label [for]="p + '-title'" class="field-label">Title of the invention <span class="text-destructive" aria-hidden="true">*</span></label>
        <input
          z-input
          [id]="p + '-title'"
          formControlName="title"
          aria-required="true"
          [attr.maxlength]="L.titleMax"
          [attr.aria-invalid]="!!err('title', 'Title')"
          [attr.aria-describedby]="p + '-title-msg'"
        />
        @if (err('title', 'Title'); as e) {
          <p class="field-error" [id]="p + '-title-msg'">{{ e }}</p>
        } @else {
          <p class="field-hint" [id]="p + '-title-msg'">{{ L.titleMin }}–{{ L.titleMax }} characters. Be specific and technical, not promotional.</p>
        }
      </div>

      <fieldset class="space-y-2">
        <legend class="field-label">Application type <span class="text-destructive" aria-hidden="true">*</span></legend>
        <div class="grid gap-2 sm:grid-cols-2">
          @for (t of types; track t.code) {
            <label
              class="has-[:checked]:border-primary has-[:checked]:bg-primary/5 has-[:focus-visible]:ring-ring/50 hover:bg-muted/50 flex cursor-pointer gap-3 rounded-lg border p-3 has-[:focus-visible]:ring-2"
              [class.opacity-60]="lockType()"
              [class.cursor-not-allowed]="lockType()"
            >
              <input type="radio" class="accent-primary mt-1" formControlName="applicationType" [value]="t.code" />
              <span>
                <span class="block text-sm font-medium">{{ t.label }}</span>
                <span class="text-muted-foreground block text-xs">{{ t.description }}</span>
              </span>
            </label>
          }
        </div>
        @if (lockType()) {
          <p class="field-hint">The application type cannot be changed once the application is filed.</p>
        }
      </fieldset>

      <div class="grid gap-5 sm:grid-cols-2">
        <div class="space-y-1.5">
          <span class="field-label block" [id]="p + '-entity-label'">Entity status <span class="text-destructive" aria-hidden="true">*</span></span>
          <z-select formControlName="entityStatus" zAriaLabel="Entity status" zPlaceholder="Select entity status" class="w-full">
            @for (e of entities; track e.code) {
              <z-select-item [zValue]="e.code">{{ e.label }}</z-select-item>
            }
          </z-select>
          <p class="field-hint">{{ entityHint() }}</p>
        </div>
        <div class="space-y-1.5">
          <label [for]="p + '-docket'" class="field-label">Attorney docket number</label>
          <input
            z-input
            [id]="p + '-docket'"
            formControlName="attorneyDocketNumber"
            class="font-mono"
            [attr.maxlength]="L.docketMax"
            [attr.aria-invalid]="!!err('attorneyDocketNumber', 'Docket number')"
            [attr.aria-describedby]="p + '-docket-msg'"
          />
          <p [class]="err('attorneyDocketNumber', 'Docket number') ? 'field-error' : 'field-hint'" [id]="p + '-docket-msg'">
            {{ err('attorneyDocketNumber', 'Docket number') ?? 'Optional, up to ' + L.docketMax + ' characters.' }}
          </p>
        </div>
      </div>

      <div class="space-y-1.5">
        <label [for]="p + '-field'" class="field-label">Technology field</label>
        <input
          z-input
          [id]="p + '-field'"
          formControlName="technologyField"
          placeholder="e.g. Electrochemistry — solid-state batteries"
          [attr.maxlength]="L.technologyFieldMax"
          [attr.aria-invalid]="!!err('technologyField', 'Technology field')"
          [attr.aria-describedby]="p + '-field-msg'"
        />
        <p [class]="err('technologyField', 'Technology field') ? 'field-error' : 'field-hint'" [id]="p + '-field-msg'">
          {{ err('technologyField', 'Technology field') ?? 'Optional. Helps route the application to the right art unit.' }}
        </p>
      </div>

      <div class="space-y-1.5">
        <label [for]="p + '-abstract'" class="field-label">Abstract</label>
        <textarea
          z-textarea
          [id]="p + '-abstract'"
          formControlName="abstractText"
          rows="6"
          [attr.maxlength]="L.abstractMax"
          [attr.aria-invalid]="!!err('abstractText', 'Abstract')"
          [attr.aria-describedby]="p + '-abstract-msg'"
        ></textarea>
        <div class="flex justify-between gap-3" [id]="p + '-abstract-msg'">
          <p [class]="err('abstractText', 'Abstract') ? 'field-error' : 'field-hint'">
            {{ err('abstractText', 'Abstract') ?? 'A concise summary (typically ≤ 150 words). Also upload the formal abstract (ABST) document.' }}
          </p>
          <span class="field-hint tabular shrink-0">{{ abstractLength() }} / {{ L.abstractMax }}</span>
        </div>
      </div>
    </div>
  `,
})
export class BiblioFieldsComponent {
  readonly form = input.required<BiblioForm>();
  readonly submitted = input(false);
  /** Filed applications keep their type. */
  readonly lockType = input(false);

  protected readonly p = `bib${++seq}`;
  protected readonly L = APPLICATION_LIMITS;
  protected readonly types = APPLICATION_TYPES;
  protected readonly entities = ENTITY_STATUSES;

  protected readonly abstractLength = signal(0);
  private readonly entity = signal<string>('');
  protected readonly entityHint = computed(() => ENTITY_STATUSES.find((e) => e.code === this.entity())?.description ?? '');

  constructor() {
    // Mirror the bits of form state the template derives from (counter, entity hint).
    effect((onCleanup) => {
      const f = this.form();
      const sync = () => {
        this.abstractLength.set(f.controls.abstractText.value?.length ?? 0);
        this.entity.set(f.controls.entityStatus.value);
      };
      sync();
      const sub = f.valueChanges.subscribe(sync);
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected err(name: keyof BiblioForm['controls'], label: string): string | null {
    const c = this.form().controls[name];
    if (c.valid || !(c.touched || this.submitted())) return null;
    return controlMessage(c, label);
  }
}
