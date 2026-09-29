import { HttpClient, HttpContext } from '@angular/common/http';
import { computed, inject, Injectable } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { catchError, type Observable, of, shareReplay } from 'rxjs';

import { API_BASE_URL, SKIP_ERROR_TOAST } from '../http/http-utils';
import {
  type ApplicationStatus,
  DEFAULT_LIMITS,
  type DocumentCode,
  type DocumentCodeMeta,
  DOCUMENT_CODES,
  type ReferenceData,
  STATUS_META,
  type Visibility,
} from '../models';

/** `GET /api/v1/reference` — fetched once and cached for the app lifetime (a failure allows a later retry). */
@Injectable({ providedIn: 'root' })
export class ReferenceApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${inject(API_BASE_URL)}/api/v1/reference`;
  private cache$: Observable<ReferenceData | null> | null = null;

  load(): Observable<ReferenceData | null> {
    this.cache$ ??= this.http.get<ReferenceData>(this.url, { context: new HttpContext().set(SKIP_ERROR_TOAST, true) }).pipe(
      catchError(() => {
        this.cache$ = null;
        return of(null);
      }),
      shareReplay({ bufferSize: 1, refCount: false }),
    );
    return this.cache$;
  }
}

/**
 * Reference data as signals with contract fallbacks, so forms keep working (with the contract
 * defaults) before — or without — the `/reference` response.
 */
@Injectable({ providedIn: 'root' })
export class ReferenceStore {
  private readonly data = toSignal(inject(ReferenceApi).load(), { initialValue: null });

  readonly loaded = computed(() => this.data() !== null);
  readonly limits = computed(() => ({ ...DEFAULT_LIMITS, ...(this.data()?.limits ?? {}) }));

  readonly documentCodes = computed<DocumentCodeMeta[]>(() => {
    const remote = this.data()?.documentCodes;
    if (!remote?.length) return [...DOCUMENT_CODES];
    return remote.map((d) => ({
      code: d.code,
      label: d.label,
      source: d.source,
      defaultVisibility: d.defaultVisibility,
      description: d.description,
    }));
  });

  private readonly codeIndex = computed(() => new Map(this.documentCodes().map((d) => [d.code, d])));

  docMeta(code: DocumentCode): DocumentCodeMeta | undefined {
    return this.codeIndex().get(code) ?? DOCUMENT_CODES.find((d) => d.code === code);
  }

  docLabel(code: DocumentCode): string {
    return this.docMeta(code)?.label ?? code;
  }

  defaultVisibility(code: DocumentCode): Visibility {
    return this.docMeta(code)?.defaultVisibility ?? 'PUBLIC';
  }

  statusLabel(status: ApplicationStatus): string {
    return this.data()?.statuses?.find((s) => s.code === status)?.label ?? STATUS_META[status]?.label ?? status;
  }
}
