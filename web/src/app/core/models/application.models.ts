import type { Role } from './auth.models';
import type { IsoDate, IsoInstant, PageRequest, Uuid } from './common.models';
import type { DocumentCode } from './document.models';

export type ApplicationType = 'UTILITY' | 'DESIGN' | 'PLANT' | 'PROVISIONAL';
export type EntityStatus = 'LARGE' | 'SMALL' | 'MICRO';

export const APPLICATION_TYPES: readonly { code: ApplicationType; label: string; description: string }[] = [
  { code: 'UTILITY', label: 'Utility', description: 'New and useful process, machine, manufacture or composition of matter.' },
  { code: 'DESIGN', label: 'Design', description: 'New, original and ornamental design for an article of manufacture.' },
  { code: 'PLANT', label: 'Plant', description: 'Distinct and new variety of asexually reproduced plant.' },
  { code: 'PROVISIONAL', label: 'Provisional', description: 'Establishes an early filing date; not examined, expires after 12 months.' },
];

export const ENTITY_STATUSES: readonly { code: EntityStatus; label: string; description: string }[] = [
  { code: 'LARGE', label: 'Large entity', description: 'Standard (undiscounted) fees.' },
  { code: 'SMALL', label: 'Small entity', description: 'Qualifying small businesses, universities and non-profits.' },
  { code: 'MICRO', label: 'Micro entity', description: 'Small entities meeting additional income/filing limits.' },
];

export type ApplicationStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'INCOMPLETE'
  | 'PRE_EXAM'
  | 'DOCKETED'
  | 'UNDER_EXAMINATION'
  | 'NON_FINAL_REJECTION'
  | 'RESPONSE_FILED'
  | 'FINAL_REJECTION'
  | 'ALLOWED'
  | 'ISSUED'
  | 'ABANDONED';

export type StatusCategory = 'DRAFT' | 'PENDING' | 'ACTION_REQUIRED' | 'POSITIVE' | 'NEGATIVE';

export interface StatusMeta {
  label: string;
  /** Compact label for charts and chips. */
  short: string;
  category: StatusCategory;
  terminal: boolean;
  /** Plain-language explanation shown on the status tab. */
  explanation: string;
}

/** Contract table "ApplicationStatus (label / category)". `/reference` may override labels at runtime. */
export const STATUS_META: Record<ApplicationStatus, StatusMeta> = {
  DRAFT: {
    label: 'Draft — not yet filed',
    short: 'Draft',
    category: 'DRAFT',
    terminal: false,
    explanation: 'The application has not been filed. Upload the required documents and submit it to receive an application number and filing date.',
  },
  SUBMITTED: {
    label: 'New application — awaiting pre-examination processing',
    short: 'Submitted',
    category: 'PENDING',
    terminal: false,
    explanation: 'The application was received and assigned a filing date. It is waiting for pre-examination processing by the Office.',
  },
  INCOMPLETE: {
    label: 'Notice to file missing parts mailed',
    short: 'Incomplete',
    category: 'ACTION_REQUIRED',
    terminal: false,
    explanation: 'The Office found missing parts. The applicant must upload the missing documents and cure the notice to continue.',
  },
  PRE_EXAM: {
    label: 'Application undergoing pre-exam processing',
    short: 'Pre-exam',
    category: 'PENDING',
    terminal: false,
    explanation: 'Formalities are being reviewed (documents, fees, inventorship) before the case is docketed to an examiner.',
  },
  DOCKETED: {
    label: 'Docketed new case — ready for examination',
    short: 'Docketed',
    category: 'PENDING',
    terminal: false,
    explanation: 'The case is assigned to an art unit and waiting for the examiner to begin substantive examination.',
  },
  UNDER_EXAMINATION: {
    label: 'Under examination',
    short: 'Examination',
    category: 'PENDING',
    terminal: false,
    explanation: 'An examiner is reviewing the claims against prior art and patentability requirements.',
  },
  NON_FINAL_REJECTION: {
    label: 'Non-final rejection mailed',
    short: 'Non-final rej.',
    category: 'ACTION_REQUIRED',
    terminal: false,
    explanation: 'The examiner issued a non-final office action. The applicant should upload a response (RESP) and file it.',
  },
  RESPONSE_FILED: {
    label: 'Response to office action entered',
    short: 'Response filed',
    category: 'PENDING',
    terminal: false,
    explanation: 'The applicant’s response was entered and awaits the examiner’s review.',
  },
  FINAL_REJECTION: {
    label: 'Final rejection mailed',
    short: 'Final rej.',
    category: 'ACTION_REQUIRED',
    terminal: false,
    explanation: 'The examiner issued a final office action. The applicant may file a response or let the application abandon.',
  },
  ALLOWED: {
    label: 'Notice of allowance mailed',
    short: 'Allowed',
    category: 'POSITIVE',
    terminal: false,
    explanation: 'The claims were found allowable. The patent will issue once the Office completes issue processing.',
  },
  ISSUED: {
    label: 'Patented case',
    short: 'Issued',
    category: 'POSITIVE',
    terminal: true,
    explanation: 'A patent was granted. The application and its public documents are part of the public record.',
  },
  ABANDONED: {
    label: 'Abandoned',
    short: 'Abandoned',
    category: 'NEGATIVE',
    terminal: true,
    explanation: 'The application is no longer pending — it was expressly abandoned or not prosecuted.',
  },
};

