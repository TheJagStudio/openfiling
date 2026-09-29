import { HttpErrorResponse } from '@angular/common/http';

import type { ProblemDetail } from '../models';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Extracts an RFC 7807 ProblemDetail from any error. Falls back to a synthesized problem for
 * network failures (status 0), non-JSON bodies and non-HTTP errors.
 */
export function toProblem(error: unknown): ProblemDetail {
  // `resource()` wraps non-Error rejections in an Error whose `cause` is the original.
  if (error instanceof Error && !(error instanceof HttpErrorResponse) && error.cause instanceof HttpErrorResponse) {
    return toProblem(error.cause);
  }
  if (error instanceof HttpErrorResponse) {
    let body: unknown = error.error;
    if (typeof body === 'string') {
      try {
        body = JSON.parse(body);
      } catch {
        body = undefined;
      }
    }
    const requestId = error.headers?.get('X-Request-Id') ?? undefined;
    if (isRecord(body) && (typeof body['title'] === 'string' || typeof body['detail'] === 'string')) {
      const problem = body as ProblemDetail;
      return { ...problem, status: problem.status ?? error.status, requestId: problem.requestId ?? requestId };
    }
    if (error.status === 0) {
      return {
        title: 'Service unreachable',
        detail: 'Could not reach the OpenFiling API. Check your connection or try again shortly.',
        status: 0,
      };
    }
    return {
      title: error.statusText || 'Request failed',
      detail: `The server responded with HTTP ${error.status}.`,
      status: error.status,
      requestId,
    };
  }
  if (error instanceof Error) {
    return { title: 'Unexpected error', detail: error.message };
  }
  return { title: 'Unexpected error' };
}

/** Maps validation `errors[]` to `{ field: message }` for inline form display. */
export function fieldErrors(problem: ProblemDetail): Record<string, string> {
  const out: Record<string, string> = {};
  for (const e of problem.errors ?? []) {
    if (!(e.field in out)) out[e.field] = e.message;
  }
  return out;
}

/** Parses `Retry-After` (delta-seconds or HTTP date) into whole seconds. */
export function retryAfterSeconds(header: string | null, now: number = Date.now()): number | null {
  if (!header) return null;
  const seconds = Number(header);
  if (Number.isFinite(seconds)) return Math.max(0, Math.ceil(seconds));
  const date = Date.parse(header);
  if (Number.isNaN(date)) return null;
  return Math.max(0, Math.ceil((date - now) / 1000));
}
