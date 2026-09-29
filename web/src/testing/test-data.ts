/* Shared fixtures for unit tests (not part of the application bundle — only specs import this). */
import type { ApplicationDetail, AuthResponse, AvailableAction, DocumentResponse, Role } from '../app/core/models';

export function authResponse(roles: Role[] = ['INVENTOR'], overrides: Partial<AuthResponse> = {}): AuthResponse {
  return {
    accessToken: 'access-1',
    expiresIn: 900,
    refreshToken: 'refresh-1',
    refreshExpiresIn: 604800,
    tokenType: 'Bearer',
    user: {
      id: 'u-1',
      email: 'ada@openfiling.local',
      displayName: 'Ada Lovelace',
      roles,
      artUnit: roles.includes('EXAMINER') ? '2100' : null,
      createdAt: '2026-01-01T00:00:00Z',
      lastLoginAt: null,
    },
    ...overrides,
  };
}

export function documentResponse(overrides: Partial<DocumentResponse> = {}): DocumentResponse {
  return {
    id: 'd-1',
    applicationId: 'a-1',
    docCode: 'SPEC',
    docCodeLabel: 'Specification',
    description: null,
    visibility: 'PUBLIC',
    fileName: 'spec.pdf',
    contentType: 'application/pdf',
    sizeBytes: 1024,
    status: 'UPLOADED',
    source: 'APPLICANT',
    uploadedBy: { id: 'u-1', displayName: 'Ada Lovelace' },
    createdAt: '2026-09-01T10:00:00Z',
    uploadedAt: '2026-09-01T10:01:00Z',
    rejectionReason: null,
    redaction: null,
    ...overrides,
  };
}

export function action(overrides: Partial<AvailableAction> = {}): AvailableAction {
  return {
    action: 'ISSUE_NON_FINAL_REJECTION',
    label: 'Issue non-final rejection',
    toStatus: 'NON_FINAL_REJECTION',
    requiresNote: true,
    acceptsDocuments: true,
    destructive: false,
    ...overrides,
  };
}

export function applicationDetail(overrides: Partial<ApplicationDetail> = {}): ApplicationDetail {
  return {
    id: 'a-1',
    applicationNumber: null,
    applicationNumberDisplay: null,
    title: 'Self-healing solid electrolyte',
    applicationType: 'UTILITY',
    entityStatus: 'SMALL',
    status: 'DRAFT',
    statusLabel: 'Draft — not yet filed',
    statusCategory: 'DRAFT',
    filingDate: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-01T10:00:00Z',
    owner: { id: 'u-1', displayName: 'Ada Lovelace' },
    examiner: null,
    artUnit: null,
    documentCount: 0,
    published: false,
    daysPending: null,
    actionRequired: false,
    confirmationNumber: null,
    abstractText: null,
    technologyField: null,
    attorneyDocketNumber: null,
    firstNamedInventor: 'Ada Lovelace',
    inventors: [{ givenName: 'Ada', familyName: 'Lovelace', city: 'London', state: null, country: 'GB', citizenship: 'GB' }],
    submittedAt: null,
    signature: null,
    publicationNumber: null,
    publicationDate: null,
    patentNumber: null,
    issueDate: null,
    version: 3,
    permissions: { canEdit: true, canDelete: true, canSubmit: true, canUpload: true, uploadableCodes: ['SPEC', 'CLM', 'ABST'] },
    availableActions: [],
    ...overrides,
  };
}

/** A File whose bytes start with the given header (default: a valid PDF header). */
export function pdfFile(name = 'spec.pdf', content = '%PDF-1.7\n%âãÏÓ\n', type = 'application/pdf'): File {
  return new File([content], name, { type });
}