export const APPLICATION_STATUSES = Object.keys(STATUS_META) as ApplicationStatus[];
export const FILED_STATUSES = APPLICATION_STATUSES.filter((s) => s !== 'DRAFT');

export const CATEGORY_LABELS: Record<StatusCategory, string> = {
  DRAFT: 'Draft',
  PENDING: 'Pending',
  ACTION_REQUIRED: 'Action required',
  POSITIVE: 'Allowed / issued',
  NEGATIVE: 'Abandoned',
};

export type WorkflowAction =
  | 'CLAIM'
  | 'RELEASE'
  | 'START_PRE_EXAM'
  | 'NOTICE_MISSING_PARTS'
  | 'CURE_MISSING_PARTS'
  | 'DOCKET'
  | 'START_EXAMINATION'
  | 'ISSUE_NON_FINAL_REJECTION'
  | 'ISSUE_FINAL_REJECTION'
  | 'FILE_RESPONSE'
  | 'ALLOW'
  | 'ISSUE_PATENT'
  | 'PUBLISH'
  | 'ABANDON';

/** Default labels (the API sends its own `label` in `availableActions`). */
export const WORKFLOW_ACTION_LABELS: Record<WorkflowAction, string> = {
  CLAIM: 'Claim application',
  RELEASE: 'Release to queue',
  START_PRE_EXAM: 'Start pre-exam',
  NOTICE_MISSING_PARTS: 'Notice to file missing parts',
  CURE_MISSING_PARTS: 'Cure missing parts',
  DOCKET: 'Docket case',
  START_EXAMINATION: 'Start examination',
  ISSUE_NON_FINAL_REJECTION: 'Issue non-final rejection',
  ISSUE_FINAL_REJECTION: 'Issue final rejection',
  FILE_RESPONSE: 'File response',
  ALLOW: 'Allow application',
  ISSUE_PATENT: 'Issue patent',
  PUBLISH: 'Publish application',
  ABANDON: 'Abandon application',
};

/** Examiner document codes that naturally accompany an action (used to pre-filter the picker). */
export const ACTION_DOCUMENT_CODES: Partial<Record<WorkflowAction, DocumentCode[]>> = {
  ISSUE_NON_FINAL_REJECTION: ['CTNF', 'EXMISC'],
  ISSUE_FINAL_REJECTION: ['CTFR', 'EXMISC'],
  ALLOW: ['NOA', 'EXMISC'],
  NOTICE_MISSING_PARTS: ['NTMP', 'EXMISC'],
};

export interface PartyRef {
  id: Uuid;
  displayName: string;
}

/** `InventorDto` (request & response). `country`/`citizenship` are ISO-3166 alpha-2. */
export interface InventorDto {
  givenName: string;
  familyName: string;
  city: string | null;
  state: string | null;
  country: string;
  citizenship: string;
}

export interface ApplicationSummary {
  id: Uuid;
  /** 8 digits, e.g. `19000123`; null until filed. */
  applicationNumber: string | null;
  /** e.g. `19/000,123`. */
  applicationNumberDisplay: string | null;
  title: string;
  applicationType: ApplicationType;
  entityStatus: EntityStatus;
  status: ApplicationStatus;
  statusLabel: string;
  statusCategory: StatusCategory;
  filingDate: IsoDate | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  owner: PartyRef;
  examiner: PartyRef | null;
  artUnit: string | null;
  documentCount: number;
  published: boolean;
  daysPending: number | null;
  /** True when the *viewer* has something to do. */
  actionRequired: boolean;
}

export interface ApplicationPermissions {
  canEdit: boolean;
  canDelete: boolean;
  canSubmit: boolean;
  canUpload: boolean;
  uploadableCodes: DocumentCode[];
}

