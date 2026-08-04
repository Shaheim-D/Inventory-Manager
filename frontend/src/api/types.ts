export interface CurrentUser {
  id: number;
  username: string;
  authProvider: 'LOCAL' | 'LDAP' | 'ACTIVE_DIRECTORY';
  mustChangePassword: boolean;
  roles: string[];
  permissions: string[];
}

/**
 * An asset as the API chose to send it. Restricted fields are genuinely absent
 * rather than null, so every gateable field is optional here — the type mirrors
 * the wire contract instead of pretending the shape is fixed.
 */
export interface Asset {
  id: number;
  displayLabel: string;
  name: string | null;
  categoryId: number;
  categoryName: string;
  serialized: boolean;
  locationId: number;
  locationName: string;
  lifecycleStateId: number;
  lifecycleStateName: string;
  manufacturer: string | null;
  model: string | null;
  serialNumber: string | null;
  assetTag: string | null;
  macAddresses: string[] | null;
  managementIp: string | null;
  hostname: string | null;
  firmwareVersion: string | null;
  softwareVersion: string | null;
  deviceRole: string | null;
  purchaseDate: string | null;
  vendor: string | null;
  warrantyStart: string | null;
  warrantyExpiration: string | null;
  licenseInformation: string | null;
  condition: string | null;
  status: string | null;
  customerName: string | null;
  customerAddress: string | null;
  notes: string | null;
  assigneeType: 'NONE' | 'USER' | 'EMPLOYEE' | 'CUSTOMER';
  /** Resolved name whichever way the assignment was recorded. */
  assigneeDisplay?: string | null;
  warrantyTermMonths: number | null;
  subcategories: { id: number; name: string }[];
  /** Core columns this category uses, and what to call them here. */
  applicableCoreFields: string[];
  coreFieldLabels: Record<string, string>;
  quantity: number;
  purchaseOrderId: number | null;
  lastVerifiedAt: string | null;
  lastVerifiedBy: number | null;
  version: number;
  createdAt: string | null;
  updatedAt: string | null;
  customFields: Record<string, unknown>;
  /** Core field names the server withheld — for layout only, never re-derived. */
  hiddenFields: string[];

