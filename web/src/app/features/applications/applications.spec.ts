import { FormBuilder } from '@angular/forms';
import { TestBed } from '@angular/core/testing';

import { action, applicationDetail, documentResponse } from '../../../testing/test-data';
import { describeLoginError } from '../auth/login.page';
import { biblioGroup, inventorGroup, inventorsArray, toCreateRequest } from './application-form';
import { canDeleteDocument } from './detail/documents-panel.component';
import { guessDocCode } from './detail/upload-panel.component';
import {
  actionErrors,
  buildActionRequest,
  buildSubmitRequest,
  candidateDocuments,
  isValidSignature,
  signatureError,
  submissionChecklist,
  suggestSignature,
} from './detail/workflow-forms';
import { parseStatusList } from './my-applications.page';
import { HttpErrorResponse } from '@angular/common/http';

describe('S-signature (submit dialog)', () => {
  it('accepts /First Last/ per the contract regex', () => {
    expect(isValidSignature('/Ada Lovelace/')).toBe(true);
    expect(isValidSignature('  /Jo/  ')).toBe(true);
    expect(isValidSignature('/José Ñúñez-O’Brien/')).toBe(true);
  });

  it('rejects missing slashes, too-short names, inner slashes and > 100 chars', () => {
    expect(isValidSignature('Ada Lovelace')).toBe(false);
    expect(isValidSignature('/A/')).toBe(false);
    expect(isValidSignature('/Ada/Lovelace/')).toBe(false);
    expect(isValidSignature(`/${'x'.repeat(101)}/`)).toBe(false);
    expect(isValidSignature('//')).toBe(false);
  });

  it('explains what is wrong', () => {
    expect(signatureError('')).toMatch(/required/);
    expect(signatureError('Ada Lovelace')).toMatch(/forward slashes/);
    expect(signatureError('/Ada/Lovelace/')).toMatch(/must not contain/);
    expect(signatureError('/Ada Lovelace/')).toBeNull();
  });

  it('suggests a signature from the display name and builds the submit body', () => {
    expect(suggestSignature('Ada Lovelace')).toBe('/Ada Lovelace/');
    expect(suggestSignature('a/b')).toBe('/ab/');
    expect(buildSubmitRequest(' /Ada Lovelace/ ', 5)).toEqual({ version: 5, certify: true, signature: '/Ada Lovelace/' });
  });
});

describe('workflow action dialog validation', () => {
  it('requires a note when the action says so', () => {
    expect(actionErrors(action({ requiresNote: true }), { note: '  ', artUnit: '', documentIds: [] }).note).toMatch(/required/);
    expect(actionErrors(action({ requiresNote: false, action: 'START_EXAMINATION' }), { note: '', artUnit: '', documentIds: [] })).toEqual({});
  });

  it('validates the optional DOCKET art unit and the note length', () => {
    const docket = action({ action: 'DOCKET', requiresNote: false, acceptsDocuments: false });
    expect(actionErrors(docket, { note: '', artUnit: '21', documentIds: [] }).artUnit).toMatch(/4 digits/);
    expect(actionErrors(docket, { note: '', artUnit: '2100', documentIds: [] })).toEqual({});
    expect(actionErrors(docket, { note: 'x'.repeat(4001), artUnit: '', documentIds: [] }).note).toMatch(/at most/);
  });

  it('buildActionRequest only sends applicable fields plus version', () => {
    const reject = action();
    expect(buildActionRequest(reject, { note: ' Claims 1-5 obvious ', artUnit: '2100', documentIds: ['d1'] }, 9)).toEqual({
      action: 'ISSUE_NON_FINAL_REJECTION',
      note: 'Claims 1-5 obvious',
      documentIds: ['d1'],
      version: 9,
    });
    const claim = action({ action: 'CLAIM', requiresNote: false, acceptsDocuments: false });
    expect(buildActionRequest(claim, { note: '', artUnit: '', documentIds: ['d1'] }, 2)).toEqual({ action: 'CLAIM', version: 2 });
    const docket = action({ action: 'DOCKET', requiresNote: false, acceptsDocuments: false });
    expect(buildActionRequest(docket, { note: '', artUnit: ' 2100 ', documentIds: [] }, 1)).toEqual({ action: 'DOCKET', artUnit: '2100', version: 1 });
  });

  it('offers only uploaded examiner documents, the matching code first', () => {
    const docs = [
      documentResponse({ id: 'misc', docCode: 'EXMISC', source: 'EXAMINER', uploadedAt: '2026-09-03T00:00:00Z' }),
      documentResponse({ id: 'ctnf', docCode: 'CTNF', source: 'EXAMINER', uploadedAt: '2026-09-01T00:00:00Z' }),
      documentResponse({ id: 'pending', docCode: 'CTNF', source: 'EXAMINER', status: 'PENDING_UPLOAD' }),
      documentResponse({ id: 'spec', docCode: 'SPEC', source: 'APPLICANT' }),
    ];
    expect(candidateDocuments(action(), docs).map((d) => d.id)).toEqual(['ctnf', 'misc']);
  });
});

