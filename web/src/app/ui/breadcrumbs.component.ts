import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { ZardBreadcrumbImports } from '@/shared/components/breadcrumb';

export interface Crumb {
  label: string;
  /** Router commands; omit for the current page. */
  link?: string | unknown[];
}

/**
 * Thin wrapper over ZardUI breadcrumb. Kept in its own component (without RouterLink in scope)
 * so the router directive does not also attach to `z-breadcrumb-item[routerLink]`.
 */
@Component({
  selector: 'app-breadcrumbs',
  imports: [...ZardBreadcrumbImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <z-breadcrumb zSize="sm" zLabel="Breadcrumb">
      @for (c of items(); track $index) {
        <z-breadcrumb-item [routerLink]="c.link ?? null">{{ c.label }}</z-breadcrumb-item>
      }
    </z-breadcrumb>
  `,
  host: { class: 'block print:hidden' },
})
export class BreadcrumbsComponent {
  readonly items = input.required<Crumb[]>();
}
