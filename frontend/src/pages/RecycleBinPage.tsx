import { useState } from 'react';
import {
  Alert,
  AlertTitle,
  Badge,
  Button,
  Chip,
  Paper,
  Tab,
  Tabs,
  Tooltip,
  Typography,
} from '@mui/material';
import RestoreIcon from '@mui/icons-material/RestoreFromTrashOutlined';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { PageHeader } from '../components/PageHeader';
import { EntityTable, type Column } from '../components/EntityTable';
import { useAuth } from '../auth/AuthContext';
import { when } from '../format';

interface DeletedAsset {
  id: number;
  label: string;
  serialNumber: string | null;
  assetTag: string | null;
  categoryName: string | null;
  locationName: string | null;
  deletedAt: string | null;
  /** Set when a live asset has taken this one's serial or tag since it was deleted. */
  blockedReason: string | null;
}

interface DeactivatedLocation {
  id: number;
  label: string;
  assetCount: number;
}

interface RemovedCategory {
  id: number;
  label: string;
  description: string | null;
}

interface RemovedUser {
  id: number;
  label: string;
  email: string | null;
}

interface RetiredDeviceModel {
  id: number;
  label: string;
  deviceRole: string | null;
}

/**
 * Getting back something that was removed.
 *
 * <p>The page is explicit about what is <em>not</em> here, because a recovery
 * screen that shows three tabs and says nothing else implies everything is
 * recoverable. Categories, custom fields, notification rules, saved reports,
 * relationships and attachments are removed immediately; for those the only
 * recovery is restore-from-backup.
 */
export function RecycleBinPage() {
  const [tab, setTab] = useState<'assets' | 'locations' | 'categories' | 'devices' | 'users'>('assets');
  const { hasAny } = useAuth();

  const assets = useQuery({
    queryKey: ['recycle-bin', 'assets'],
    queryFn: () => api.get<DeletedAsset[]>('/api/recycle-bin/assets'),
  });
  const locations = useQuery({
    queryKey: ['recycle-bin', 'locations'],
    queryFn: () => api.get<DeactivatedLocation[]>('/api/recycle-bin/locations'),
    enabled: hasAny('location:read'),
  });
  const devices = useQuery({
    queryKey: ['recycle-bin', 'device-models'],
    queryFn: () => api.get<RetiredDeviceModel[]>('/api/recycle-bin/device-models'),
  });
  const categories = useQuery({
    queryKey: ['recycle-bin', 'categories'],
    queryFn: () => api.get<RemovedCategory[]>('/api/recycle-bin/categories'),
  });
  const users = useQuery({
    queryKey: ['recycle-bin', 'users'],
    queryFn: () => api.get<RemovedUser[]>('/api/recycle-bin/users'),
    // A list of accounts is a list of who works here, so this tab only loads
    // for somebody who already administers users.
    enabled: hasAny('user:manage'),
  });

  return (
    <>
      <PageHeader
        title="Recycle Bin"
        help="Assets are soft-deleted and locations and device models are deactivated, so all three can be brought back. Anything else is removed immediately and needs a restore from backup."
      />

      <Paper variant="outlined" sx={{ mb: 2 }}>
        <Tabs value={tab} onChange={(_, next) => setTab(next)} variant="scrollable" scrollButtons="auto">
          <Tab
            value="assets"
            label={<Badge badgeContent={assets.data?.length ?? 0} color="default" sx={{ pr: 2 }}>Assets</Badge>}
          />
          <Tab
            value="locations"
            label={<Badge badgeContent={locations.data?.length ?? 0} color="default" sx={{ pr: 2 }}>Locations</Badge>}
          />
          <Tab
            value="categories"
            label={<Badge badgeContent={categories.data?.length ?? 0} color="default" sx={{ pr: 2 }}>Categories</Badge>}
          />
          <Tab
            value="devices"
            label={<Badge badgeContent={devices.data?.length ?? 0} color="default" sx={{ pr: 2 }}>Device models</Badge>}
          />
          {hasAny('user:manage') && (
            <Tab
              value="users"
              label={<Badge badgeContent={users.data?.length ?? 0} color="default" sx={{ pr: 2 }}>Users</Badge>}
            />
          )}
        </Tabs>
      </Paper>

      {tab === 'assets' && <DeletedAssets query={assets} canRestore={hasAny('asset:delete')} />}
      {tab === 'locations' && <DeactivatedLocations query={locations} canRestore={hasAny('location:write')} />}
      {tab === 'categories' && <RemovedCategories query={categories} canRestore={hasAny('category:manage')} />}
      {tab === 'devices' && <RetiredDeviceModels query={devices} canRestore={hasAny('category:manage')} />}
      {tab === 'users' && <RemovedUsers query={users} canRestore={hasAny('user:manage')} />}

      <Alert severity="info" sx={{ mt: 3 }}>
        <AlertTitle>What is not in here</AlertTitle>
        Categories, custom field definitions, notification rules, saved reports, asset
        relationships and attachments are removed immediately rather than kept — so they never
        reach this screen. Recovering one of those means restoring from a backup; see the runbook.
        Nothing on this page is ever purged on a timer: a deleted asset stays recoverable
        indefinitely, not for a grace period.
      </Alert>
    </>
  );
}

