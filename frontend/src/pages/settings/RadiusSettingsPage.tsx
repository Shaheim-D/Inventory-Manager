import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Divider,
  FormControlLabel,
  Grid,
  LinearProgress,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { RadiusSettings } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';

/**
 * RADIUS/NPS sign-in.
 *
 * Configured here rather than in environment variables so it can be corrected
 * or switched off without a restart — which is exactly when you need to, since
 * getting it wrong is what stops people signing in.
 *
 * The shared secret is never held by this screen. The form takes the *name* of
 * an environment variable and the server says only whether that name currently
 * resolves.
 */
export function RadiusSettingsPage() {
  const queryClient = useQueryClient();

  const settings = useQuery({
    queryKey: ['radius-settings'],
    queryFn: () => api.get<RadiusSettings>('/api/admin/radius-settings'),
  });

  const [form, setForm] = useState({
    enabled: false,
    host: '',
    port: '1812',
    sharedSecretRef: '',
    timeoutSeconds: '5',
    retries: '1',
    nasIdentifier: '',
  });
  const [testUsername, setTestUsername] = useState('');
  const [testPassword, setTestPassword] = useState('');
  const [banner, setBanner] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    const data = settings.data;
    if (!data) return;
    setForm({
      enabled: data.enabled,
      host: data.host ?? '',
      port: String(data.port ?? 1812),
      sharedSecretRef: data.sharedSecretRef ?? '',
      timeoutSeconds: String(data.timeoutSeconds ?? 5),
      retries: String(data.retries ?? 1),
      nasIdentifier: data.nasIdentifier ?? '',
    });
  }, [settings.data]);

  const save = useMutation({
    mutationFn: () =>
      api.put<RadiusSettings>('/api/admin/radius-settings', {
        enabled: form.enabled,
        host: form.host.trim() || null,
        port: Number(form.port) || 1812,
        sharedSecretRef: form.sharedSecretRef.trim() || null,
        timeoutSeconds: Number(form.timeoutSeconds) || 5,
        retries: Number(form.retries) || 1,
        nasIdentifier: form.nasIdentifier.trim() || null,
      }),
    onSuccess: () => {
      setBanner({ kind: 'success', text: 'Saved.' });
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

  const secretMissing =
    form.sharedSecretRef.trim().length > 0 && settings.data?.sharedSecretResolves === false;

  return (
    <>
      <PageHeader
        title="RADIUS"
        help={
          <>
            Lets people sign in with their network credentials, checked against RADIUS/NPS.
            A first-time user gets an account with no permissions until somebody assigns
            roles. Passwords set in this application keep working either way — this is in
            addition to local sign-in, never instead of it.
          </>
        }
      />

      {settings.isLoading && <LinearProgress />}
      {banner && (
        <Alert severity={banner.kind} sx={{ mb: 2 }} onClose={() => setBanner(null)}>
          {banner.text}
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

          <Grid container spacing={2}>
            <Grid item xs={12} sm={8}>
              <TextField
                fullWidth
                label="Server"
                value={form.host}
                onChange={(event) => setForm({ ...form, host: event.target.value })}
                placeholder="nps01.example.local"
                helperText="Host name or IP address of the RADIUS/NPS server."
              />
            </Grid>
            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="Port"
                value={form.port}
                onChange={(event) => setForm({ ...form, port: event.target.value })}
                helperText="1812 is the standard."
              />
            </Grid>

            <Grid item xs={12}>
              <TextField
                fullWidth
                label="Shared secret variable"
                value={form.sharedSecretRef}
                onChange={(event) => setForm({ ...form, sharedSecretRef: event.target.value })}
                placeholder="RADIUS_SHARED_SECRET"
                error={secretMissing}
                helperText={
                  secretMissing
                    ? `Nothing in this application's environment is named ${form.sharedSecretRef}. Set it and restart.`
                    : 'The NAME of an environment variable, not the secret itself. The secret is never stored in the database, so it is never in a backup.'
                }
              />
            </Grid>

            <Grid item xs={12} sm={4}>
              <TextField
                fullWidth
                label="Timeout (seconds)"
                value={form.timeoutSeconds}
                onChange={(event) => setForm({ ...form, timeoutSeconds: event.target.value })}
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
              Sends a real sign-in request using the saved settings. Nothing else proves the
              whole path works — a server can be reachable and still reject everyone because
              the shared secret is wrong or its network policy excludes this application. The
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
