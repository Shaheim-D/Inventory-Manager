export type ReportEntity = 'ASSET' | 'PURCHASE_ORDER';

/** A report that already exists, as the server describes it. */
export interface CannedReport {
  id: string;
  title: string;
  description: string;
  entity: ReportEntity;
  /** Which filter controls this report understands. */
  filters: string[];
  /** True for the two that count rather than list — they have no field picker. */
  summary: boolean;
}

export interface ReportFieldOption {
  key: string;
  label: string;
  group: string;
}

export interface ReportResult {
  title: string;
  columns: { key: string; label: string }[];
  rows: Record<string, unknown>[];
  truncated: boolean;
}

export interface SavedReport {
  id: number;
  name: string;
  entity: ReportEntity;
  fields: string[];
  filters: Record<string, unknown>;
  createdBy: string | null;
  createdAt: string | null;
}

/** What to run. Exactly one of the three ways of saying it is populated. */
export interface RunRequest {
  reportId?: string;
  savedReportId?: number;
  entity?: ReportEntity;
  fields?: string[];
  filters?: Record<string, unknown>;
}

/** How a cell's value is written, whatever type came back as JSON. */
export function renderCell(value: unknown): string {
  if (value == null || value === '') return '—';
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (Array.isArray(value)) return value.join(', ');
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(value)) {
    return new Date(value).toLocaleString();
  }
  return String(value);
}
