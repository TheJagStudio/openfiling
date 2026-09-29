import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';

import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCheck, lucideMonitor, lucideMoon, lucideSun } from '@ng-icons/lucide';

import { ZardButtonComponent } from '@/shared/components/button';
import { ZardDropdownImports } from '@/shared/components/dropdown';
import { EDarkModes, ZardDarkMode, type DarkModeOptions } from '@/shared/services/dark-mode';

/** Light / dark / system switcher. Persisted by ZardDarkMode (localStorage `theme`). */
@Component({
  selector: 'app-theme-toggle',
  imports: [ZardButtonComponent, NgIcon, ...ZardDropdownImports],
  viewProviders: [provideIcons({ lucideSun, lucideMoon, lucideMonitor, lucideCheck })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      z-button
      zType="ghost"
      zSize="icon"
      z-dropdown
      [zDropdownMenu]="menu"
      [attr.aria-label]="'Theme: ' + current() + '. Change theme'"
    >
      <ng-icon [name]="mode() === 'dark' ? 'lucideMoon' : 'lucideSun'" aria-hidden="true" />
    </button>
    <z-dropdown-menu-content #menu="zDropdownMenuContent" zAlign="end" class="w-36">
      @for (opt of options; track opt.value) {
        <z-dropdown-menu-item (click)="set(opt.value)">
          <ng-icon [name]="opt.icon" aria-hidden="true" />
          <span class="flex-1">{{ opt.label }}</span>
          @if (current() === opt.value) {
            <ng-icon name="lucideCheck" aria-label="selected" />
          }
        </z-dropdown-menu-item>
      }
    </z-dropdown-menu-content>
  `,
})
export class ThemeToggleComponent {
  private readonly darkMode = inject(ZardDarkMode);
  protected readonly current = this.darkMode.currentTheme;
  protected readonly mode = computed(() => this.darkMode.themeMode());
  protected readonly options: { value: DarkModeOptions; label: string; icon: string }[] = [
    { value: EDarkModes.LIGHT, label: 'Light', icon: 'lucideSun' },
    { value: EDarkModes.DARK, label: 'Dark', icon: 'lucideMoon' },
    { value: EDarkModes.SYSTEM, label: 'System', icon: 'lucideMonitor' },
  ];

  protected set(mode: DarkModeOptions): void {
    this.darkMode.toggleTheme(mode);
  }
}