  // Present only when the viewer is permitted to see them.
  purchasePrice?: number | null;
  purchaseLink?: string | null;
  invoiceNumber?: string | null;
  assigneeText?: string | null;
  assigneeUserId?: number | null;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface Category {
  id: number;
  name: string;
  description: string | null;
  serialized: boolean;
  verificationIntervalDays: number | null;
  /** Core columns this category actually uses — a Vehicle has no firmware version. */
  applicableCoreFields: string[];
}

export interface LocationTypeOption {
  id: number;
  name: string;
  sortOrder: number;
  active: boolean;
}

export interface DeviceModel {
  id: number;
  categoryId: number | null;
  categoryName: string | null;
  manufacturer: string;
  model: string;
  deviceRole: string | null;
  defaultPrice: number | null;
  notes: string | null;
  active: boolean;
}

export interface CoreFieldConfig {
  applicable: string[];
  configurable: string[];
  labels: Record<string, string>;
}

export interface TransitionOptions {
  suggested: { id: number; name: string }[];
  all: { id: number; name: string; suggested: boolean }[];
}

export interface CustomFieldDefinition {
  id: number;
  categoryId: number;
  fieldName: string;
  fieldType: 'TEXT' | 'NUMBER' | 'DATE' | 'BOOLEAN' | 'ENUM';
  required: boolean;
  sortOrder: number;
  enumOptions: string[] | null;
}

export interface Location {
  id: number;
  name: string;
  parentLocationId: number | null;
  locationTypeId: number;
  locationTypeName: string;
  ownershipType: string;
  ownershipOtherDescription: string | null;
  addressLine1: string | null;
  city: string | null;
  state: string | null;
  zip: string | null;
  active: boolean;
}

export interface LifecycleState {
  id: number;
  name: string;
}

export interface AuditEvent {
  id: number;
  entityType: string;
  entityId: number;
  userId: number | null;
  /** Resolved server-side. "system" for plugin and scheduled writes. */
  username: string;
  occurredAt: string;
  action: string;
  fieldName: string | null;
  previousValue: string | null;
  newValue: string | null;
  reason: string | null;
}

export interface Role {
  id: number;
  name: string;
  permissionIds: number[];
  permissionKeys: string[];
}

export interface Permission {
  id: number;
  permissionKey: string;
  description: string;
}

export interface UserSummary {
  id: number;
  username: string;
  email: string | null;
  authProvider: string;
  active: boolean;
  locked: boolean;
  mustChangePassword: boolean;
  lastLoginAt: string | null;
  roles: { id: number; name: string }[];
}

export interface FieldVisibilityRule {
  id: number;
  entityType: string;
  coreFieldName: string | null;
  customFieldDefinitionId: number | null;
  customFieldName: string | null;
  requiredPermissionId: number;
  requiredPermissionKey: string;
  assetCategoryId: number | null;
  assetCategoryName: string | null;
  scope: 'GLOBAL' | 'CATEGORY';
}

export interface Branding {
  organizationName: string | null;
  primaryColor: string | null;
  secondaryColor: string | null;
  hasLogo: boolean;
  logoFilename: string | null;
  logoUpdatedAt: string | null;
}

export interface AssignableUser {
  id: number;
  username: string;
  email: string;
}

/** A kind of link that may be drawn between two assets. */
export interface RelationshipType {
  id: number;
  name: string;
}

/**
 * A link as seen from one particular asset. The server words `typeName` from
 * that asset outwards, so this never needs to know which end stored the row.
 */
export interface AssetRelationship {
  id: number;
  typeId: number;
  typeName: string;
  outgoing: boolean;
  otherAssetId: number;
  otherAssetLabel: string;
  otherAssetCategory: string;
  createdAt: string;
}

/** A file held against an asset. The stored path is never sent to the client. */
export interface Attachment {
  id: number;
  fileCategory: string;
  originalFilename: string;
  uploadedAt: string;
  uploadedBy: string;
}

/** Everything /api/reference/enums serves, in one shape rather than one per caller. */
export interface ReferenceEnums {
  ownershipTypes: string[];
  assigneeTypes: string[];
  customFieldTypes: string[];
  attachmentCategories: string[];
}

/** One uploaded import file and what became of it. */
export interface ImportBatch {
  id: number;
  filename: string;
  status: 'PENDING' | 'VALIDATED' | 'COMMITTED' | 'FAILED';
  rowCount: number;
  successCount: number;
  failureCount: number;
  importedAt: string;
  importedBy: string;
}

/** One parsed row, with why it cannot import if it cannot. */
export interface ImportRow {
  rowNumber: number;
  status: 'VALID' | 'INVALID' | 'IMPORTED';
  errorMessage: string | null;
  createdAssetId: number | null;
  data: Record<string, string>;
}

export interface ImportBatchDetail extends ImportBatch {
  rows: ImportRow[];
}

/** ORDERED is what the UI calls "Purchased"; the stored name is unchanged. */
export type PurchaseOrderStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'ORDERED'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CANCELLED';

/**
 * One line of an order. `unitPrice` and `lineTotal` are optional for the same
 * reason the asset cost fields are: without `purchase_order:cost:view` the
 * server does not send them at all, and the order total goes with them so the
 * price cannot be recovered by division.
 */
export interface PurchaseOrderLineItem {
  id: number;
  categoryId: number;
  categoryName: string;
  serialized: boolean;
  /** The catalogue entry being bought, when the line names one. */
  deviceModelId: number | null;
  deviceLabel: string | null;
  description: string;
  quantityOrdered: number;
  quantityReceived: number;
  quantityOutstanding: number;
  notes: string | null;
  unitPrice?: number | null;
  lineTotal?: number | null;
}

/** One delivery against an order: who unpacked it, when, and what was in it. */
export interface PurchaseOrderReceipt {
  id: number;
  receivedBy: string | null;
  receivedAt: string;
  notes: string | null;
  lines: { lineItemId: number; description: string; quantityReceived: number }[];
}

export interface PurchaseOrder {
  id: number;
  status: PurchaseOrderStatus;
  justification: string | null;
  notes: string | null;
  orderNumber: string | null;
  vendor: string | null;
  purchaseLink: string | null;
  requestedBy: string | null;
  requestedAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  orderedBy: string | null;
  /** When it was bought — the purchase date of everything it delivers. */
  orderedAt: string | null;
  rejectedBy: string | null;
  rejectedAt: string | null;
  rejectionReason: string | null;
  createdAt: string | null;
  lineItems: PurchaseOrderLineItem[];
  quantityOrdered: number;
  quantityReceived: number;
  fullyReceived: boolean;
  /** Absent without `purchase_order:cost:view`. */
  total?: number;
  /** Only on the detail response; the list omits receipts. */
  receipts?: PurchaseOrderReceipt[];
  hiddenFields: string[];
}

export type NotificationTrigger =
  | 'WARRANTY_EXPIRATION'
  | 'INVENTORY_STALENESS_CHECK'
  | 'PURCHASE_ORDER_SUBMITTED'
  | 'PURCHASE_ORDER_APPROVED'
  | 'PURCHASE_ORDER_DENIED'
  | 'PURCHASE_ORDER_PURCHASED'
  | 'PURCHASE_ORDER_PARTIALLY_RECEIVED'
  | 'PURCHASE_ORDER_RECEIVED'
  | 'PURCHASE_ORDER_CANCELLED'
  | 'ASSET_CREATED'
  | 'ASSET_LIFECYCLE_CHANGED'
  | 'ASSET_ASSIGNED'
  | 'ASSET_DELETED'
  | 'IMPORT_COMPLETED';

/** How often the emails for a rule go out. The notice itself is never delayed. */
export type NotificationFrequency = 'IMMEDIATE' | 'HOURLY' | 'DAILY' | 'WEEKLY' | 'MONTHLY';

/** The server's own list, so the UI never keeps a second copy of either enum. */
export interface NotificationVocabulary {
  triggerTypes: { name: NotificationTrigger; scheduled: boolean }[];
  frequencies: NotificationFrequency[];
}

/** One notification addressed to the signed-in user. */
export interface AppNotification {
  id: number;
  triggerType: NotificationTrigger;
  subject: string;
  body: string;
  /** What it is about, so the UI can link through. Not a foreign key server-side. */
  entityType: string | null;
  entityId: number | null;
  createdAt: string;
  readAt: string | null;
  /**
   * SKIPPED means no relay is configured — not a failure. DEFERRED means the
   * rule sends its emails on a digest rather than one at a time.
   */
  emailStatus: 'SKIPPED' | 'PENDING' | 'DEFERRED' | 'SENT' | 'FAILED';
  emailError: string | null;
}

export interface NotificationPage {
  content: AppNotification[];
  page: number;
  totalElements: number;
  totalPages: number;
  unread: number;
}

export interface DistributionTargetView {
  id: number | null;
  targetType: 'ROLE' | 'EMAIL';
  emailAddress: string | null;
  roleId: number | null;
  roleName: string | null;
}

export interface NotificationRuleView {
  id: number;
  name: string;
  triggerType: NotificationTrigger;
  assetCategoryId: number | null;
  assetCategoryName: string | null;
  active: boolean;
  frequency: NotificationFrequency;
  /** Whether the trigger is a periodic sweep rather than something a person did. */
  scheduled: boolean;
  lastRunAt: string | null;
  targets: DistributionTargetView[];
}

/** The SMTP relay. The password is never returned — only whether one is set. */
export interface MailSettings {
  enabled: boolean;
  host: string | null;
  port: number | null;
  username: string | null;
  fromAddress: string | null;
  startTls: boolean;
  passwordSet: boolean;
  usable: boolean;
  updatedAt: string | null;
}
