import type { AppNotification, NotificationFrequency, NotificationTrigger } from '../api/types';

/**
 * What each trigger and frequency is called on screen.
 *
 * <p>Shared between the rules admin and the notification list so a trigger is
 * named the same in the rule that raised it and the notice it produced. The
 * enum values themselves come from the server's vocabulary endpoint — this maps
 * them to prose and nothing else, so adding a trigger backend-side surfaces as
 * a raw enum name rather than disappearing from the picker.
 */
export const TRIGGER_LABELS: Record<NotificationTrigger, string> = {
  WARRANTY_EXPIRATION: 'Warranty coming up for expiry',
  INVENTORY_STALENESS_CHECK: 'Bulk stock overdue for verification',
  PURCHASE_ORDER_SUBMITTED: 'Purchase request submitted',
  PURCHASE_ORDER_APPROVED: 'Purchase request approved',
  PURCHASE_ORDER_DENIED: 'Purchase request denied',
  PURCHASE_ORDER_PURCHASED: 'Purchase order marked purchased',
  PURCHASE_ORDER_PARTIALLY_RECEIVED: 'Purchase order partly received',
  PURCHASE_ORDER_RECEIVED: 'Purchase order fully received',
  PURCHASE_ORDER_CANCELLED: 'Purchase order cancelled',
  ASSET_CREATED: 'Asset created',
  ASSET_LIFECYCLE_CHANGED: 'Asset lifecycle state changed',
  ASSET_ASSIGNED: 'Asset assigned',
  ASSET_DELETED: 'Asset deleted',
  IMPORT_COMPLETED: 'Bulk import finished',
};

/** The chip on a notification: the area it came from, not the whole sentence. */
export const TRIGGER_CHIPS: Record<NotificationTrigger, string> = {
  WARRANTY_EXPIRATION: 'Warranty',
  INVENTORY_STALENESS_CHECK: 'Verification',
  PURCHASE_ORDER_SUBMITTED: 'Purchase request',
  PURCHASE_ORDER_APPROVED: 'Purchase request',
  PURCHASE_ORDER_DENIED: 'Purchase request',
  PURCHASE_ORDER_PURCHASED: 'Purchase order',
  PURCHASE_ORDER_PARTIALLY_RECEIVED: 'Purchase order',
  PURCHASE_ORDER_RECEIVED: 'Purchase order',
  PURCHASE_ORDER_CANCELLED: 'Purchase order',
  ASSET_CREATED: 'Asset',
  ASSET_LIFECYCLE_CHANGED: 'Asset',
  ASSET_ASSIGNED: 'Asset',
  ASSET_DELETED: 'Asset',
  IMPORT_COMPLETED: 'Import',
};

export function triggerLabel(trigger: NotificationTrigger | string): string {
  return TRIGGER_LABELS[trigger as NotificationTrigger] ?? trigger;
}

export function triggerChip(trigger: NotificationTrigger | string): string {
  return TRIGGER_CHIPS[trigger as NotificationTrigger] ?? trigger;
}

/**
 * Fourteen triggers in one flat list is a scroll and a hunt. Grouping them by
 * what they are about turns it into a two-step read: the area, then the event.
 */
export const TRIGGER_GROUPS: { heading: string; triggers: NotificationTrigger[] }[] = [
  {
    heading: 'Purchase orders',
    triggers: [
      'PURCHASE_ORDER_SUBMITTED',
      'PURCHASE_ORDER_APPROVED',
      'PURCHASE_ORDER_DENIED',
      'PURCHASE_ORDER_PURCHASED',
      'PURCHASE_ORDER_PARTIALLY_RECEIVED',
      'PURCHASE_ORDER_RECEIVED',
      'PURCHASE_ORDER_CANCELLED',
    ],
  },
  {
    heading: 'Assets',
    triggers: ['ASSET_CREATED', 'ASSET_LIFECYCLE_CHANGED', 'ASSET_ASSIGNED', 'ASSET_DELETED'],
  },
  { heading: 'Scheduled checks', triggers: ['WARRANTY_EXPIRATION', 'INVENTORY_STALENESS_CHECK'] },
  { heading: 'Imports', triggers: ['IMPORT_COMPLETED'] },
];

/**
 * Whether narrowing the rule to one category means anything for this trigger.
 *
 * <p>A purchase order is not filed under a category and an import spans them,
 * so a category on those rules would not narrow anything — it would stop the
 * rule matching at all, because the event carries no category to compare
 * against. The screen disables the field rather than letting somebody build a
 * rule that silently never fires.
 */
export function triggerHasCategory(trigger: NotificationTrigger | string): boolean {
  return trigger === 'WARRANTY_EXPIRATION'
    || trigger === 'INVENTORY_STALENESS_CHECK'
    || String(trigger).startsWith('ASSET_');
}

export const FREQUENCY_LABELS: Record<NotificationFrequency, string> = {
  IMMEDIATE: 'As it happens',
  HOURLY: 'Hourly summary',
  DAILY: 'Daily summary',
  WEEKLY: 'Weekly summary',
  MONTHLY: 'Monthly summary',
};

export function frequencyLabel(frequency: NotificationFrequency | string): string {
  return FREQUENCY_LABELS[frequency as NotificationFrequency] ?? frequency;
}

/**
 * Where a notification's subject actually lives, when it has one.
 *
 * <p>Shared by the list and the popup so clicking either lands in the same
 * place. `entityType` is a plain string server-side — deliberately, since the
 * row outlives whatever it describes — so an unrecognised one is a link to
 * nowhere rather than a broken route.
 */
export function notificationLink(entry: AppNotification): string | null {
  if (entry.entityType === 'VERIFICATION_QUEUE') return '/verification';
  if (entry.entityId == null) return null;
  if (entry.entityType === 'ASSET') return `/assets/${entry.entityId}`;
  if (entry.entityType === 'PURCHASE_ORDER') return `/purchase-orders/order/${entry.entityId}`;
  return null;
}

/** What the button on a notification should say, given where it goes. */
export function notificationLinkLabel(entry: AppNotification): string {
  if (entry.entityType === 'VERIFICATION_QUEUE') return 'Open the verification queue';
  if (entry.entityType === 'ASSET') return 'Open the asset';
  return 'Open the order';
}
