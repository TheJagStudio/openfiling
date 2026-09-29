import { inject, Injectable } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { type RouterStateSnapshot, TitleStrategy } from '@angular/router';

import { environment } from '../../environments/environment';

/** "Page · OpenFiling" for every route with a `title`, the plain product line otherwise. */
@Injectable({ providedIn: 'root' })
export class OpenFilingTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const page = this.buildTitle(snapshot);
    this.title.setTitle(page ? `${page} · ${environment.appName}` : `${environment.appName} — secure patent application filing`);
  }

  /** For pages whose title depends on loaded data (application number, title…). */
  setPageTitle(page: string): void {
    this.title.setTitle(`${page} · ${environment.appName}`);
  }
}
