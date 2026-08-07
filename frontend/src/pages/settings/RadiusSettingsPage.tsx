import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  Divider,
  FormControlLabel,
  Grid,
  IconButton,
  InputAdornment,
  LinearProgress,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import VisibilityIcon from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { RadiusSettings } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';

/**
 * RADIUS/NPS sign-in, against a primary and a secondary server.
 *
 * Configured here rather than in environment variables so it can be corrected or
 * switched off without a restart — which is exactly when you need to, since
 * getting it wrong is what stops people signing in.
 *
 * Shared secrets are stored encrypted and never sent back to the browser. A
 * stored one shows as a masked placeholder; leaving it alone keeps it, so a port
 * can be changed without retyping a credential.
 */

/** Two slots always render. A blank secondary is simply not saved. */
const SLOTS = [
  { ordinal: 1, label: 'Primary server' },
  { ordinal: 2, label: 'Secondary server' },
];

interface ServerForm {
  host: string;
  port: string;
  /** Only ever what the user just typed. A stored secret never arrives here. */
  sharedSecret: string;
  secretSet: boolean;
  secretReadable: boolean;
}

const emptyServer = (): ServerForm => ({
  host: '',
  port: '1812',
  sharedSecret: '',
  secretSet: false,
  secretReadable: false,
});

