/**
 * Application numbers: 8 digits = 2-digit series code + 6-digit serial (`19000123`), displayed as
 * `19/000,123`. Series `19` utility/plant, `29` design, `63` provisional.
 */

export const APPLICATION_NUMBER_HINT = 'e.g. 19/000,123 or 19000123';

const SERIES_LABELS: Record<string, string> = {
  '19': 'Utility / plant',
  '29': 'Design',
  '63': 'Provisional',
};

/**
 * Normalizes user input to the canonical 8-digit form, or `null` if it is not an application number.
 * Accepts `19/000,123`, `19000123`, `19/000123`, `19 000 123`, `19-000-123` and surrounding spaces.
 */
export function parseApplicationNumber(input: string | null | undefined): string | null {
  if (!input) return null;
  const trimmed = input.trim();
  if (!/^[\d\s/,.-]+$/.test(trimmed)) return null;
  const digits = trimmed.replace(/\D/g, '');
  return digits.length === 8 ? digits : null;
}

/** `19000123` → `19/000,123`; anything else is returned unchanged (or `—` when empty). */
export function formatApplicationNumber(value: string | null | undefined): string {
  if (!value) return '—';
  const digits = parseApplicationNumber(value);
  if (!digits) return value;
  return `${digits.slice(0, 2)}/${digits.slice(2, 5)},${digits.slice(5)}`;
}

export function seriesLabel(value: string | null | undefined): string | null {
  const digits = parseApplicationNumber(value);
  return digits ? (SERIES_LABELS[digits.slice(0, 2)] ?? null) : null;
}
