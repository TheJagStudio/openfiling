import type { ApplicationStatus, ApplicationType, StatusCategory, StatusCount } from './application.models';
import type { Role } from './auth.models';
import type { IsoDate, IsoInstant, PageRequest, Uuid } from './common.models';
import type { DocumentCode, DocumentSource, RedactionCategoryCount, Visibility } from './document.models';

// ---------------------------------------------------------------- examiner

export type AssignedFilter = 'me' | 'unassigned' | 'any';

export type QueueSortField = 'filingDate' | 'updatedAt' | 'applicationNumber' | 'status';

export interface ExaminerQueueQuery extends PageRequest {
  q?: string;
  status?: string;
  assigned?: AssignedFilter;
  artUnit?: string;
  type?: ApplicationType;
  filedFrom?: IsoDate;
  filedTo?: IsoDate;
}

export interface RedactionCounters {
  pending: number;
  processing: number;
  completed: number;
  failed: number;
}

export interface ExaminerStats {
  byStatus: StatusCount[];
  unassigned: number;
  myDocket: number;
  awaitingMyAction: number;
  avgPendencyDays: number | null;
  redactions: RedactionCounters;
}

// ---------------------------------------------------------------- notifications

export interface NotificationResponse {
  id: Uuid;
  type: string;
  title: string;
  message: string;
  applicationId: Uuid | null;
  applicationNumber: string | null;
  read: boolean;
  createdAt: IsoInstant;
}

export interface NotificationQuery extends PageRequest {
  unreadOnly?: boolean;
}

export interface UnreadCount {
  count: number;
}

// ---------------------------------------------------------------- admin

export interface AdminUser {
  id: Uuid;
  email: string;
  displayName: string;
  roles: Role[];
  artUnit: string | null;
  enabled: boolean;
  locked: boolean;
  lastLoginAt: IsoInstant | null;
  createdAt: IsoInstant;
  applicationCount: number;
}

export interface AdminUserQuery extends PageRequest {
  q?: string;
  role?: Role;
}

export interface CreateUserRequest {
  email: string;
  displayName: string;
  password: string;
  roles: Role[];
  artUnit?: string | null;
}

export interface UpdateUserRequest {
  roles?: Role[];
  enabled?: boolean;
  artUnit?: string | null;
  unlock?: boolean;
}

export interface AuditEvent {
  id: number;
  occurredAt: IsoInstant;
  actorId: Uuid | null;
  actorEmail: string | null;
  action: string;
  resourceType: string | null;
  resourceId: string | null;
  ip: string | null;
  userAgent: string | null;
  requestId: string | null;
  details: unknown;
}

export interface AuditQuery extends PageRequest {
  actor?: string;
  action?: string;
  resourceType?: string;
  resourceId?: string;
  /** ISO instant or date. */
  from?: string;
  to?: string;
}

/** Audit actions listed in the contract (the filter also accepts free text). */
export const AUDIT_ACTIONS = [
  'AUTH_LOGIN_SUCCESS',
  'AUTH_LOGIN_FAILED',
  'AUTH_LOCKED',
  'AUTH_REFRESH_REUSE',
  'AUTH_LOGOUT',
  'USER_REGISTERED',
  'PASSWORD_CHANGED',
  'APPLICATION_CREATED',
  'APPLICATION_UPDATED',
  'APPLICATION_DELETED',
  'APPLICATION_SUBMITTED',
  'WORKFLOW_ACTION',
  'DOCUMENT_UPLOAD_URL_ISSUED',
  'DOCUMENT_UPLOADED',
  'DOCUMENT_REJECTED',
  'DOCUMENT_DOWNLOAD',
  'DOCUMENT_DELETED',
  'REDACTION_COMPLETED',
  'REDACTION_FAILED',
  'REDACTION_RETRIED',
  'ADMIN_USER_UPDATED',
  'ADMIN_USER_CREATED',
] as const;

export interface ComponentHealth {
  name: 'database' | 'storage' | 'redaction-lambda' | string;
  status: 'UP' | 'DOWN' | string;
  detail: string | null;
}

export interface SystemStatus {
  components: ComponentHealth[];
  counts: {
    users: number;
    applications: number;
    filedApplications: number;
    documents: number;
    storageBytes: number;
  };
  redactions: RedactionCounters & {
    totalRedactions: number;
    byCategory: RedactionCategoryCount[];
  };
}

// ---------------------------------------------------------------- public

export interface PublicApplicationSummary {
  applicationNumber: string;
  applicationNumberDisplay: string;
  title: string;
  applicationType: ApplicationType;
  status: ApplicationStatus;
  statusLabel: string;
  statusCategory: StatusCategory;
  filingDate: IsoDate | null;
  publicationNumber: string | null;
  publicationDate: IsoDate | null;
  patentNumber: string | null;
  issueDate: IsoDate | null;
  firstNamedInventor: string | null;
  abstractSnippet: string | null;
}

export interface PublicInventor {
  name: string;
  city: string | null;
  state: string | null;
  country: string | null;
}

export interface PublicHistoryEvent {
  eventCode: string;
  description: string;
  toStatus: ApplicationStatus | null;
  createdAt: IsoInstant;
}

export interface PublicDocument {
  id: Uuid;
  docCode: DocumentCode;
  docCodeLabel: string;
  pageCount: number | null;
  redactionCount: number | null;
  uploadedAt: IsoInstant | null;
  publicCopyAvailable: boolean;
}

export interface PublicApplicationDetail extends PublicApplicationSummary {
  abstractText: string | null;
  technologyField: string | null;
  inventors: PublicInventor[];
  examinerName: string | null;
  artUnit: string | null;
  history: PublicHistoryEvent[];
  documents: PublicDocument[];
}

export interface PublicSearchQuery extends PageRequest {
  q?: string;
  type?: ApplicationType;
}

export interface PublicStats {
  applicationsFiled: number;
  published: number;
  issued: number;
  documentsProcessed: number;
  redactionsApplied: number;
}

// ---------------------------------------------------------------- reference

export interface ReferenceData {
  statuses: { code: ApplicationStatus; label: string; category: StatusCategory; terminal: boolean; description: string }[];
  documentCodes: { code: DocumentCode; label: string; source: DocumentSource; defaultVisibility: Visibility; description: string }[];
  applicationTypes: { code: ApplicationType; label: string }[];
  entityStatuses: { code: string; label: string }[];
  limits: {
    maxUploadBytes: number;
    maxDocumentsPerApplication: number;
    allowedContentTypes: string[];
  };
}

/** Contract defaults, used until `/reference` has loaded (or if it fails). */
export const DEFAULT_LIMITS: ReferenceData['limits'] = {
  maxUploadBytes: 500 * 1024 * 1024,
  maxDocumentsPerApplication: 60,
  allowedContentTypes: ['application/pdf'],
};