export interface AvailableAction {
  action: WorkflowAction;
  label: string;
  toStatus: ApplicationStatus | null;
  requiresNote: boolean;
  acceptsDocuments: boolean;
  destructive: boolean;
}

export interface ApplicationDetail extends ApplicationSummary {
  confirmationNumber: string | null;
  abstractText: string | null;
  technologyField: string | null;
  attorneyDocketNumber: string | null;
  firstNamedInventor: string | null;
  inventors: InventorDto[];
  submittedAt: IsoInstant | null;
  signature: string | null;
  publicationNumber: string | null;
  publicationDate: IsoDate | null;
  patentNumber: string | null;
  issueDate: IsoDate | null;
  version: number;
  permissions: ApplicationPermissions;
  availableActions: AvailableAction[];
}

export interface CreateApplicationRequest {
  title: string;
  applicationType: ApplicationType;
  entityStatus: EntityStatus;
  abstractText: string | null;
  technologyField: string | null;
  attorneyDocketNumber: string | null;
  inventors: InventorDto[];
}

export interface UpdateApplicationRequest extends CreateApplicationRequest {
  version: number;
}

export interface SubmitApplicationRequest {
  version: number;
  certify: true;
  /** `/First Last/` — must match {@link SIGNATURE_PATTERN}. */
  signature: string;
}

export interface WorkflowActionRequest {
  action: WorkflowAction;
  note?: string;
  artUnit?: string;
  documentIds?: Uuid[];
  version: number;
}

export interface StatusEventResponse {
  id: number;
  /** `CREATED`, `SUBMIT`, `DOCUMENT_UPLOADED`, or a {@link WorkflowAction}. */
  eventCode: string;
  fromStatus: ApplicationStatus | null;
  toStatus: ApplicationStatus | null;
  description: string;
  note: string | null;
  actor: { displayName: string; role: Role | 'SYSTEM' | string } | null;
  createdAt: IsoInstant;
}

export interface ReceiptDocument {
  docCode: DocumentCode;
  docCodeLabel: string;
  fileName: string;
  sizeBytes: number;
  uploadedAt: IsoInstant | null;
}

export interface FilingReceipt {
  applicationNumberDisplay: string;
  confirmationNumber: string;
  title: string;
  applicationType: ApplicationType;
  entityStatus: EntityStatus;
  filingDate: IsoDate;
  submittedAt: IsoInstant;
  signature: string;
  applicant: PartyRef & { email: string };
  inventors: InventorDto[];
  documents: ReceiptDocument[];
}

export interface StatusCount {
  status: ApplicationStatus;
  count: number;
}

export interface DashboardTotals {
  all: number;
  drafts: number;
  pending: number;
  actionRequired: number;
  issued: number;
  abandoned: number;
}

export interface ExaminerDashboard {
  unassigned: number;
  myDocket: number;
  awaitingMyAction: number;
  avgPendencyDays: number | null;
  byStatus: StatusCount[];
}

export interface DashboardResponse {
  totals: DashboardTotals;
  byStatus: StatusCount[];
  actionRequired: ApplicationSummary[];
  recent: ApplicationSummary[];
  examiner: ExaminerDashboard | null;
}

export type ApplicationSortField = 'updatedAt' | 'createdAt' | 'filingDate' | 'title' | 'status';

export interface ApplicationQuery extends PageRequest {
  q?: string;
  /** Comma-separated list, e.g. `DRAFT,INCOMPLETE`. */
  status?: string;
  type?: ApplicationType;
}

/** Contract: signature must match `^/[^/]{2,100}/$`, e.g. `/Ada Lovelace/`. */
export const SIGNATURE_PATTERN = /^\/[^/]{2,100}\/$/;

/** Field limits of `CreateApplicationRequest` (mirrored by the wizard / edit forms). */
export const APPLICATION_LIMITS = {
  titleMin: 5,
  titleMax: 500,
  abstractMax: 5000,
  technologyFieldMax: 200,
  docketMax: 50,
  inventorsMin: 1,
  inventorsMax: 20,
  nameMax: 100,
  cityMax: 100,
  stateMax: 50,
  noteMax: 4000,
} as const;

/** Required document codes for submission, per application type. */
export const REQUIRED_DOCUMENTS: Record<ApplicationType, DocumentCode[]> = {
  UTILITY: ['SPEC', 'CLM', 'ABST'],
  PLANT: ['SPEC', 'CLM', 'ABST'],
  DESIGN: ['SPEC', 'DRW'],
  PROVISIONAL: ['SPEC'],
};
