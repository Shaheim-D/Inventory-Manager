import { Chip } from '@mui/material';
import type { PurchaseOrder, PurchaseOrderStatus } from '../../api/types';

/**
 * Colour carries the same meaning everywhere an order appears, so the mapping
 * lives here rather than being re-decided per screen. Rejected and cancelled are
 * deliberately different: one is a decision someone made about the request, the
 * other is the request being withdrawn.
 */
const STATUS: Record<
  PurchaseOrderStatus,
  { label: string; color: 'default' | 'info' | 'warning' | 'success' | 'error' | 'primary' }
> = {
  DRAFT: { label: 'Draft', color: 'default' },
  SUBMITTED: { label: 'Awaiting approval', color: 'warning' },
  APPROVED: { label: 'Approved', color: 'info' },
  REJECTED: { label: 'Denied', color: 'error' },
  // Stored as ORDERED since V3; "Purchased" is what it is called out loud.
  ORDERED: { label: 'Purchased', color: 'info' },
  PARTIALLY_RECEIVED: { label: 'Partially received', color: 'primary' },
  RECEIVED: { label: 'Received', color: 'success' },
  CANCELLED: { label: 'Cancelled', color: 'default' },
};

export function StatusChip({ status, size = 'small' }: { status: PurchaseOrderStatus; size?: 'small' | 'medium' }) {
  const entry = STATUS[status] ?? { label: status, color: 'default' as const };
  return <Chip size={size} label={entry.label} color={entry.color} variant="outlined" />;
}

export function statusLabel(status: PurchaseOrderStatus): string {
  return STATUS[status]?.label ?? status;
}

/** Every status, in the order an order passes through them. */
export const ALL_STATUSES: PurchaseOrderStatus[] = [
  'DRAFT',
  'SUBMITTED',
  'APPROVED',
  'ORDERED',
  'PARTIALLY_RECEIVED',
  'RECEIVED',
  'REJECTED',
  'CANCELLED',
];

/** An order is only receivable once it has actually been bought. */
export const RECEIVABLE: PurchaseOrderStatus[] = ['ORDERED', 'PARTIALLY_RECEIVED'];

/** Agreed to, but not bought yet — the purchasing queue's contents. */
export const AWAITING_PURCHASE: PurchaseOrderStatus[] = ['APPROVED'];

export function money(value: number | null | undefined): string {
  if (value == null) return '—';
  return Number(value).toLocaleString(undefined, { style: 'currency', currency: 'USD' });
}

export function when(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString() : '—';
}

/** What to call an order in a sentence: its vendor number once it has one. */
export function orderLabel(order: PurchaseOrder): string {
  return order.orderNumber ? order.orderNumber : `Request #${order.id}`;
}
