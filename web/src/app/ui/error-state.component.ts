import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideRotateCw } from '@ng-icons/lucide';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardButtonComponent } from '@/shared/components/button';

import { toProblem } from '../core/http/problem';

/** Inline error panel for a failed load: ProblemDetail title/detail, request id and a retry button. */
@Component({
  selector: 'app-error-state',
  imports: [ZardAlertComponent, ZardButtonComponent, NgIcon],
  viewProviders: [provideIcons({ lucideRotateCw })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <z-alert zType="destructive" zIcon="lucideCircleAlert" zRole="alert" [zTitle]="problem().title ?? 'Something went wrong'" [zDescription]="desc" />
    <ng-template #desc>
      <p>{{ problem().detail ?? fallback() }}</p>
      @if (problem().requestId) {
        <p class="text-muted-foreground font-mono text-xs">Request ID: {{ problem().requestId }}</p>
      }
      @if (retryable()) {
        <button type="button" z-button zType="outline" zSize="sm" class="mt-2" (click)="retry.emit()">
          <ng-icon name="lucideRotateCw" aria-hidden="true" /> Try again
        </button>
      }
    </ng-template>
  `,
})
export class ErrorStateComponent {
  readonly error = input.required<unknown>();
  readonly fallback = input('The request could not be completed.');
  readonly retryable = input(true);
  readonly retry = output<void>();

  protected readonly problem = computed(() => toProblem(this.error()));
}
