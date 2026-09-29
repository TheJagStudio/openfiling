/** Presentation helpers shared by templates (pure functions, easy to test). */

const ACRONYMS = new Set(['US', 'PDF', 'CUI', 'PII', 'SSN', 'IP', 'API', 'ID', 'IDS', 'ADS', 'NOA', 'S3']);

/** `UNDER_EXAMINATION` → `Under examination`, `DOCUMENT_UPLOAD_URL_ISSUED` → `Document upload URL issued`. */
export function humanize(value: string | null | undefined): string {
  if (!value) return '';
  const words = value.split('_').map((w) => (ACRONYMS.has(w) || w === 'URL' ? w : w.toLowerCase()));
  const first = words[0] ?? '';
  words[0] = ACRONYMS.has(first) || first === 'URL' ? first : first.charAt(0).toUpperCase() + first.slice(1);
  return words.join(' ');
}

/** Binary units (KB = 1024 B), like the upload limit ("500 MB"). */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });
export function formatCompact(n: number | null | undefined): string {
  return n === null || n === undefined || !Number.isFinite(n) ? '—' : compact.format(n);
}

const integer = new Intl.NumberFormat('en-US');
export function formatNumber(n: number | null | undefined): string {
  return n === null || n === undefined || !Number.isFinite(n) ? '—' : integer.format(n);
}

/**
 * Parses an ISO date (`2026-09-29`, interpreted as a *calendar* date in local time — never shifted
 * by the timezone) or instant (`2026-09-29T12:00:00Z`).
 */
export function parseDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d;
}

const dateFmt = new Intl.DateTimeFormat('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
const dateTimeFmt = new Intl.DateTimeFormat('en-US', {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

/** `Sep 29, 2026` */
export function formatDate(value: string | null | undefined): string {
  const d = parseDate(value);
  return d ? dateFmt.format(d) : '—';
}

/** `Sep 29, 2026, 2:05 PM` */
export function formatDateTime(value: string | null | undefined): string {
  const d = parseDate(value);
  return d ? dateTimeFmt.format(d) : '—';
}

const rtf = new Intl.RelativeTimeFormat('en-US', { numeric: 'auto' });
const RELATIVE_STEPS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['second', 60],
  ['minute', 60],
  ['hour', 24],
  ['day', 7],
  ['week', 4.34524],
  ['month', 12],
  ['year', Number.POSITIVE_INFINITY],
];

/** `just now`, `5 minutes ago`, `yesterday`, `in 3 days`… */
export function formatRelative(value: string | null | undefined, now: number = Date.now()): string {
  const d = parseDate(value);
  if (!d) return '—';
  let delta = (d.getTime() - now) / 1000;
  if (Math.abs(delta) < 45) return 'just now';
  for (const [unit, size] of RELATIVE_STEPS) {
    if (Math.abs(delta) < size) return rtf.format(Math.round(delta), unit);
    delta /= size;
  }
  return rtf.format(Math.round(delta), 'year');
}

/** Whole days between two calendar dates / instants (b − a), or null. */
export function daysBetween(a: string | null | undefined, b: string | null | undefined): number | null {
  const da = parseDate(a);
  const db = parseDate(b);
  if (!da || !db) return null;
  const utcA = Date.UTC(da.getFullYear(), da.getMonth(), da.getDate());
  const utcB = Date.UTC(db.getFullYear(), db.getMonth(), db.getDate());
  return Math.round((utcB - utcA) / 86_400_000);
}

/** `1 day`, `12 days` */
export function pluralize(n: number | null | undefined, singular: string, plural = `${singular}s`): string {
  if (n === null || n === undefined) return '—';
  return `${formatNumber(n)} ${n === 1 ? singular : plural}`;
}

/** Initials for avatars: `Ada Lovelace` → `AL`, `grace@example.org` → `GE`. */
export function initialsOf(source: string | null | undefined): string {
  const parts = (source ?? '').split(/[\s@._-]+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts[1]?.[0] ?? '')).toUpperCase() || '?';
}

/** Today in the user's local calendar as `YYYY-MM-DD`. */
export function todayIso(now: Date = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** Short, human description of a browser user-agent string (for the sessions list). */
export function describeUserAgent(ua: string | null | undefined): string {
  if (!ua) return 'Unknown device';
  const browser = /Edg\//.test(ua)
    ? 'Edge'
    : /OPR\//.test(ua)
      ? 'Opera'
      : /Firefox\//.test(ua)
        ? 'Firefox'
        : /Chrome\//.test(ua)
          ? 'Chrome'
          : /Safari\//.test(ua)
            ? 'Safari'
            : /curl|PostmanRuntime|HttpClient|okhttp|Java/i.test(ua)
              ? 'API client'
              : 'Browser';
  const os = /Windows/.test(ua)
    ? 'Windows'
    : /iPhone|iPad/.test(ua)
      ? 'iOS'
      : /Android/.test(ua)
        ? 'Android'
        : /Mac OS X/.test(ua)
          ? 'macOS'
          : /Linux/.test(ua)
            ? 'Linux'
            : '';
  return os ? `${browser} on ${os}` : browser;
}
