import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowLeft, lucideScanSearch } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink, NgIcon, ZardButtonComponent],
  viewProviders: [provideIcons({ lucideArrowLeft, lucideScanSearch })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mx-auto flex max-w-xl flex-col items-center px-4 py-24 text-center">
      <p class="text-primary font-mono text-sm font-semibold">Error 404</p>
      <h1 class="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">Page not found</h1>
      <p class="text-muted-foreground mt-3">
        We couldn’t find <code class="bg-muted rounded px-1.5 py-0.5 font-mono text-sm break-all">{{ path }}</code>. It may have moved, or the link is
        mistyped.
      </p>
      <div class="mt-8 flex flex-wrap justify-center gap-2">
        <a z-button routerLink="/"><ng-icon name="lucideArrowLeft" aria-hidden="true" /> Back home</a>
        <a z-button zType="outline" routerLink="/status"><ng-icon name="lucideScanSearch" aria-hidden="true" /> Look up an application</a>
      </div>
    </div>
  `,
})
export class NotFoundPage {
  protected readonly path = inject(Router).url;
}
