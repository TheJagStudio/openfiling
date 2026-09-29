import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal, signal, untracked, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';

import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideBadgeCheck,
  lucideCircleAlert,
  lucideClock,
  lucideExternalLink,
  lucideFileText,
  lucideGavel,
  lucideHandHelping,
  lucideReceipt,
  lucideSend,
  lucideTrash2,
  lucideTriangleAlert,
  lucideUndo2,
  lucideUserCheck,
} from '@ng-icons/lucide';
import { merge } from 'rxjs';

import { ZardAlertComponent } from '@/shared/components/alert';
import { ZardAlertDialogService } from '@/shared/components/alert-dialog';
import { ZardButtonComponent } from '@/shared/components/button';
import { ZardCardImports } from '@/shared/components/card';
import { ZardDialogService } from '@/shared/components/dialog';
import { ZardSkeletonComponent } from '@/shared/components/skeleton';
import { ZardTabGroupComponent, ZardTabsImports } from '@/shared/components/tabs';

import { ApplicationsApi } from '../../../core/api/applications.api';
import { DocumentsApi } from '../../../core/api/documents.api';
import { AuthStore } from '../../../core/auth/auth.store';
import { formatDate, pluralize } from '../../../core/format';
import { toProblem } from '../../../core/http/problem';
import {
  APPLICATION_TYPES,
  type ApplicationDetail,
  type AvailableAction,
  type DocumentResponse,
  ENTITY_STATUSES,
  isRedactionActive,
  STATUS_META,
} from '../../../core/models';
import { Notifier } from '../../../core/notify/notifier.service';
import { toneOfCategory } from '../../../core/status-tone';
import { OpenFilingTitleStrategy } from '../../../core/title.strategy';
import { DirectUploadService } from '../../../core/upload/direct-upload.service';
import { BreadcrumbsComponent } from '../../../ui/breadcrumbs.component';
import { ErrorStateComponent } from '../../../ui/error-state.component';
import { StatusBadgeComponent } from '../../../ui/status-badge.component';
import { ActionDialogComponent, type ActionDialogData } from './action-dialog.component';
import { BiblioTabComponent } from './biblio-tab.component';
import { DocumentsPanelComponent } from './documents-panel.component';
import { InventorsTabComponent } from './inventors-tab.component';
import { StatusTimelineComponent } from './status-timeline.component';
import { SubmitDialogComponent, type SubmitDialogData } from './submit-dialog.component';
import { submissionChecklist } from './workflow-forms';

export const DETAIL_TABS = ['status', 'documents', 'biblio', 'inventors'] as const;
export type DetailTab = (typeof DETAIL_TABS)[number];

const ACTION_ICONS: Record<string, string> = {
  CLAIM: 'lucideUserCheck',
  RELEASE: 'lucideUndo2',
  ABANDON: 'lucideTrash2',
  FILE_RESPONSE: 'lucideSend',
  CURE_MISSING_PARTS: 'lucideSend',
  ALLOW: 'lucideBadgeCheck',
  ISSUE_PATENT: 'lucideBadgeCheck',
  ISSUE_NON_FINAL_REJECTION: 'lucideGavel',
  ISSUE_FINAL_REJECTION: 'lucideGavel',
};

