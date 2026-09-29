/** ISO-8601 calendar date, e.g. `"2025-01-19"` (Java `LocalDate`). */
export type IsoDate = string;
/** ISO-8601 UTC instant, e.g. `"2025-01-19T10:15:30Z"` (Java `Instant`). */
export type IsoInstant = string;
/** UUID string. */
export type Uuid = string;

/** Pagination envelope returned by every list endpoint (own record, not Spring's `Page`). */
export interface Page<T> {
  content: T[];
  /** 0-based page index. */
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Common paging query parameters. */
export interface PageRequest {
  /** 0-based. */
  page?: number;
  /** 1–100. */
  size?: number;
  /** `field,asc|desc`. */
  sort?: string;
}

/** `{ value, count }` bucket used by facets and stats. */
export interface ValueCount<V = string> {
  value: V;
  count: number;
}

export interface FieldError {
  field: string;
  message: string;
}

/** RFC 7807 `application/problem+json` body shared by every service. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  requestId?: string;
  /** Present only for validation failures. */
  errors?: FieldError[];
}

export type SortDirection = 'asc' | 'desc';

export function emptyPage<T>(size = 20): Page<T> {
  return { content: [], page: 0, size, totalElements: 0, totalPages: 0 };
}