/** One mutation shape for all three tabs — the interaction is deliberately identical. */
function useRestore(path: string, keys: string[]) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (id: number) => api.post(`/api/recycle-bin/${path}/${id}/restore`),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['recycle-bin', ...keys] });
      // The thing is live again, so every list that excluded it is now stale.
      void queryClient.invalidateQueries({ queryKey: [keys[0] === 'assets' ? 'assets' : keys[0]] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'That could not be recovered.'),
  });

  return { mutation, error, setError };
}

function RestoreButton({
  onClick,
  disabled,
  pending,
  blockedReason,
}: {
  onClick: () => void;
  disabled: boolean;
  pending: boolean;
  blockedReason?: string | null;
}) {
  const button = (
    <span>
      <Button
        size="small"
        variant="outlined"
        startIcon={<RestoreIcon />}
        disabled={disabled || pending || !!blockedReason}
        onClick={onClick}
      >
        Recover
      </Button>
    </span>
  );
  // Wrapped in a span so the tooltip still fires on a disabled button — the
  // whole point of disabling it is that the reason should be reachable.
  if (blockedReason) return <Tooltip title={blockedReason}>{button}</Tooltip>;
  if (disabled) return <Tooltip title="You do not have permission to recover this.">{button}</Tooltip>;
  return button;
}

function DeletedAssets({
  query,
  canRestore,
}: {
  query: { data?: DeletedAsset[]; isLoading: boolean };
  canRestore: boolean;
}) {
  const { mutation, error, setError } = useRestore('assets', ['assets']);

  const columns: Column<DeletedAsset>[] = [
    { header: 'Asset', render: (row) => row.label },
    {
      header: 'Serial / Tag',
      render: (row) => [row.serialNumber, row.assetTag].filter(Boolean).join(' · ') || '—',
      secondary: true,
    },
    { header: 'Category', render: (row) => row.categoryName ?? '—', secondary: true },
    { header: 'Location', render: (row) => row.locationName ?? '—', secondary: true },
    { header: 'Deleted', render: (row) => (row.deletedAt ? when(row.deletedAt) : '—') },
    {
      header: '',
      render: (row) =>
        row.blockedReason ? <Chip size="small" color="warning" label="Blocked" /> : null,
    },
  ];

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={query.data ?? []}
          rowKey={(row) => row.id}
          loading={query.isLoading}
          cardTitle={(row) => row.label}
          emptyMessage="Nothing deleted. Assets you delete land here and can be brought back at any time."
          rowActions={(row) => (
            <RestoreButton
              onClick={() => mutation.mutate(row.id)}
              disabled={!canRestore}
              pending={mutation.isPending}
              blockedReason={row.blockedReason}
            />
          )}
        />
      </Paper>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        Deleting an asset releases its serial number and asset tag, so something deleted by mistake
        can be re-created with the label still physically on it. The cost is that a live asset may
        have taken the identifier since — those rows show <strong>Blocked</strong>, and the tooltip
        names what is in the way. Clear it on that asset and the row recovers normally.
      </Typography>
    </>
  );
}

