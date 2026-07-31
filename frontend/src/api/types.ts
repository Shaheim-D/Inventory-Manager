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
  assigneeType: 'NONE' | 'FREE_TEXT' | 'USER';
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
  locationType: string;
  ownershipType: string;
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
