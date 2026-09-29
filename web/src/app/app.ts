import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { ZardDarkMode } from '@/shared/services/dark-mode';

import { ShellComponent } from './layout/shell.component';

@Component({
  selector: 'app-root',
  imports: [ShellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-shell />`,
})
export class App {
  constructor() {
    // Apply the persisted / system theme before first paint of routed content.
    inject(ZardDarkMode).init();
  }
}