function DeactivatedLocations({
  query,
  canRestore,
}: {
  query: { data?: DeactivatedLocation[]; isLoading: boolean };
  canRestore: boolean;
}) {
  const { mutation, error, setError } = useRestore('locations', ['locations']);

  const columns: Column<DeactivatedLocation>[] = [
    { header: 'Location', render: (row) => row.label },
    {
      header: 'Assets still here',
      render: (row) => (row.assetCount > 0 ? row.assetCount : '—'),
      secondary: true,
    },
  ];

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={query.data ?? []}
          rowKey={(row) => row.id}
          loading={query.isLoading}
          cardTitle={(row) => row.label}
          emptyMessage="No deleted locations."
          rowActions={(row) => (
            <RestoreButton
              onClick={() => mutation.mutate(row.id)}
              disabled={!canRestore}
              pending={mutation.isPending}
            />
          )}
        />
      </Paper>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        Deleting a location hides it rather than removing it, so assets that were there keep their
        history and the location can come back with that history intact.
      </Typography>
    </>
  );
}

function RetiredDeviceModels({
  query,
  canRestore,
}: {
  query: { data?: RetiredDeviceModel[]; isLoading: boolean };
  canRestore: boolean;
}) {
  const { mutation, error, setError } = useRestore('device-models', ['device-models']);

  const columns: Column<RetiredDeviceModel>[] = [
    { header: 'Device', render: (row) => row.label },
    { header: 'Role', render: (row) => row.deviceRole ?? '—', secondary: true },
  ];

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={query.data ?? []}
          rowKey={(row) => row.id}
          loading={query.isLoading}
          cardTitle={(row) => row.label}
          emptyMessage="No retired device models."
          rowActions={(row) => (
            <RestoreButton
              onClick={() => mutation.mutate(row.id)}
              disabled={!canRestore}
              pending={mutation.isPending}
            />
          )}
        />
      </Paper>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        Retiring a device model only stops it being offered on new asset forms. Assets copy the
        manufacturer and model when they are created rather than pointing at this row, so nothing
        already built from it was affected.
      </Typography>
    </>
  );
}

function RemovedCategories({
  query,
  canRestore,
}: {
  query: { data?: RemovedCategory[]; isLoading: boolean };
  canRestore: boolean;
}) {
  const { mutation, error, setError } = useRestore('categories', ['categories']);

  const columns: Column<RemovedCategory>[] = [
    { header: 'Category', render: (row) => row.label },
    { header: 'Description', render: (row) => row.description ?? '—', secondary: true },
  ];

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={query.data ?? []}
          rowKey={(row) => row.id}
          loading={query.isLoading}
          cardTitle={(row) => row.label}
          emptyMessage="No deleted categories."
          rowActions={(row) => (
            <RestoreButton
              onClick={() => mutation.mutate(row.id)}
              disabled={!canRestore}
              pending={mutation.isPending}
            />
          )}
        />
      </Paper>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        A deleted category disappears from the asset form as well as from the Categories screen,
        so nothing new can be filed under it. Its custom fields, lifecycle graph and warranty
        thresholds are kept and come back with it.
      </Typography>
    </>
  );
}

function RemovedUsers({
  query,
  canRestore,
}: {
  query: { data?: RemovedUser[]; isLoading: boolean };
  canRestore: boolean;
}) {
  const { mutation, error, setError } = useRestore('users', ['users']);

  const columns: Column<RemovedUser>[] = [
    { header: 'Username', render: (row) => row.label },
    { header: 'Email', render: (row) => row.email ?? '—', secondary: true },
  ];

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={query.data ?? []}
          rowKey={(row) => row.id}
          loading={query.isLoading}
          cardTitle={(row) => row.label}
          emptyMessage="No removed accounts."
          rowActions={(row) => (
            <RestoreButton
              onClick={() => mutation.mutate(row.id)}
              disabled={!canRestore}
              pending={mutation.isPending}
            />
          )}
        />
      </Paper>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        Accounts are deactivated rather than deleted, so everything they ever did keeps their name
        against it in the audit history. Recovering one restores its roles and its password —
        it does not clear a lockout, because letting somebody back in is a separate decision.
      </Typography>
    </>
  );
}
