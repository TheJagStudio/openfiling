import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCheck, lucideCopy } from '@ng-icons/lucide';

import { ZardButtonComponent, type ZardButtonSizeVariants, type ZardButtonTypeVariants } from '@/shared/components/button';
import { ZardTooltipImports } from '@/shared/components/tooltip';

import { Notifier } from '../core/notify/notifier.service';

/** Copies `value` to the clipboard; confirms visually, via tooltip and via the live toast region. */
@Component({
  selector: 'app-copy-button',
  imports: [ZardButtonComponent, NgIcon, ...ZardTooltipImports],
  viewProviders: [provideIcons({ lucideCopy, lucideCheck })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      z-button
      [zType]="type()"
      [zSize]="label() ? size() : 'icon-sm'"
      [zTooltip]="copied() ? 'Copied!' : tooltip()"
      [attr.aria-label]="label() ? null : tooltip()"
      (click)="copy()"
    >
      <ng-icon [name]="copied() ? 'lucideCheck' : 'lucideCopy'" aria-hidden="true" />
      @if (label()) {
        <span>{{ copied() ? 'Copied' : label() }}</span>
      }
    </button>
  `,
})
export class CopyButtonComponent {
  private readonly notifier = inject(Notifier);

  readonly value = input.required<string>();
  readonly label = input<string>('');
  readonly tooltip = input<string>('Copy to clipboard');
  readonly type = input<ZardButtonTypeVariants>('ghost');
  readonly size = input<ZardButtonSizeVariants>('sm');

  protected readonly copied = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;

  async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.value());
      this.copied.set(true);
      this.notifier.success('Copied to clipboard');
      if (this.timer) clearTimeout(this.timer);
      this.timer = setTimeout(() => this.copied.set(false), 2000);
    } catch {
      this.notifier.error('Could not copy', { description: 'Your browser blocked clipboard access. Select and copy manually.' });
    }
  }
}
