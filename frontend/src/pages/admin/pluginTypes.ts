export type PluginType = 'ZABBIX' | 'NETBOX' | 'LDAP' | 'ACTIVE_DIRECTORY' | 'RADIUS_NPS';

export type SyncStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILURE';

/** One field of a plugin's own configuration form, as the plugin declares it. */
export interface PluginConfigField {
  name: string;
  label: string;
  type: 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'SELECT' | 'MULTI_SELECT';
  required: boolean;
  /** True when the field names an environment variable rather than holding a value. */
  secretRef: boolean;
  help: string | null;
  options: string[];
}

/** A kind of integration this build can run. */
export interface PluginTypeInfo {
  type: PluginType;
  displayName: string;
  description: string;
  defaultSyncIntervalMinutes: number;
  touchesAssets: boolean;
  fields: PluginConfigField[];
}

/** A configured instance. */
export interface PluginInstance {
  id: number;
  name: string;
  pluginType: PluginType;
  displayName: string;
  enabled: boolean;
  configuration: Record<string, unknown>;
  lastSyncAt: string | null;
  lastSyncStatus: SyncStatus | null;
  pendingCount: number;
  touchesAssets: boolean;
  /** False when this build has no implementation for the type — after a downgrade. */
  available: boolean;
  /** Per secret field: whether the named variable currently resolves to anything. */
  secretsResolved: Record<string, boolean>;
}

export interface PendingAction {
  id: number;
  actionType: 'LINK_EXISTING_ASSET' | 'CREATE_NEW_ASSET';
  externalIdentifier: string;
  matchedVia: string | null;
  proposedData: Record<string, unknown>;
  createdAt: string | null;
  matchedAssetId: number | null;
  matchedAssetLabel: string | null;
}

export interface PluginLink {
  id: number;
  linkType: 'LINKED' | 'IGNORED';
  externalIdentifier: string;
  matchedVia: string | null;
  assetId: number | null;
  assetLabel: string | null;
  decidedAt: string;
  decidedBy: string | null;
}

export interface SyncRun {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  status: SyncStatus;
  message: string | null;
  recordsCreated: number | null;
  recordsUpdated: number | null;
  recordsFailed: number | null;
}

export interface GroupMapping {
  id: number;
  groupIdentifier: string;
  roleId: number;
  roleName: string | null;
}

export interface SyncReport {
  status: SyncStatus;
  message: string;
  created: number;
  updated: number;
  failed: number;
  awaitingConfirmation: number;
  ignored: number;
}

/** Colour for a status chip. PARTIAL is a real outcome, not a failure. */
export function statusColor(status: SyncStatus | null): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'SUCCESS') return 'success';
  if (status === 'PARTIAL') return 'warning';
  if (status === 'FAILURE') return 'error';
  return 'default';
}