describe('submission checklist', () => {
  it('requires SPEC, CLM and ABST for utility applications', () => {
    const docs = [documentResponse({ docCode: 'SPEC' }), documentResponse({ id: 'd2', docCode: 'CLM' })];
    const c = submissionChecklist('UTILITY', docs);
    expect(c.items.map((i) => [i.code, i.present])).toEqual([
      ['SPEC', true],
      ['CLM', true],
      ['ABST', false],
    ]);
    expect(c.ready).toBe(false);
  });

  it('DESIGN needs SPEC + DRW; pending uploads block submission', () => {
    const docs = [documentResponse({ docCode: 'SPEC' }), documentResponse({ id: 'd2', docCode: 'DRW' })];
    expect(submissionChecklist('DESIGN', docs).ready).toBe(true);
    const withPending = [...docs, documentResponse({ id: 'd3', docCode: 'ADS', status: 'PENDING_UPLOAD' })];
    const c = submissionChecklist('DESIGN', withPending);
    expect(c.blocking.map((d) => d.id)).toEqual(['d3']);
    expect(c.ready).toBe(false);
    expect(submissionChecklist('PROVISIONAL', [documentResponse({ docCode: 'SPEC' })]).ready).toBe(true);
  });
});

describe('document rules', () => {
  it('only the owner may delete — any document of a draft, or unfinished/rejected documents later', () => {
    const draft = applicationDetail({ status: 'DRAFT' });
    const filed = applicationDetail({ status: 'PRE_EXAM' });
    expect(canDeleteDocument(draft, documentResponse(), 'u-1')).toBe(true);
    expect(canDeleteDocument(draft, documentResponse(), 'someone-else')).toBe(false);
    expect(canDeleteDocument(filed, documentResponse({ status: 'UPLOADED' }), 'u-1')).toBe(false);
    expect(canDeleteDocument(filed, documentResponse({ status: 'REJECTED' }), 'u-1')).toBe(true);
  });

  it('guesses document codes from file names within the allowed set', () => {
    expect(guessDocCode('Claims_v2.pdf', ['SPEC', 'CLM'])).toBe('CLM');
    expect(guessDocCode('abstract.pdf', ['SPEC', 'CLM', 'ABST'])).toBe('ABST');
    expect(guessDocCode('response-to-oa.pdf', ['IDS', 'MISC'])).toBeNull();
    expect(guessDocCode('non-final-rejection.pdf', ['CTNF', 'CTFR', 'EXMISC'])).toBe('CTNF');
  });
});

describe('application forms', () => {
  let fb: FormBuilder['nonNullable'];
  beforeEach(() => (fb = TestBed.inject(FormBuilder).nonNullable));

  it('title needs 5–500 non-blank characters', () => {
    const f = biblioGroup(fb);
    f.controls.title.setValue('     ');
    expect(f.controls.title.hasError('required')).toBe(true);
    f.controls.title.setValue('Gear');
    expect(f.controls.title.hasError('minlength')).toBe(true);
    f.controls.title.setValue('Gearbox');
    expect(f.controls.title.valid).toBe(true);
  });

  it('inventor country/citizenship must be 2-letter codes; names required', () => {
    const g = inventorGroup(fb, { givenName: 'Ada', familyName: 'Lovelace', country: 'USA' });
    expect(g.controls.country.hasError('pattern')).toBe(true);
    g.controls.country.setValue('gb');
    expect(g.valid).toBe(true);
    g.controls.familyName.setValue(' ');
    expect(g.valid).toBe(false);
  });

  it('inventorsArray starts with one inventor; toCreateRequest trims, uppercases and nulls blanks', () => {
    expect(inventorsArray(fb).length).toBe(1);
    const req = toCreateRequest(
      { title: ' Gearbox ', applicationType: 'DESIGN', entityStatus: 'MICRO', abstractText: ' ', technologyField: 'Mechanics', attorneyDocketNumber: '' },
      [{ givenName: ' Ada ', familyName: 'Lovelace', city: '', state: ' VA ', country: 'us', citizenship: 'gb' }],
    );
    expect(req).toEqual({
      title: 'Gearbox',
      applicationType: 'DESIGN',
      entityStatus: 'MICRO',
      abstractText: null,
      technologyField: 'Mechanics',
      attorneyDocketNumber: null,
      inventors: [{ givenName: 'Ada', familyName: 'Lovelace', city: null, state: 'VA', country: 'US', citizenship: 'GB' }],
    });
  });

  it('parseStatusList keeps only known statuses from the URL', () => {
    expect(parseStatusList('DRAFT,BOGUS,ISSUED')).toEqual(['DRAFT', 'ISSUED']);
    expect(parseStatusList(undefined)).toEqual([]);
  });
});

describe('login errors', () => {
  it('429 lockout shows the Retry-After wait in minutes', () => {
    const err = new HttpErrorResponse({ status: 429, error: { title: 'Locked', status: 429 }, headers: undefined });
    const withHeader = Object.assign(err, { headers: { get: (n: string) => (n === 'Retry-After' ? '840' : null) } });
    const p = describeLoginError(withHeader);
    expect(p.title).toBe('Account temporarily locked');
    expect(p.detail).toContain('14 minutes');
  });

  it('401 → incorrect email or password', () => {
    expect(describeLoginError(new HttpErrorResponse({ status: 401, error: { title: 'Unauthorized' } })).title).toBe('Incorrect email or password');
  });
});
