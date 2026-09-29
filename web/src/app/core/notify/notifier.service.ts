import { inject, Injectable } from '@angular/core';

import { ZardSonnerService } from '@/shared/components/sonner';

import type { ProblemDetail } from '../models';

export interface NotifyOptions {
  description?: string;
  /** ms; defaults to the toaster's duration. */
  duration?: number;
}

/**
 * App-level toast facade over ZardUI's sonner. Keeps feature code (and tests) independent of
 * the toast library. The toaster region is `aria-live="polite"`, so every toast is announced.
 */
@Injectable({ providedIn: 'root' })
export class Notifier {
  private readonly sonner = inject(ZardSonnerService);

  success(message: string, options?: NotifyOptions): void {
    this.sonner.success(message, options);
  }

  info(message: string, options?: NotifyOptions): void {
    this.sonner.info(message, options);
  }

  warning(message: string, options?: NotifyOptions): void {
    this.sonner.warning(message, options);
  }

  error(message: string, options?: NotifyOptions): void {
    this.sonner.error(message, options);
  }

  /** Error toast for a ProblemDetail: title, detail and the request id for support. */
  problem(problem: ProblemDetail, options?: NotifyOptions): void {
    const lines = [problem.detail, problem.requestId ? `Request ID: ${problem.requestId}` : undefined].filter(
      (l): l is string => !!l,
    );
    this.sonner.error(problem.title ?? 'Something went wrong', {
      description: lines.join('\n') || undefined,
      duration: options?.duration ?? 6000,
    });
  }
}
