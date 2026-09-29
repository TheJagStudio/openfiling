import { type AbstractControl, FormArray, type FormControl, type FormGroup, type NonNullableFormBuilder, type ValidationErrors, type ValidatorFn, Validators } from '@angular/forms';

import {
  APPLICATION_LIMITS as L,
  type ApplicationDetail,
  type ApplicationType,
  type CreateApplicationRequest,
  type EntityStatus,
  type InventorDto,
} from '../../core/models';

export type InventorForm = FormGroup<{
  givenName: FormControl<string>;
  familyName: FormControl<string>;
  city: FormControl<string>;
  state: FormControl<string>;
  country: FormControl<string>;
  citizenship: FormControl<string>;
}>;

export type BiblioForm = FormGroup<{
  title: FormControl<string>;
  applicationType: FormControl<ApplicationType>;
  entityStatus: FormControl<EntityStatus>;
  abstractText: FormControl<string>;
  technologyField: FormControl<string>;
  attorneyDocketNumber: FormControl<string>;
}>;

/** Length checks on the *trimmed* value (the API trims too), so "     " never passes as 5 chars. */
export function trimmedLength(min: number, max: number): ValidatorFn {
  return (c: AbstractControl): ValidationErrors | null => {
    const v = typeof c.value === 'string' ? c.value.trim() : '';
    if (!v.length) return null; // `required` reports emptiness
    if (v.length < min) return { minlength: { requiredLength: min, actualLength: v.length } };
    if (v.length > max) return { maxlength: { requiredLength: max, actualLength: v.length } };
    return null;
  };
}

/** Required that treats whitespace-only as empty. */
export const requiredTrimmed: ValidatorFn = (c) => (typeof c.value === 'string' && c.value.trim() ? null : { required: true });

export const COUNTRY_PATTERN = /^[A-Za-z]{2}$/;

export function inventorGroup(fb: NonNullableFormBuilder, v: Partial<InventorDto> = {}): InventorForm {
  return fb.group({
    givenName: [v.givenName ?? '', [requiredTrimmed, Validators.maxLength(L.nameMax)]],
    familyName: [v.familyName ?? '', [requiredTrimmed, Validators.maxLength(L.nameMax)]],
    city: [v.city ?? '', [Validators.maxLength(L.cityMax)]],
    state: [v.state ?? '', [Validators.maxLength(L.stateMax)]],
    country: [v.country ?? 'US', [requiredTrimmed, Validators.pattern(COUNTRY_PATTERN)]],
    citizenship: [v.citizenship ?? 'US', [requiredTrimmed, Validators.pattern(COUNTRY_PATTERN)]],
  });
}

export function inventorsArray(fb: NonNullableFormBuilder, list: readonly Partial<InventorDto>[] = [{}]): FormArray<InventorForm> {
  return new FormArray<InventorForm>(
    (list.length ? list : [{}]).map((i) => inventorGroup(fb, i)),
    [Validators.minLength(L.inventorsMin), Validators.maxLength(L.inventorsMax)],
  );
}

export function biblioGroup(fb: NonNullableFormBuilder, v: Partial<ApplicationDetail> = {}): BiblioForm {
  return fb.group({
    title: [v.title ?? '', [requiredTrimmed, trimmedLength(L.titleMin, L.titleMax)]],
    applicationType: [v.applicationType ?? ('UTILITY' as ApplicationType), Validators.required],
    entityStatus: [v.entityStatus ?? ('SMALL' as EntityStatus), Validators.required],
    abstractText: [v.abstractText ?? '', [Validators.maxLength(L.abstractMax)]],
    technologyField: [v.technologyField ?? '', [Validators.maxLength(L.technologyFieldMax)]],
    attorneyDocketNumber: [v.attorneyDocketNumber ?? '', [Validators.maxLength(L.docketMax)]],
  });
}

const blankToNull = (s: string | null | undefined): string | null => {
  const t = (s ?? '').trim();
  return t ? t : null;
};

export function toInventorDto(v: InventorForm['value'] | InventorDto): InventorDto {
  return {
    givenName: (v.givenName ?? '').trim(),
    familyName: (v.familyName ?? '').trim(),
    city: blankToNull(v.city),
    state: blankToNull(v.state),
    country: (v.country ?? '').trim().toUpperCase(),
    citizenship: (v.citizenship ?? '').trim().toUpperCase(),
  };
}

export function toCreateRequest(biblio: BiblioForm['value'], inventors: readonly (InventorForm['value'] | InventorDto)[]): CreateApplicationRequest {
  return {
    title: (biblio.title ?? '').trim(),
    applicationType: biblio.applicationType ?? 'UTILITY',
    entityStatus: biblio.entityStatus ?? 'SMALL',
    abstractText: blankToNull(biblio.abstractText),
    technologyField: blankToNull(biblio.technologyField),
    attorneyDocketNumber: blankToNull(biblio.attorneyDocketNumber),
    inventors: inventors.map((i) => toInventorDto(i)),
  };
}

/** The editable part of an existing application, as a create/update request body. */
export function requestFromDetail(app: ApplicationDetail): CreateApplicationRequest {
  return toCreateRequest(
    {
      title: app.title,
      applicationType: app.applicationType,
      entityStatus: app.entityStatus,
      abstractText: app.abstractText ?? '',
      technologyField: app.technologyField ?? '',
      attorneyDocketNumber: app.attorneyDocketNumber ?? '',
    },
    app.inventors,
  );
}

/** Human message for the first error on a control (null when valid or not yet shown). */
export function controlMessage(c: AbstractControl | null | undefined, label: string): string | null {
  if (!c || !c.errors) return null;
  const e = c.errors;
  if (e['required']) return `${label} is required.`;
  if (e['minlength']) return `${label} must be at least ${e['minlength'].requiredLength} characters.`;
  if (e['maxlength']) return `${label} must be at most ${e['maxlength'].requiredLength} characters.`;
  if (e['pattern']) return `${label} must be a 2-letter ISO country code (e.g. US).`;
  return `${label} is invalid.`;
}

/** Common ISO-3166 alpha-2 codes offered as suggestions (any valid 2-letter code is accepted). */
export const COMMON_COUNTRIES: readonly { code: string; name: string }[] = [
  { code: 'US', name: 'United States' },
  { code: 'CA', name: 'Canada' },
  { code: 'MX', name: 'Mexico' },
  { code: 'GB', name: 'United Kingdom' },
  { code: 'DE', name: 'Germany' },
  { code: 'FR', name: 'France' },
  { code: 'IT', name: 'Italy' },
  { code: 'ES', name: 'Spain' },
  { code: 'NL', name: 'Netherlands' },
  { code: 'SE', name: 'Sweden' },
  { code: 'CH', name: 'Switzerland' },
  { code: 'IE', name: 'Ireland' },
  { code: 'IL', name: 'Israel' },
  { code: 'IN', name: 'India' },
  { code: 'CN', name: 'China' },
  { code: 'JP', name: 'Japan' },
  { code: 'KR', name: 'South Korea' },
  { code: 'TW', name: 'Taiwan' },
  { code: 'SG', name: 'Singapore' },
  { code: 'AU', name: 'Australia' },
  { code: 'BR', name: 'Brazil' },
];
