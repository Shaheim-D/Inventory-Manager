import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Badge,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Switch,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { EntityTable } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';
import { PluginConfigForm } from './PluginConfigForm';
import { CategoryPicker } from '../../components/CategoryPicker';
import { locationOptions, locationOptionSx, locationPath } from '../../components/locationTree';
import type { Location } from '../../api/types';
import {
  statusColor,
  type PendingAction,
  type PluginInstance,
  type PluginLink,
  type PluginTypeInfo,
  type SyncReport,
  type SyncRun,
} from './pluginTypes';

/**
 * One integration: how it is configured, what it is waiting to be told, and
 * what it has been told already.
 *
 * <p>The review queue is the same shape as the inventory verification queue on
 * purpose — a list of things needing a quick decision, a few buttons per row.
 * Both design documents asked for that explicitly: a reviewer should not have
 * to learn two mental models because one queue happens to come from a plugin
 * and the other from a date.
 */
export function PluginDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState(0);
  const [report, setReport] = useState<SyncReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [connection, setConnection] = useState<{ ok: boolean; message: string } | null>(null);

  const plugin = useQuery({
    queryKey: ['plugin', id],
    queryFn: () => api.get<PluginInstance>(`/api/admin/plugins/${id}`),
  });
  const types = useQuery({
    queryKey: ['plugin-types'],
    queryFn: () => api.get<PluginTypeInfo[]>('/api/admin/plugins/types'),
  });
  const pending = useQuery({
    queryKey: ['plugin-pending', id],
    queryFn: () => api.get<PendingAction[]>(`/api/admin/plugins/${id}/pending`),
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['plugin', id] });
    void queryClient.invalidateQueries({ queryKey: ['plugin-pending', id] });
    void queryClient.invalidateQueries({ queryKey: ['plugin-ignored', id] });
    void queryClient.invalidateQueries({ queryKey: ['plugin-runs', id] });
    void queryClient.invalidateQueries({ queryKey: ['plugins'] });
  };

  const sync = useMutation({
    mutationFn: () => api.post<SyncReport>(`/api/admin/plugins/${id}/sync`),
    onSuccess: (result) => {
      setReport(result);
      setError(null);
      // One current message on screen, not a pile: the connection result from a
      // minute ago is not what somebody just asked about.
      setConnection(null);
      refresh();
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not run that sync.'),
  });

  const test = useMutation({
    mutationFn: () => api.post<{ ok: boolean; message: string }>(`/api/admin/plugins/${id}/test-connection`),
    onSuccess: (result) => {
      setConnection(result);
      setReport(null);
    },
  });

  const remove = useMutation({
    mutationFn: () => api.del(`/api/admin/plugins/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['plugins'] });
      navigate('/admin/plugins');
    },
  });

  if (plugin.isLoading) return <LinearProgress />;
  if (!plugin.data) return <Alert severity="error">That plugin no longer exists.</Alert>;

  const instance = plugin.data;
  const schema = (types.data ?? []).find((entry) => entry.type === instance.pluginType) ?? null;
  const pendingCount = (pending.data ?? []).length;

  return (
    <>
      <PageHeader
        title={instance.name}
        subtitle={schema?.description ?? instance.displayName}
        actions={
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            <Button onClick={() => navigate('/admin/plugins')}>Back</Button>
            <Button onClick={() => test.mutate()} disabled={test.isPending}>
              Test connection
            </Button>
            <Button variant="contained" onClick={() => sync.mutate()} disabled={sync.isPending}>
              Sync now
            </Button>
          </Stack>
        }
      />

      {connection && (
        <Alert severity={connection.ok ? 'success' : 'error'} sx={{ mb: 2 }} onClose={() => setConnection(null)}>
          {connection.message}
        </Alert>
      )}
      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      {report && (
        <Alert
          severity={report.status === 'FAILURE' ? 'error' : report.status === 'PARTIAL' ? 'warning' : 'success'}
          sx={{ mb: 2 }}
          onClose={() => setReport(null)}
        >
          {report.message}
        </Alert>
      )}

      <Tabs value={tab} onChange={(_event, next) => setTab(next)} sx={{ mb: 2 }}>
        <Tab
          label={
            <Badge color="warning" badgeContent={pendingCount} sx={{ pr: pendingCount ? 2 : 0 }}>
              Awaiting confirmation
            </Badge>
          }
        />
        <Tab label="Configuration" />
        <Tab label="Confirmed" />
        <Tab label="Ignored" />
        <Tab label="Run history" />
      </Tabs>

      {tab === 0 && <PendingQueue pluginId={instance.id} actions={pending.data ?? []} onResolved={refresh} />}
      {tab === 1 && (
        <ConfigurationTab
          instance={instance}
          schema={schema}
          onSaved={refresh}
          onDelete={() => remove.mutate()}
        />
      )}
      {tab === 2 && <LinkList pluginId={instance.id} kind="links" onChanged={refresh} />}
      {tab === 3 && <LinkList pluginId={instance.id} kind="ignored" onChanged={refresh} />}
      {tab === 4 && <RunHistory pluginId={instance.id} />}
    </>
  );
}

// ---------------------------------------------------------------------------
// the review queue
// ---------------------------------------------------------------------------

function PendingQueue({
  pluginId,
  actions,
  onResolved,
}: {
  pluginId: number;
  actions: PendingAction[];
  onResolved: () => void;
}) {
  const [accepting, setAccepting] = useState<PendingAction | null>(null);
  const [error, setError] = useState<string | null>(null);

  const resolve = useMutation({
    mutationFn: ({ actionId, how }: { actionId: number; how: 'accept' | 'deny' | 'ignore' }) =>
      api.post(`/api/admin/plugins/pending/${actionId}/${how}`, {}),
    onSuccess: () => {
      setError(null);
      onResolved();
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not record that decision.'),
  });

  return (
    <>
      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}

      <Paper variant="outlined">
        <EntityTable
          columns={[
            {
              header: 'Proposal',
              render: (action: PendingAction) =>
                action.actionType === 'LINK_EXISTING_ASSET' ? (
                  <Stack>
                    <Typography variant="body2">Link to {action.matchedAssetLabel}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      matched on {action.matchedVia === 'SERIAL_NUMBER' ? 'serial number' : action.matchedVia}
                    </Typography>
                  </Stack>
                ) : (
                  <Stack>
                    <Typography variant="body2">Create a new asset</Typography>
                    <Typography variant="caption" color="text.secondary">
                      nothing here matches it
                    </Typography>
                  </Stack>
                ),
            },
            {
              header: 'Upstream record',
              render: (action: PendingAction) => action.externalIdentifier,
            },
            {
              header: 'What it wants to write',
              render: (action: PendingAction) => (
                <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                  {Object.entries(action.proposedData).map(([key, value]) => (
                    <Chip key={key} size="small" variant="outlined" label={`${key}: ${String(value)}`} />
                  ))}
                </Stack>
              ),
            },
          ]}
          rows={actions}
          rowKey={(action) => action.id}
          emptyMessage="Nothing is waiting. Everything this plugin has seen has already been decided."
          cardTitle={(action) =>
            action.actionType === 'LINK_EXISTING_ASSET'
              ? `Link to ${action.matchedAssetLabel}`
              : 'Create a new asset'}
          rowActions={(action) => (
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              <Button
                size="small"
                variant="outlined"
                onClick={() =>
                  action.actionType === 'CREATE_NEW_ASSET'
                    ? setAccepting(action)
                    : resolve.mutate({ actionId: action.id, how: 'accept' })
                }
              >
                Accept
              </Button>
              <Button size="small" onClick={() => resolve.mutate({ actionId: action.id, how: 'deny' })}>
                Not this time
              </Button>
              <Button
                size="small"
                color="error"
                onClick={() => resolve.mutate({ actionId: action.id, how: 'ignore' })}
              >
                Never ask again
              </Button>
            </Stack>
          )}
        />
      </Paper>

      <Alert severity="info" sx={{ mt: 2 }}>
        <strong>Not this time</strong> leaves no record, so this comes back on the next sync.{' '}
        <strong>Never ask again</strong> is a standing decision — it is listed under Ignored and can
        be reversed there.
      </Alert>

      {accepting && (
        <AcceptNewAssetDialog
          action={accepting}
          pluginId={pluginId}
          onClose={() => setAccepting(null)}
          onAccepted={() => {
            setAccepting(null);
            onResolved();
          }}
        />
      )}
    </>
  );
}

/**
 * Accepting a proposed new asset needs two things the upstream cannot know:
 * what kind of thing it is, and where it physically is.
 */
function AcceptNewAssetDialog({
  action,
  onClose,
  onAccepted,
}: {
  action: PendingAction;
  pluginId: number;
  onClose: () => void;
  onAccepted: () => void;
}) {
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [locationId, setLocationId] = useState<string>('');
  const [error, setError] = useState<string | null>(null);

  const locations = useQuery({
    queryKey: ['locations'],
    queryFn: () => api.get<Location[]>('/api/locations'),
  });

  const accept = useMutation({
    mutationFn: () =>
      api.post(`/api/admin/plugins/pending/${action.id}/accept`, {
        categoryId,
        locationId: locationId ? Number(locationId) : null,
      }),
    onSuccess: onAccepted,
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not create that asset.'),
  });

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Create this asset</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Alert severity="info">
            The integration knows what it found; it cannot know what kind of thing it is or where it
            physically lives. Both are yours to say.
          </Alert>
          <CategoryPicker required value={categoryId} onChange={setCategoryId} emptyLabel="Choose a category" />
          <TextField
            select
            required
            label="Location"
            value={locationId}
            onChange={(event) => setLocationId(event.target.value)}
            SelectProps={{ renderValue: (value) => locationPath(locations.data, Number(value)) }}
          >
            {locationOptions(locations.data)
              .filter((option) => option.location.active)
              .map((option) => (
                <MenuItem
                  key={option.location.id}
                  value={String(option.location.id)}
                  sx={locationOptionSx(option.depth)}
                >
                  {option.location.name}
                </MenuItem>
              ))}
          </TextField>

          <Box>
            <Typography variant="subtitle2" sx={{ mb: 0.5 }}>What will be written</Typography>
            <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
              {Object.entries(action.proposedData).map(([key, value]) => (
                <Chip key={key} size="small" variant="outlined" label={`${key}: ${String(value)}`} />
              ))}
            </Stack>
          </Box>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!categoryId || !locationId || accept.isPending}
          onClick={() => accept.mutate()}
        >
          Create it
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// configuration
// ---------------------------------------------------------------------------

function ConfigurationTab({
  instance,
  schema,
  onSaved,
  onDelete,
}: {
  instance: PluginInstance;
  schema: PluginTypeInfo | null;
  onSaved: () => void;
  onDelete: () => void;
}) {
  const [name, setName] = useState(instance.name);
  const [enabled, setEnabled] = useState(instance.enabled);
  const [configuration, setConfiguration] = useState<Record<string, unknown>>(instance.configuration);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);

  const save = useMutation({
    mutationFn: () =>
      api.put(`/api/admin/plugins/${instance.id}`, {
        name, pluginType: instance.pluginType, enabled, configuration,
      }),
    onSuccess: () => {
      setSaved(true);
      setError(null);
      onSaved();
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not save that.'),
  });

  return (
    <Stack spacing={2}>
      {saved && <Alert severity="success" onClose={() => setSaved(false)}>Saved.</Alert>}
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Stack spacing={2}>
          <TextField label="Name" value={name} onChange={(event) => setName(event.target.value)} />
          <FormControlLabel
            control={<Switch checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />}
            label="Run this plugin on its schedule"
          />
          {schema && (
            <>
              <PluginConfigForm
                fields={schema.fields}
                value={configuration}
                onChange={setConfiguration}
                secretsResolved={instance.secretsResolved}
              />
              <Typography variant="caption" color="text.secondary">
                Suggested interval for this kind of plugin: every {schema.defaultSyncIntervalMinutes}{' '}
                minutes. Leave the interval blank to use it.
              </Typography>
            </>
          )}
          <Stack direction="row" spacing={1}>
            <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>
              Save
            </Button>
            <Box sx={{ flexGrow: 1 }} />
            <Button color="error" onClick={() => setConfirmingDelete(true)}>
              Remove this plugin
            </Button>
          </Stack>
        </Stack>
      </Paper>

      <Dialog open={confirmingDelete} onClose={() => setConfirmingDelete(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Remove “{instance.name}”?</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            Its confirmed links and its ignore list go with it, so if you add it again everything it
            finds will be proposed for review from scratch. Assets it has already written to are not
            touched.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmingDelete(false)}>Cancel</Button>
          <Button variant="contained" color="error" onClick={onDelete}>Remove</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

// ---------------------------------------------------------------------------
// settled decisions
// ---------------------------------------------------------------------------

function LinkList({
  pluginId,
  kind,
  onChanged,
}: {
  pluginId: number;
  kind: 'links' | 'ignored';
  onChanged: () => void;
}) {
  const queryClient = useQueryClient();
  const links = useQuery({
    queryKey: [kind === 'ignored' ? 'plugin-ignored' : 'plugin-links', String(pluginId)],
    queryFn: () => api.get<PluginLink[]>(`/api/admin/plugins/${pluginId}/${kind}`),
  });

  const reverse = useMutation({
    mutationFn: (linkId: number) => api.del(`/api/admin/plugins/links/${linkId}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['plugin-ignored', String(pluginId)] });
      void queryClient.invalidateQueries({ queryKey: ['plugin-links', String(pluginId)] });
      onChanged();
    },
  });

  return (
    <>
      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'Upstream record', render: (link: PluginLink) => link.externalIdentifier },
            {
              header: kind === 'ignored' ? 'Decision' : 'Asset',
              render: (link: PluginLink) =>
                link.assetLabel ?? (kind === 'ignored' ? 'Never propose this record' : '—'),
            },
            {
              header: 'Decided',
              render: (link: PluginLink) =>
                `${new Date(link.decidedAt).toLocaleString()}${link.decidedBy ? ` by ${link.decidedBy}` : ''}`,
            },
          ]}
          rows={links.data ?? []}
          rowKey={(link) => link.id}
          loading={links.isLoading}
          emptyMessage={
            kind === 'ignored'
              ? 'Nothing is being ignored. Records you say “never ask again” to appear here.'
              : 'Nothing confirmed yet. Accepted proposals appear here and sync without asking again.'
          }
          rowActions={(link) => (
            <Button size="small" onClick={() => reverse.mutate(link.id)}>
              {kind === 'ignored' ? 'Start asking again' : 'Unlink'}
            </Button>
          )}
        />
      </Paper>
      <Alert severity="info" sx={{ mt: 2 }}>
        {kind === 'ignored'
          ? 'Reversing one puts the record back in the queue on the next sync, as if it were new.'
          : 'Unlinking stops this plugin writing to that asset. The next sync will propose it again for review.'}
      </Alert>
    </>
  );
}

function RunHistory({ pluginId }: { pluginId: number }) {
  const runs = useQuery({
    queryKey: ['plugin-runs', String(pluginId)],
    queryFn: () => api.get<SyncRun[]>(`/api/admin/plugins/${pluginId}/runs`),
  });

  return (
    <Paper variant="outlined">
      <EntityTable
        columns={[
          { header: 'Started', render: (run: SyncRun) => new Date(run.startedAt).toLocaleString() },
          {
            header: 'Result',
            render: (run: SyncRun) => (
              <Chip size="small" variant="outlined" color={statusColor(run.status)} label={run.status} />
            ),
          },
          { header: 'Created', align: 'right', render: (run: SyncRun) => run.recordsCreated ?? '—' },
          { header: 'Updated', align: 'right', render: (run: SyncRun) => run.recordsUpdated ?? '—' },
          { header: 'Failed', align: 'right', render: (run: SyncRun) => run.recordsFailed ?? '—' },
          { header: 'Detail', secondary: true, render: (run: SyncRun) => run.message ?? '—' },
        ]}
        rows={runs.data ?? []}
        rowKey={(run) => run.id}
        loading={runs.isLoading}
        emptyMessage="This plugin has not run yet."
      />
    </Paper>
  );
}
