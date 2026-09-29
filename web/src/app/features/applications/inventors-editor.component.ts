import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormArray, FormBuilder, ReactiveFormsModule } from '@angular/forms';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowDown, lucideArrowUp, lucideStar, lucideTrash2, lucideUserPlus } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardInputComponent } from '@/shared/components/input';

import { APPLICATION_LIMITS } from '../../core/models';
import { COMMON_COUNTRIES, controlMessage, inventorGroup, type InventorForm } from './application-form';

let editorSeq = 0;

/**
 * Dynamic inventor list (1–20) over a `FormArray`. The first row is the first named inventor;
 * rows can be reordered, added and removed. Errors show once a row is touched or the parent
 * form was submitted.
 */
@Component({
  selector: 'app-inventors-editor',
  imports: [ReactiveFormsModule, NgIcon, ZardButtonComponent, ZardInputComponent],
  viewProviders: [provideIcons({ lucideUserPlus, lucideTrash2, lucideArrowUp, lucideArrowDown, lucideStar })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <datalist [id]="prefix + '-countries'">
      @for (c of countries; track c.code) {
        <option [value]="c.code">{{ c.name }}</option>
      }
    </datalist>

    <ol class="space-y-4" aria-label="Inventors">
      @for (group of array().controls; track group; let i = $index; let first = $first; let last = $last) {
        <li class="bg-card rounded-xl border p-4 sm:p-5" [formGroup]="group">
          <div class="mb-4 flex flex-wrap items-center gap-2">
            <h3 class="text-sm font-semibold" [id]="prefix + '-h-' + i">Inventor {{ i + 1 }}</h3>
            @if (first) {
              <span class="bg-seal/20 text-seal-foreground dark:text-seal inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-semibold">
                <ng-icon name="lucideStar" class="size-3" aria-hidden="true" /> First named inventor
              </span>
            }
            <div class="ml-auto flex items-center gap-1">
              <button type="button" z-button zType="ghost" zSize="icon-sm" [zDisabled]="first" [attr.aria-label]="'Move inventor ' + (i + 1) + ' up'" (click)="move(i, -1)">
                <ng-icon name="lucideArrowUp" aria-hidden="true" />
              </button>
              <button type="button" z-button zType="ghost" zSize="icon-sm" [zDisabled]="last" [attr.aria-label]="'Move inventor ' + (i + 1) + ' down'" (click)="move(i, 1)">
                <ng-icon name="lucideArrowDown" aria-hidden="true" />
              </button>
              <button
                type="button"
                z-button
                zType="ghost"
                zSize="icon-sm"
                class="text-destructive"
                [zDisabled]="array().length <= limits.inventorsMin"
                [attr.aria-label]="'Remove inventor ' + (i + 1)"
                (click)="remove(i)"
              >
                <ng-icon name="lucideTrash2" aria-hidden="true" />
              </button>
            </div>
          </div>
          <div class="grid gap-4 sm:grid-cols-2" role="group" [attr.aria-labelledby]="prefix + '-h-' + i">
            @for (f of fields; track f.name) {
              <div class="space-y-1.5" [class.sm:col-span-1]="true">
                <label [for]="id(i, f.name)" class="field-label">
                  {{ f.label }}
                  @if (f.required) {
                    <span class="text-destructive" aria-hidden="true">*</span>
                  }
                </label>
                <input
                  z-input
                  [id]="id(i, f.name)"
                  [formControlName]="f.name"
                  [attr.autocomplete]="f.autocomplete"
                  [attr.maxlength]="f.max"
                  [attr.list]="f.country ? prefix + '-countries' : null"
                  [class.uppercase]="f.country"
                  [class.font-mono]="f.country"
                  [attr.aria-required]="f.required"
                  [attr.aria-invalid]="!!error(group, f.name, f.label)"
                  [attr.aria-describedby]="error(group, f.name, f.label) ? id(i, f.name) + '-err' : f.hint ? id(i, f.name) + '-hint' : null"
                />
                @if (error(group, f.name, f.label); as e) {
                  <p class="field-error" [id]="id(i, f.name) + '-err'">{{ e }}</p>
                } @else if (f.hint) {
                  <p class="field-hint" [id]="id(i, f.name) + '-hint'">{{ f.hint }}</p>
                }
              </div>
            }
          </div>
        </li>
      }
    </ol>

    <div class="mt-4 flex flex-wrap items-center justify-between gap-3">
      <p class="field-hint" aria-live="polite">{{ array().length }} of {{ limits.inventorsMax }} inventors</p>
      <button type="button" z-button zType="outline" [zDisabled]="array().length >= limits.inventorsMax" (click)="add()">
        <ng-icon name="lucideUserPlus" aria-hidden="true" /> Add inventor
      </button>
    </div>
  `,
})
export class InventorsEditorComponent {
  private readonly fb = inject(FormBuilder).nonNullable;

  readonly array = input.required<FormArray<InventorForm>>();
  /** Show all errors (after a submit attempt). */
  readonly submitted = input(false);

  protected readonly prefix = `inv${++editorSeq}`;
  protected readonly limits = APPLICATION_LIMITS;
  protected readonly countries = COMMON_COUNTRIES;
  protected readonly version = signal(0);

  protected readonly fields = [
    { name: 'givenName', label: 'Given name', required: true, max: APPLICATION_LIMITS.nameMax, autocomplete: 'off', country: false, hint: '' },
    { name: 'familyName', label: 'Family name', required: true, max: APPLICATION_LIMITS.nameMax, autocomplete: 'off', country: false, hint: '' },
    { name: 'city', label: 'City', required: false, max: APPLICATION_LIMITS.cityMax, autocomplete: 'off', country: false, hint: '' },
    { name: 'state', label: 'State / region', required: false, max: APPLICATION_LIMITS.stateMax, autocomplete: 'off', country: false, hint: '' },
    { name: 'country', label: 'Country of residence', required: true, max: 2, autocomplete: 'off', country: true, hint: 'ISO code, e.g. US' },
    { name: 'citizenship', label: 'Citizenship', required: true, max: 2, autocomplete: 'off', country: true, hint: 'ISO code, e.g. US' },
  ] as const;

  protected id(i: number, name: string): string {
    return `${this.prefix}-${i}-${name}`;
  }

  protected error(group: InventorForm, name: string, label: string): string | null {
    const c = group.get(name);
    if (!c || c.valid || !(c.touched || this.submitted())) return null;
    return controlMessage(c, label);
  }

  protected add(): void {
    const arr = this.array();
    if (arr.length >= APPLICATION_LIMITS.inventorsMax) return;
    const prev = arr.at(arr.length - 1)?.getRawValue();
    arr.push(inventorGroup(this.fb, { country: prev?.country || 'US', citizenship: prev?.citizenship || 'US' }));
    arr.markAsDirty();
    setTimeout(() => document.getElementById(this.id(arr.length - 1, 'givenName'))?.focus());
  }

  protected remove(i: number): void {
    const arr = this.array();
    if (arr.length <= APPLICATION_LIMITS.inventorsMin) return;
    arr.removeAt(i);
    arr.markAsDirty();
  }

  protected move(i: number, delta: number): void {
    const arr = this.array();
    const j = i + delta;
    if (j < 0 || j >= arr.length) return;
    const ctrl = arr.at(i);
    arr.removeAt(i);
    arr.insert(j, ctrl);
    arr.markAsDirty();
  }
}
