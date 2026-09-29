import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** OpenFiling mark: a filed document with a seal-gold verification stamp. Decorative (aria-hidden). */
@Component({
  selector: 'app-brand-mark',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 32 32" [attr.width]="size()" [attr.height]="size()" aria-hidden="true" focusable="false" class="block">
      <rect width="32" height="32" rx="7" class="fill-navy dark:fill-primary/20" />
      <path d="M10 7.5h8.5l4.5 4.5v12.5a1 1 0 0 1-1 1H10a1 1 0 0 1-1-1v-16a1 1 0 0 1 1-1z" fill="#fff" />
      <path d="M18.5 7.5V12H23" fill="none" stroke="#c9d2ff" stroke-width="1.2" stroke-linejoin="round" />
      <circle cx="16" cy="19" r="3.6" class="fill-seal" />
      <path d="m14.4 19 1.1 1.1 2.1-2.2" fill="none" stroke="#1f2a6b" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
    </svg>
  `,
  host: { class: 'inline-block shrink-0' },
})
export class BrandMarkComponent {
  readonly size = input(28);
}
