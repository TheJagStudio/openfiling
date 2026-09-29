import { formatApplicationNumber, parseApplicationNumber, seriesLabel } from './application-number';
import { daysBetween, describeUserAgent, formatBytes, formatDate, formatRelative, humanize, initialsOf, parseDate } from './format';
import { toHttpParams, toPage } from './http/http-utils';
import { retryAfterSeconds } from './http/problem';
import { toneOf, toneOfApplicationStatus, toneOfCategory } from './status-tone';

describe('format helpers', () => {
  it('formatBytes uses binary units', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(500 * 1024 * 1024)).toBe('500 MB');
    expect(formatBytes(null)).toBe('—');
    expect(formatBytes(-1)).toBe('—');
  });

  it('formats LocalDate values as calendar dates without timezone shift', () => {
    const d = parseDate('2026-09-29')!;
    expect([d.getFullYear(), d.getMonth(), d.getDate()]).toEqual([2026, 8, 29]);
    expect(formatDate('2026-09-29')).toBe('Sep 29, 2026');
    expect(formatDate(null)).toBe('—');
    expect(formatDate('garbage')).toBe('—');
  });

  it('formatRelative describes past and future instants', () => {
    const now = Date.parse('2026-09-29T12:00:00Z');
    expect(formatRelative('2026-09-29T11:59:50Z', now)).toBe('just now');
    expect(formatRelative('2026-09-29T11:55:00Z', now)).toBe('5 minutes ago');
    expect(formatRelative('2026-09-28T12:00:00Z', now)).toBe('yesterday');
    expect(formatRelative('2026-10-02T12:00:00Z', now)).toBe('in 3 days');
  });

  it('humanize keeps acronyms', () => {
    expect(humanize('UNDER_EXAMINATION')).toBe('Under examination');
    expect(humanize('DOCUMENT_UPLOAD_URL_ISSUED')).toBe('Document upload URL issued');
    expect(humanize('CUI_MARKING')).toBe('CUI marking');
  });

  it('daysBetween, initials and user agents', () => {
    expect(daysBetween('2026-09-01', '2026-09-29')).toBe(28);
    expect(initialsOf('Ada Lovelace')).toBe('AL');
    expect(initialsOf('grace@example.org')).toBe('GE');
    expect(describeUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0 Safari/537.36')).toBe('Chrome on Windows');
    expect(describeUserAgent(null)).toBe('Unknown device');
  });
});

describe('application numbers', () => {
  it('parses display, compact and spaced forms to 8 digits', () => {
    expect(parseApplicationNumber('19/000,123')).toBe('19000123');
    expect(parseApplicationNumber('19000123')).toBe('19000123');
    expect(parseApplicationNumber(' 29/000123 ')).toBe('29000123');
    expect(parseApplicationNumber('63 000 001')).toBe('63000001');
  });

  it('rejects anything that is not exactly 8 digits', () => {
    expect(parseApplicationNumber('1900012')).toBeNull();
    expect(parseApplicationNumber('190001234')).toBeNull();
    expect(parseApplicationNumber('US19000123')).toBeNull();
    expect(parseApplicationNumber('')).toBeNull();
    expect(parseApplicationNumber(null)).toBeNull();
  });

  it('formats to the display form and names the series', () => {
    expect(formatApplicationNumber('19000123')).toBe('19/000,123');
    expect(formatApplicationNumber('19/000,123')).toBe('19/000,123');
    expect(formatApplicationNumber(null)).toBe('—');
    expect(seriesLabel('29000001')).toBe('Design');
  });
});

describe('status tones', () => {
  it('tones applications by contract category', () => {
    expect(toneOfCategory('ACTION_REQUIRED')).toBe('warning');
    expect(toneOfCategory('POSITIVE')).toBe('success');
    expect(toneOfCategory('NEGATIVE')).toBe('danger');
    expect(toneOfApplicationStatus('NON_FINAL_REJECTION')).toBe('warning');
    expect(toneOfApplicationStatus('ISSUED')).toBe('success');
    expect(toneOfApplicationStatus('DRAFT')).toBe('neutral');
  });

  it('tones documents and redactions', () => {
    expect(toneOf('PROCESSING')).toBe('progress');
    expect(toneOf('REJECTED')).toBe('danger');
    expect(toneOf('UPLOADED')).toBe('success');
    expect(toneOf('unknown')).toBe('neutral');
  });
});

describe('http utils', () => {
  it('toHttpParams drops blanks and joins arrays with commas', () => {
    const p = toHttpParams({ q: '  ', status: ['DRAFT', 'ISSUED'], page: 0, x: null, y: undefined, flag: false });
    expect(p.has('q')).toBe(false);
    expect(p.get('status')).toBe('DRAFT,ISSUED');
    expect(p.get('page')).toBe('0');
    expect(p.get('flag')).toBe('false');
    expect(p.keys().sort()).toEqual(['flag', 'page', 'status']);
  });

  it('toPage fills in defaults defensively', () => {
    expect(toPage<number>({ content: [1, 2] })).toEqual({ content: [1, 2], page: 0, size: 2, totalElements: 2, totalPages: 1 });
    expect(toPage(null).content).toEqual([]);
  });

  it('retryAfterSeconds handles seconds and HTTP dates', () => {
    expect(retryAfterSeconds('900')).toBe(900);
    expect(retryAfterSeconds(new Date(60_000).toUTCString(), 0)).toBe(60);
    expect(retryAfterSeconds(null)).toBeNull();
  });
});