export function RadiusSettingsPage() {
  const queryClient = useQueryClient();

  const settings = useQuery({
    queryKey: ['radius-settings'],
    queryFn: () => api.get<RadiusSettings>('/api/admin/radius-settings'),
  });

  const [form, setForm] = useState({
    enabled: false,
    timeoutSeconds: '5',
    retries: '1',
    nasIdentifier: '',
  });
  const [servers, setServers] = useState<ServerForm[]>([emptyServer(), emptyServer()]);
  const [revealed, setRevealed] = useState<Record<number, boolean>>({});
  const [testUsername, setTestUsername] = useState('');
  const [testPassword, setTestPassword] = useState('');
  const [banner, setBanner] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    const data = settings.data;
    if (!data) return;
    setForm({
      enabled: data.enabled,
      timeoutSeconds: String(data.timeoutSeconds ?? 5),
      retries: String(data.retries ?? 1),
      nasIdentifier: data.nasIdentifier ?? '',
    });
    setServers(
      SLOTS.map((slot) => {
        const stored = data.servers.find((s) => s.ordinal === slot.ordinal);
        if (!stored) return emptyServer();
        return {
          host: stored.host,
          port: String(stored.port),
          sharedSecret: '',
          secretSet: stored.secretSet,
          secretReadable: stored.secretReadable,
        };
      }),
    );
  }, [settings.data]);

  const updateServer = (index: number, patch: Partial<ServerForm>) =>
    setServers((current) => current.map((s, i) => (i === index ? { ...s, ...patch } : s)));

  const save = useMutation({
    mutationFn: () =>
      api.put<RadiusSettings>('/api/admin/radius-settings', {
        enabled: form.enabled,
        timeoutSeconds: Number(form.timeoutSeconds) || 5,
        retries: Number(form.retries) || 1,
        nasIdentifier: form.nasIdentifier.trim() || null,
        servers: servers.map((s) => ({
          host: s.host.trim() || null,
          port: Number(s.port) || 1812,
          // Blank means "keep what is stored". The server reads it that way.
          sharedSecret: s.sharedSecret ? s.sharedSecret : null,
        })),
      }),
    onSuccess: () => {
      setBanner({ kind: 'success', text: 'Saved.' });
      setRevealed({});
      void queryClient.invalidateQueries({ queryKey: ['radius-settings'] });
    },
    onError: (error: unknown) =>
      setBanner({
        kind: 'error',
        text: error instanceof ApiError ? error.message : 'Could not save.',
      }),
  });

  const test = useMutation({
    mutationFn: () =>
      api.post<{ ok: boolean; message: string }>('/api/admin/radius-settings/test', {
        username: testUsername,
        password: testPassword,
      }),
    onSuccess: (result) => {
      setBanner({ kind: result.ok ? 'success' : 'error', text: result.message });
      setTestPassword('');
    },
    onError: (error: unknown) =>
      setBanner({
        kind: 'error',
        text: error instanceof ApiError ? error.message : 'Could not run the test.',
      }),
  });

  const unreadableSecret = servers.some((s) => s.secretSet && !s.secretReadable);

  return (
    <>
      <PageHeader
        title="RADIUS"
        help={
          <>
            Lets people sign in with their network credentials, checked against RADIUS/NPS.
            Two servers can be configured; the secondary is used when the primary does not
            answer. A first-time user gets an account with no permissions until somebody
            assigns roles. Passwords set in this application keep working either way — this
            is in addition to local sign-in, never instead of it.
          </>
        }
      />

      {settings.isLoading && <LinearProgress />}
      {banner && (
        <Alert severity={banner.kind} sx={{ mb: 2 }} onClose={() => setBanner(null)}>
          {banner.text}
        </Alert>
      )}
      {unreadableSecret && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          A stored shared secret cannot be decrypted with this installation's encryption key.
          That normally means the database was restored onto a host without the original key
          file. Enter the secret again to fix it — nothing else is affected.
        </Alert>
      )}

      <Paper sx={{ p: 3 }}>
        <Stack spacing={3}>
          <FormControlLabel
            control={
              <Switch
                checked={form.enabled}
                onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
              />
            }
            label="Allow signing in with network credentials"
          />

          <Alert severity="info">
            Local accounts are always tried first, so an unreachable RADIUS server can never
            lock an administrator out of the account they would need to fix it. Turning this
            off changes nothing for anyone who has a password set in this application.
          </Alert>

          {SLOTS.map((slot, index) => (
            <Stack key={slot.ordinal} spacing={2}>
              <Divider textAlign="left">
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography variant="subtitle2">{slot.label}</Typography>
                  {index === 1 && (
                    <Chip size="small" variant="outlined" label="Optional" />
                  )}
                </Stack>
              </Divider>

              <Grid container spacing={2}>
                <Grid item xs={12} sm={5}>
                  <TextField
                    fullWidth
                    label="Server"
                    value={servers[index].host}
                    onChange={(event) => updateServer(index, { host: event.target.value })}
                    placeholder={index === 0 ? 'nps01.example.local' : 'nps02.example.local'}
                    helperText={
                      index === 0
                        ? 'Tried first.'
                        : 'Tried only when the primary does not answer. Leave blank for one server.'
                    }
                  />
                </Grid>
                <Grid item xs={12} sm={3}>
                  <TextField
                    fullWidth
                    label="Port"
                    value={servers[index].port}
                    onChange={(event) => updateServer(index, { port: event.target.value })}
                    helperText="1812 is standard."
                  />
                </Grid>
                <Grid item xs={12} sm={4}>
                  <TextField
                    fullWidth
                    label="Shared secret"
                    type={revealed[index] ? 'text' : 'password'}
                    value={servers[index].sharedSecret}
                    onChange={(event) => updateServer(index, { sharedSecret: event.target.value })}
                    placeholder={servers[index].secretSet ? '••••••••  (unchanged)' : ''}
                    autoComplete="new-password"
                    InputProps={{
                      endAdornment: (
                        <InputAdornment position="end">
                          <IconButton
                            aria-label={revealed[index] ? 'Hide what you typed' : 'Show what you typed'}
                            onClick={() =>
                              setRevealed((current) => ({ ...current, [index]: !current[index] }))
                            }
                            edge="end"
                            size="small"
                            // Only ever reveals what is in the box now. A stored
                            // secret is not sent to the browser, so there is
                            // nothing here to reveal until somebody types.
                            disabled={!servers[index].sharedSecret}
                          >
                            {revealed[index] ? <VisibilityOffIcon /> : <VisibilityIcon />}
                          </IconButton>
                        </InputAdornment>
                      ),
                    }}
                    helperText={
                      servers[index].secretSet
                        ? 'Stored and encrypted. Leave blank to keep it; type to replace it.'
                        : 'Stored encrypted, and never shown again after saving.'
                    }
                  />
                </Grid>
              </Grid>
            </Stack>
          ))}

          <Divider />

          <Grid container spacing={2}>
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="Timeout (seconds)"
                value={form.timeoutSeconds}
                onChange={(event) => setForm({ ...form, timeoutSeconds: event.target.value })}
                helperText="Per server, before trying the next."
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="Retries"
                value={form.retries}
                onChange={(event) => setForm({ ...form, retries: event.target.value })}
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="NAS identifier"
                value={form.nasIdentifier}
                onChange={(event) => setForm({ ...form, nasIdentifier: event.target.value })}
                helperText="Optional. NPS network policies often match on it."
              />
            </Grid>
          </Grid>

          <Stack direction="row" spacing={2}>
            <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>
              Save
            </Button>
          </Stack>

          <Divider />

          <Stack spacing={2}>
            <Typography variant="subtitle1">Test it</Typography>
            <Typography variant="body2" color="text.secondary">
              Sends a real sign-in request using the saved settings, through the same code
              signing in uses. Nothing else proves the whole path — a server can be reachable
              and still reject everyone because the shared secret is wrong or its network
              policy excludes this application. The result names which server answered. The
              credentials you enter are used once and never stored.
            </Typography>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  label="Username"
                  value={testUsername}
                  onChange={(event) => setTestUsername(event.target.value)}
                />
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  type="password"
                  label="Password"
                  value={testPassword}
                  onChange={(event) => setTestPassword(event.target.value)}
                />
              </Grid>
            </Grid>
            <Stack direction="row">
              <Button variant="outlined" onClick={() => test.mutate()} disabled={test.isPending}>
                Send a test sign-in
              </Button>
            </Stack>
          </Stack>
        </Stack>
      </Paper>
    </>
  );
}
