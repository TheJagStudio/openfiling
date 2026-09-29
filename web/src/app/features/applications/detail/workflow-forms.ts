import {
  ACTION_DOCUMENT_CODES,
  APPLICATION_LIMITS,
  type ApplicationType,
  type AvailableAction,
  type DocumentCode,
  type DocumentResponse,
  REQUIRED_DOCUMENTS,
  SIGNATURE_PATTERN,
  type SubmitApplicationRequest,
  type WorkflowActionRequest,
} from '../../../core/models';

export interface ActionFormValue {
  note: string;
  artUnit: string;
  documentIds: string[];
}

export type ActionFieldErrors = Partial<Record<'note' | 'artUnit', string>>;

/** Art units are 4-digit codes, e.g. `2100`. */
export const ART_UNIT_PATTERN = /^\d{4}$/;

/** Client-side validation of a workflow action dialog (mirrors the contract rules). */
export function actionErrors(action: Pick<AvailableAction, 'action' | 'requiresNote'>, v: ActionFormValue): ActionFieldErrors {
  const errors: ActionFieldErrors = {};
  const note = v.note.trim();
  if (action.requiresNote && !note) {
    errors.note = 'A note is required for this action. It is recorded in the prosecution history and sent to the other party.';
  } else if (note.length > APPLICATION_LIMITS.noteMax) {
    errors.note = `The note must be at most ${APPLICATION_LIMITS.noteMax} characters.`;
  }
  const artUnit = v.artUnit.trim();
  if (action.action === 'DOCKET' && artUnit && !ART_UNIT_PATTERN.test(artUnit)) {
    errors.artUnit = 'Art unit must be 4 digits, e.g. 2100.';
  }
  return errors;
}

/** Request body for `POST /applications/{id}/actions` — only sends the fields that apply. */
export function buildActionRequest(action: AvailableAction, v: ActionFormValue, version: number): WorkflowActionRequest {
  const body: WorkflowActionRequest = { action: action.action, version };
  const note = v.note.trim();
  if (note) body.note = note;
  const artUnit = v.artUnit.trim();
  if (action.action === 'DOCKET' && artUnit) body.artUnit = artUnit;
  if (action.acceptsDocuments && v.documentIds.length) body.documentIds = [...v.documentIds];
  return body;
}

/**
 * Examiner documents that may be attached to an action: uploaded, examiner-sourced; the codes that
 * naturally belong to the action (e.g. CTNF for a non-final rejection) are listed first.
 */
export function candidateDocuments(action: AvailableAction, docs: readonly DocumentResponse[]): DocumentResponse[] {
  const preferred = ACTION_DOCUMENT_CODES[action.action] ?? [];
  const eligible = docs.filter((d) => d.source === 'EXAMINER' && d.status === 'UPLOADED');
  const rank = (d: DocumentResponse) => {
    const i = preferred.indexOf(d.docCode);
    return i === -1 ? preferred.length : i;
  };
  return [...eligible].sort((a, b) => rank(a) - rank(b) || (b.uploadedAt ?? '').localeCompare(a.uploadedAt ?? ''));
}

// ---------------------------------------------------------------- submission

export function isValidSignature(value: string | null | undefined): boolean {
  return SIGNATURE_PATTERN.test((value ?? '').trim());
}

/** Error for the S-signature field, or null when valid. */
export function signatureError(value: string | null | undefined): string | null {
  const v = (value ?? '').trim();
  if (!v) return 'Your electronic signature is required.';
  if (!v.startsWith('/') || !v.endsWith('/') || v.length < 2) return 'Enclose your name in forward slashes, e.g. /Ada Lovelace/.';
  if (!isValidSignature(v)) return 'The name between the slashes must be 2–100 characters and must not contain “/”.';
  return null;
}

/** `Ada Lovelace` → `/Ada Lovelace/` (a suggestion; the signer must type/confirm it). */
export function suggestSignature(displayName: string | null | undefined): string {
  const name = (displayName ?? '').replace(/\//g, '').trim();
  return name ? `/${name}/` : '';
}

export function buildSubmitRequest(signature: string, version: number): SubmitApplicationRequest {
  return { version, certify: true, signature: signature.trim() };
}

export interface ChecklistItem {
  code: DocumentCode;
  label: string;
  present: boolean;
}

export interface SubmissionChecklist {
  items: ChecklistItem[];
  /** Documents that are not UPLOADED (pending, rejected, expired) — they block submission. */
  blocking: DocumentResponse[];
  ready: boolean;
}

/** Required-documents checklist for submission: UTILITY/PLANT SPEC+CLM+ABST, DESIGN SPEC+DRW, PROVISIONAL SPEC. */
export function submissionChecklist(
  type: ApplicationType,
  docs: readonly DocumentResponse[],
  label: (code: DocumentCode) => string = (c) => c,
): SubmissionChecklist {
  const uploaded = new Set(docs.filter((d) => d.status === 'UPLOADED').map((d) => d.docCode));
  const items = REQUIRED_DOCUMENTS[type].map((code) => ({ code, label: label(code), present: uploaded.has(code) }));
  const blocking = docs.filter((d) => d.status === 'PENDING_UPLOAD' || d.status === 'REJECTED');
  return { items, blocking, ready: items.every((i) => i.present) && blocking.length === 0 };
}
