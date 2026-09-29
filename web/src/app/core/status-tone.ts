/**
 * Maps every domain status to a semantic tone. The UI renders tones consistently
 * (`<app-status-badge>`), so "green = good" means the same thing on every page.
 */
import { STATUS_META, type ApplicationStatus, type StatusCategory } from './models';

export type Tone = 'success' | 'info' | 'warning' | 'danger' | 'neutral' | 'progress';

/** Application tone is driven by the contract `statusCategory`. */
export const CATEGORY_TONES: Record<StatusCategory, Tone> = {
  DRAFT: 'neutral',
  PENDING: 'info',
  ACTION_REQUIRED: 'warning',
  POSITIVE: 'success',
  NEGATIVE: 'danger',
};

export function toneOfCategory(category: StatusCategory | string | null | undefined): Tone {
  return (category && CATEGORY_TONES[category as StatusCategory]) || 'neutral';
}

/** Tone of an application status via its category (falls back to the generic table). */
export function toneOfApplicationStatus(status: ApplicationStatus | string | null | undefined): Tone {
  const meta = status ? STATUS_META[status as ApplicationStatus] : undefined;
  return meta ? CATEGORY_TONES[meta.category] : toneOf(status);
}

const TONES: Record<string, Tone> = {
  // documents
  PENDING_UPLOAD: 'neutral',
  UPLOADED: 'success',
  REJECTED: 'danger',
  EXPIRED: 'neutral',
  // redaction
  PENDING: 'neutral',
  PROCESSING: 'progress',
  COMPLETED: 'success',
  FAILED: 'danger',
  // visibility
  PUBLIC: 'info',
  RESTRICTED: 'warning',
  // components
  UP: 'success',
  DOWN: 'danger',
};

export function toneOf(status: string | null | undefined): Tone {
  if (!status) return 'neutral';
  const key = status.toUpperCase();
  const app = STATUS_META[key as ApplicationStatus];
  if (app) return CATEGORY_TONES[app.category];
  return TONES[key] ?? 'neutral';
}

/** Tailwind classes per tone — tuned for AA contrast in light and dark themes. */
export const TONE_CLASSES: Record<Tone, string> = {
  success: 'bg-emerald-50 text-emerald-800 ring-emerald-600/20 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-400/25',
  info: 'bg-sky-50 text-sky-800 ring-sky-600/20 dark:bg-sky-500/10 dark:text-sky-300 dark:ring-sky-400/25',
  warning: 'bg-amber-50 text-amber-900 ring-amber-600/25 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-400/25',
  danger: 'bg-red-50 text-red-800 ring-red-600/20 dark:bg-red-500/10 dark:text-red-300 dark:ring-red-400/25',
  neutral: 'bg-muted text-muted-foreground ring-border',
  progress: 'bg-violet-50 text-violet-800 ring-violet-600/20 dark:bg-violet-500/10 dark:text-violet-300 dark:ring-violet-400/25',
};

export const TONE_DOT: Record<Tone, string> = {
  success: 'bg-emerald-500',
  info: 'bg-sky-500',
  warning: 'bg-amber-500',
  danger: 'bg-red-500',
  neutral: 'bg-muted-foreground/60',
  progress: 'bg-violet-500 animate-pulse',
};

/** Solid accent (timeline markers, bars). */
export const TONE_SOLID: Record<Tone, string> = {
  success: 'bg-emerald-600 dark:bg-emerald-400',
  info: 'bg-sky-600 dark:bg-sky-400',
  warning: 'bg-amber-500 dark:bg-amber-400',
  danger: 'bg-red-600 dark:bg-red-400',
  neutral: 'bg-muted-foreground/60',
  progress: 'bg-violet-600 dark:bg-violet-400',
};