@Component({
  selector: 'app-application-detail',
  imports: [
    RouterLink,
    NgIcon,
    ZardButtonComponent,
    ZardAlertComponent,
    ZardSkeletonComponent,
    ...ZardCardImports,
    ...ZardTabsImports,
    BreadcrumbsComponent,
    ErrorStateComponent,
    StatusBadgeComponent,
    StatusTimelineComponent,
    DocumentsPanelComponent,
    BiblioTabComponent,
    InventorsTabComponent,
  ],
  viewProviders: [
    provideIcons({
      lucideArrowLeft,
      lucideSend,
      lucideTrash2,
      lucideReceipt,
      lucideExternalLink,
      lucideCircleAlert,
      lucideTriangleAlert,
      lucideClock,
      lucideUserCheck,
      lucideUndo2,
      lucideBadgeCheck,
      lucideGavel,
      lucideFileText,
      lucideHandHelping,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './application-detail.page.html',
})
export class ApplicationDetailPage {
  private readonly api = inject(ApplicationsApi);
  private readonly docsApi = inject(DocumentsApi);
  private readonly uploader = inject(DirectUploadService);
  private readonly dialog = inject(ZardDialogService);
  private readonly confirm = inject(ZardAlertDialogService);
  private readonly notifier = inject(Notifier);
  private readonly router = inject(Router);
  private readonly titles = inject(OpenFilingTitleStrategy);
  protected readonly auth = inject(AuthStore);

  /** Route param. */
  readonly id = input.required<string>();
  /** `?tab=status|documents|biblio|inventors` */
  readonly tab = input<string>();

  protected readonly app = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly history = rxResource({
    params: () => (this.app.hasValue() ? { id: this.app.value().id, version: this.app.value().version } : undefined),
    stream: ({ params }) => this.api.history(params.id),
  });
  protected readonly documents = rxResource({ params: () => this.id(), stream: ({ params }) => this.docsApi.list(params) });
  /** Local, patchable copy of the documents list (upload / poll updates are merged in). */
  protected readonly docs = linkedSignal<DocumentResponse[]>(() => (this.documents.hasValue() ? this.documents.value() : []));
  /** Documents whose redaction the upload queue is already polling. */
  private readonly queuePolled = signal<ReadonlySet<string>>(new Set());

  protected readonly notFound = computed(() => {
    const e = this.app.error();
    return !!e && toProblem(e).status === 404;
  });

  protected readonly tabIndex = computed(() => Math.max(0, DETAIL_TABS.indexOf((this.tab() ?? 'status') as DetailTab)));
  private readonly tabGroup = viewChild(ZardTabGroupComponent);

  protected readonly statusMeta = computed(() => (this.app.hasValue() ? STATUS_META[this.app.value().status] : null));
  protected readonly tone = computed(() => (this.app.hasValue() ? toneOfCategory(this.app.value().statusCategory) : 'neutral'));
  protected readonly isOwner = computed(() => this.app.hasValue() && this.app.value().owner?.id === this.auth.user()?.id);
  protected readonly isFiled = computed(() => this.app.hasValue() && !!this.app.value().applicationNumber);
  protected readonly checklist = computed(() => (this.app.hasValue() ? submissionChecklist(this.app.value().applicationType, this.docs()) : null));
  protected readonly processingIds = computed(() =>
    this.docs()
      .filter((d) => isRedactionActive(d) && !this.queuePolled().has(d.id))
      .map((d) => d.id)
      .join(','),
  );

  constructor() {
    // URL → tab.
    effect(() => {
      const group = this.tabGroup();
      const idx = this.tabIndex();
      if (group && untracked(() => group.activeTabIndex()) !== idx) group.selectTabByIndex(idx);
    });

    // Page title (re-applied after `?tab=` navigations, which reset the route title).
    effect(() => {
      this.tab();
      if (!this.app.hasValue()) return;
      const a = this.app.value();
      this.titles.setPageTitle(a.applicationNumberDisplay ? `${a.applicationNumberDisplay} — ${a.title}` : `Draft — ${a.title}`);
    });

    // Poll GET /documents/{id} every 3 s for documents whose redaction is still running.
    effect((onCleanup) => {
      const ids = this.processingIds();
      if (!ids) return;
      const sub = merge(...ids.split(',').map((id) => this.uploader.pollDocument(id))).subscribe((d) => this.patchDocument(d));
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected onTabChange(index: number): void {
    const key = DETAIL_TABS[index] ?? 'status';
    if (key === (this.tab() ?? 'status')) return;
    void this.router.navigate([], { queryParams: { tab: key === 'status' ? null : key }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  protected typeLabel(code: string): string {
    return APPLICATION_TYPES.find((t) => t.code === code)?.label ?? code;
  }

  protected entityLabel(code: string): string {
    return ENTITY_STATUSES.find((t) => t.code === code)?.label ?? code;
  }

  protected date(v: string | null): string {
    return formatDate(v);
  }

  protected days(n: number | null): string {
    return n === null ? '—' : pluralize(n, 'day');
  }

  protected actionIcon(a: AvailableAction): string {
    return ACTION_ICONS[a.action] ?? 'lucideHandHelping';
  }

  /** Merges a server update of one document into the local list (adds it when new). */
  protected patchDocument(doc: DocumentResponse): void {
    this.docs.update((list) => {
      const i = list.findIndex((d) => d.id === doc.id);
      if (i === -1) return [doc, ...list];
      const copy = [...list];
      copy[i] = doc;
      return copy;
    });
  }

  protected onUploadedDocument(doc: DocumentResponse): void {
    this.queuePolled.update((s) => {
      const next = new Set(s);
      if (isRedactionActive(doc)) next.add(doc.id);
      else next.delete(doc.id);
      return next;
    });
    this.patchDocument(doc);
    if (doc.status === 'UPLOADED' && !isRedactionActive(doc)) {
      // documentCount / permissions may have changed.
      this.app.reload();
    }
  }

  protected onDocumentRemoved(id: string): void {
    this.docs.update((list) => list.filter((d) => d.id !== id));
    this.app.reload();
  }

  protected onUpdated(updated: ApplicationDetail): void {
    this.app.set(updated);
    this.documents.reload();
  }

  protected reloadAll(): void {
    this.app.reload();
    this.documents.reload();
  }

  protected openAction(action: AvailableAction): void {
    if (!this.app.hasValue()) return;
    const app = this.app.value();
    this.dialog.create<ActionDialogComponent, ActionDialogData>({
      zTitle: action.label,
      zDescription: app.applicationNumberDisplay ? `Application ${app.applicationNumberDisplay} — ${app.title}` : app.title,
      zContent: ActionDialogComponent,
      zData: {
        application: app,
        action,
        documents: this.docs(),
        onDone: (updated) => (updated.version === app.version ? this.reloadAll() : this.onUpdated(updated)),
      },
      zHideFooter: true,
      zCustomClasses: 'sm:max-w-xl',
    });
  }

  protected openSubmit(): void {
    if (!this.app.hasValue()) return;
    const app = this.app.value();
    this.dialog.create<SubmitDialogComponent, SubmitDialogData>({
      zTitle: 'Submit application',
      zDescription: 'Review the checklist, certify and sign electronically.',
      zContent: SubmitDialogComponent,
      zData: {
        application: app,
        documents: this.docs(),
        onDone: (updated) => (updated.version === app.version ? this.reloadAll() : this.onUpdated(updated)),
      },
      zHideFooter: true,
      zCustomClasses: 'sm:max-w-xl',
    });
  }

  protected deleteDraft(): void {
    if (!this.app.hasValue()) return;
    const app = this.app.value();
    this.confirm.create({
      zTitle: 'Delete this draft?',
      zDescription: `“${app.title}” and all of its uploaded documents will be permanently deleted. This cannot be undone.`,
      zOkText: 'Delete draft',
      zOkDestructive: true,
      zCancelText: 'Keep draft',
      zOnOk: () => {
        this.api.remove(app.id).subscribe(() => {
          this.notifier.success('Draft deleted', { description: app.title });
          void this.router.navigateByUrl('/applications');
        });
      },
    });
  }
}
