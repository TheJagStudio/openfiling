import type { PartyRef } from './application.models';
import type { IsoInstant, Uuid } from './common.models';

export type DocumentCode =
  | 'SPEC'
  | 'CLM'
  | 'ABST'
  | 'DRW'
  | 'ADS'
  | 'OATH'
  | 'IDS'
  | 'TRNA'
  | 'RESP'
  | 'MISC'
  | 'CTNF'
  | 'CTFR'
  | 'NOA'
  | 'NTMP'
  | 'EXMISC';

export type Visibility = 'PUBLIC' | 'RESTRICTED';
export type DocumentSource = 'APPLICANT' | 'EXAMINER';
export type DocumentStatus = 'PENDING_UPLOAD' | 'UPLOADED' | 'REJECTED' | 'EXPIRED';
export type RedactionStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export type RedactionCategory =
  | 'PERSON_NAME'
  | 'SSN'
  | 'TAX_ID'
  | 'PHONE'
  | 'EMAIL'
  | 'ADDRESS'
  | 'DATE_OF_BIRTH'
  | 'FINANCIAL_ACCOUNT'
  | 'GOVERNMENT_ID'
  | 'IP_ADDRESS'
  | 'MEDICAL'
  | 'CUI_MARKING'
  | 'EXPORT_CONTROLLED'
  | 'OTHER_SENSITIVE';

export const REDACTION_CATEGORY_LABELS: Record<RedactionCategory, string> = {
  PERSON_NAME: 'Person name',
  SSN: 'SSN',
  TAX_ID: 'Tax ID',
  PHONE: 'Phone',
  EMAIL: 'Email',
  ADDRESS: 'Address',
  DATE_OF_BIRTH: 'Date of birth',
  FINANCIAL_ACCOUNT: 'Financial account',
  GOVERNMENT_ID: 'Government ID',
  IP_ADDRESS: 'IP address',
  MEDICAL: 'Medical',
  CUI_MARKING: 'CUI marking',
  EXPORT_CONTROLLED: 'Export controlled',
  OTHER_SENSITIVE: 'Other sensitive',
};

export interface DocumentCodeMeta {
  code: DocumentCode;
  label: string;
  source: DocumentSource;
  defaultVisibility: Visibility;
  description?: string;
}

/** Contract "DocumentCode (label, source, default visibility)". `/reference` is authoritative at runtime. */
export const DOCUMENT_CODES: readonly DocumentCodeMeta[] = [
  { code: 'SPEC', label: 'Specification', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'CLM', label: 'Claims', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'ABST', label: 'Abstract', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'DRW', label: 'Drawings', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'ADS', label: 'Application Data Sheet', source: 'APPLICANT', defaultVisibility: 'RESTRICTED' },
  { code: 'OATH', label: 'Oath or Declaration', source: 'APPLICANT', defaultVisibility: 'RESTRICTED' },
  { code: 'IDS', label: 'Information Disclosure Statement', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'TRNA', label: 'Transmittal Letter', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'RESP', label: 'Response to Office Action', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'MISC', label: 'Miscellaneous Incoming Letter', source: 'APPLICANT', defaultVisibility: 'PUBLIC' },
  { code: 'CTNF', label: 'Non-Final Rejection', source: 'EXAMINER', defaultVisibility: 'PUBLIC' },
  { code: 'CTFR', label: 'Final Rejection', source: 'EXAMINER', defaultVisibility: 'PUBLIC' },
  { code: 'NOA', label: 'Notice of Allowance', source: 'EXAMINER', defaultVisibility: 'PUBLIC' },
  { code: 'NTMP', label: 'Notice to File Missing Parts', source: 'EXAMINER', defaultVisibility: 'PUBLIC' },
  { code: 'EXMISC', label: 'Examiner Miscellaneous', source: 'EXAMINER', defaultVisibility: 'PUBLIC' },
];

export interface RedactionCategoryCount {
  category: RedactionCategory | string;
  count: number;
}

/** `RedactionResponse` */
export interface RedactionResponse {
  status: RedactionStatus;
  engine: 'CLAUDE+RULES' | 'RULES' | null;
  model: string | null;
  pageCount: number | null;
  redactionCount: number | null;
  categories: RedactionCategoryCount[];
  startedAt: IsoInstant | null;
  completedAt: IsoInstant | null;
  error: string | null;
  publicCopyAvailable: boolean;
}

/** `DocumentResponse` */
export interface DocumentResponse {
  id: Uuid;
  applicationId: Uuid;
  docCode: DocumentCode;
  docCodeLabel: string;
  description: string | null;
  visibility: Visibility;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  status: DocumentStatus;
  source: DocumentSource;
  uploadedBy: PartyRef | null;
  createdAt: IsoInstant;
  uploadedAt: IsoInstant | null;
  rejectionReason: string | null;
  /** null for RESTRICTED documents. */
  redaction: RedactionResponse | null;
}

export interface CreateDocumentRequest {
  docCode: DocumentCode;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  visibility?: Visibility;
  description?: string;
}

/** Pre-signed PUT instruction (TTL 15 min). */
export interface UploadInstruction {
  url: string;
  method: 'PUT';
  headers: Record<string, string>;
  expiresAt: IsoInstant;
}

export interface CreateDocumentResponse {
  document: DocumentResponse;
  upload: UploadInstruction;
}

/** Pre-signed GET (TTL 5 min). */
export interface DownloadResponse {
  url: string;
  expiresAt: IsoInstant;
  fileName: string;
}

export type DownloadCopy = 'original' | 'public';

export function isRedactionActive(doc: Pick<DocumentResponse, 'redaction'> | null | undefined): boolean {
  const s = doc?.redaction?.status;
  return s === 'PENDING' || s === 'PROCESSING';
}
