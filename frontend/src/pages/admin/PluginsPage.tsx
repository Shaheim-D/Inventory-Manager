import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Badge,
  Button,
  Card,
  CardActionArea,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ExtensionIcon from '@mui/icons-material/Extension';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { PageHeader } from '../../components/PageHeader';
import { PluginConfigForm } from './PluginConfigForm';
import { statusColor, type PluginInstance, type PluginTypeInfo } from './pluginTypes';

/**
 * The integrations this installation runs (Phase 9 §4.12).
 *
 * <p>A list rather than a settings form, because a plugin is a thing with a
 * state — enabled, last run, and how many records it is waiting to be told
 * about — and that count is the reason anybody opens this screen.
 */
export function PluginsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);

  const plugins = useQuery({
    queryKey: ['plugins'],
    queryFn: () => api.get<PluginInstance[]>('/api/admin/plugins'),
  });
  const types = useQuery({
    queryKey: ['plugin-types'],
    queryFn: () => api.get<PluginTypeInfo[]>('/api/admin/plugins/types'),
  });

  return (
    <>
      <PageHeader
        title="Plugins"
        subtitle="Integrations that read from other systems. Nothing an integration proposes reaches an asset until somebody confirms it."
        actions={
          <Button variant="contained" onClick={() => setAdding(true)}>
            Add a plugin
          </Button>
        }
      />

      {plugins.isLoading && <LinearProgress />}

      {!plugins.isLoading && (plugins.data ?? []).length === 0 && (
        <Paper variant="outlined" sx={{ p: 4, textAlign: 'center' }}>
          <ExtensionIcon sx={{ fontSize: 40, color: 'text.disabled' }} />
          <Typography color="text.secondary" sx={{ mt: 1 }}>
            No integrations configured. Add one to pull device data from Zabbix or NetBox, or to keep
            role assignment in step with a directory.
          </Typography>
        </Paper>
      )}

      <Grid container spacing={2}>
        {(plugins.data ?? []).map((plugin) => (
          <Grid item xs={12} md={6} lg={4} key={plugin.id}>
            <Card variant="outlined" sx={{ height: '100%' }}>
              <CardActionArea
                sx={{ height: '100%' }}
                onClick={() => navigate(`/admin/plugins/${plugin.id}`)}
              >
                <CardContent>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }} flexWrap="wrap" useFlexGap>
                    <Typography variant="subtitle1">{plugin.name}</Typography>
                    <Chip size="small" variant="outlined" label={plugin.displayName} />
                    {plugin.enabled
                      ? <Chip size="small" color="success" variant="outlined" label="On" />
                      : <Chip size="small" variant="outlined" label="Off" />}
                  </Stack>

                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                    {plugin.lastSyncStatus && (
                      <Chip
                        size="small"
                        color={statusColor(plugin.lastSyncStatus)}
                        variant="outlined"
                        label={plugin.lastSyncStatus}
                      />
                    )}
                    <Typography variant="body2" color="text.secondary">
                      {plugin.lastSyncAt
                        ? `Last run ${new Date(plugin.lastSyncAt).toLocaleString()}`
                        : 'Never run'}
                    </Typography>
                  </Stack>

                  {plugin.pendingCount > 0 && (
                    <Badge color="warning" badgeContent={plugin.pendingCount} sx={{ mt: 2 }}>
                      <Chip size="small" color="warning" label="Awaiting confirmation" />
                    </Badge>
                  )}

                  {!plugin.available && (
                    <Alert severity="warning" sx={{ mt: 2 }}>
                      This build has no implementation for {plugin.pluginType} plugins, so it cannot run.
                    </Alert>
                  )}
                </CardContent>
              </CardActionArea>
            </Card>
          </Grid>
        ))}
      </Grid>

      {adding && (
        <AddPluginDialog
          types={types.data ?? []}
          onClose={() => setAdding(false)}
          onCreated={(plugin) => {
            setAdding(false);
            void queryClient.invalidateQueries({ queryKey: ['plugins'] });
            navigate(`/admin/plugins/${plugin.id}`);
          }}
        />
      )}
    </>
  );
}

function AddPluginDialog({
  types,
  onClose,
  onCreated,
}: {
  types: PluginTypeInfo[];
  onClose: () => void;
  onCreated: (plugin: PluginInstance) => void;
}) {
  const [type, setType] = useState<string>('');
  const [name, setName] = useState('');
  const [configuration, setConfiguration] = useState<Record<string, unknown>>({});
  const [error, setError] = useState<string | null>(null);

  const chosen = types.find((entry) => entry.type === type) ?? null;

  const create = useMutation({
    mutationFn: () =>
      api.post<PluginInstance>('/api/admin/plugins', {
        name: name.trim(),
        pluginType: type,
        // Off to begin with, deliberately: a plugin that started syncing the
        // moment it was saved would stage its first proposals before anybody
        // had checked what it was pointed at.
        enabled: false,
        configuration,
      }),
    onSuccess: onCreated,
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not add that plugin.'),
  });

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Add a plugin</DialogTitle>
      <DialogContent>
        {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            select
            label="Plugin"
            value={type}
            onChange={(event) => {
              setType(event.target.value);
              setConfiguration({});
            }}
          >
            {types.map((entry) => (
              <MenuItem key={entry.type} value={entry.type}>
                {entry.displayName}
              </MenuItem>
            ))}
          </TextField>

          {chosen && (
            <>
              <Typography variant="body2" color="text.secondary">
                {chosen.description}
              </Typography>
              <TextField
                label="Name"
                required
                value={name}
                onChange={(event) => setName(event.target.value)}
                helperText="What to call this one. Two instances of the same kind need telling apart."
              />
              <PluginConfigForm
                fields={chosen.fields}
                value={configuration}
                onChange={setConfiguration}
              />
              <Alert severity="info">
                It starts switched off. Test the connection first, then turn it on.
              </Alert>
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!chosen || !name.trim() || create.isPending}
          onClick={() => create.mutate()}
        >
          Add
        </Button>
      </DialogActions>
    </Dialog>
  );
}
