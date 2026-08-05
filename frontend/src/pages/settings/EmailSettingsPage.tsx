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
import type { MailSettings } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';

/**
 * The SMTP relay.
 *
 * <p>Configured here rather than in a deployment file because rotating an SMTP
 * password should not need a restart. Email is an addition to notification
 * delivery, never a prerequisite: with this switched off the system still
 * notifies people in the application, which is why the page says so plainly
 * rather than looking broken.
 */
export function EmailSettingsPage() {
  const queryClient = useQueryClient();

  const settings = useQuery({
    queryKey: ['mail-settings'],
    queryFn: () => api.get<MailSettings>('/api/admin/mail-settings'),
  });

  const [form, setForm] = useState({
    enabled: false,
    host: '',
    port: '587',
    username: '',
    password: '',
    fromAddress: '',
    startTls: true,
  });
  const [passwordTouched, setPasswordTouched] = useState(false);
  const [testTo, setTestTo] = useState('');
  const [banner, setBanner] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    const data = settings.data;
    if (!data) return;
    setForm({
      enabled: data.enabled,
      host: data.host ?? '',
      port: data.port == null ? '587' : String(data.port),
      username: data.username ?? '',
      password: '',
      fromAddress: data.fromAddress ?? '',
      startTls: data.startTls,
    });
    setPasswordTouched(false);
  }, [settings.data]);

  const set = (patch: Partial<typeof form>) => setForm((current) => ({ ...current, ...patch }));

  const save = useMutation({
    mutationFn: () =>
      api.put<MailSettings>('/api/admin/mail-settings', {
        enabled: form.enabled,
        host: form.host.trim() || null,
        port: form.port ? Number(form.port) : null,
        username: form.username.trim() || null,
        // Omitted unless touched, so saving after changing the port does not
        // blank a password this page was never shown.
        ...(passwordTouched ? { password: form.password } : {}),
        fromAddress: form.fromAddress.trim() || null,
        startTls: form.startTls,
      }),
    onSuccess: () => {
      setBanner({ kind: 'success', text: 'Saved.' });
      void queryClient.invalidateQueries({ queryKey: ['mail-settings'] });
    },
    onError: (caught) =>
      setBanner({
        kind: 'error',
        text: caught instanceof ApiError ? caught.message : 'Could not save these settings.',
      }),
  });

  const sendTest = useMutation({
    mutationFn: () => api.post<{ ok: boolean; message: string }>('/api/admin/mail-settings/test', {
      to: testTo.trim(),
    }),
    onSuccess: (result) =>
      setBanner({ kind: result.ok ? 'success' : 'error', text: result.message }),
    onError: (caught) =>
      setBanner({
        kind: 'error',
        text: caught instanceof ApiError ? caught.message : 'Could not send the test.',
      }),
  });

  if (settings.isLoading) return <LinearProgress />;

  return (
    <>
      <PageHeader
        title="SMTP settings"
        help="Where notifications are emailed from. Notifications always appear in the application; email is in addition to that."
      />

      {banner && (
        <Alert severity={banner.kind} sx={{ mb: 2 }} onClose={() => setBanner(null)}>
          {banner.text}
        </Alert>
      )}

      {!settings.data?.usable && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Email is off. Notifications are still delivered in the application and appear under the
          bell — nothing is being lost.
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <FormControlLabel
          control={
            <Switch checked={form.enabled} onChange={(event) => set({ enabled: event.target.checked })} />
          }
          label="Email notifications as well as showing them in the application"
        />

        <Divider sx={{ my: 2 }} />

        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <TextField
              label="SMTP host"
              placeholder="smtp.example.com"
              value={form.host}
              onChange={(event) => set({ host: event.target.value })}
            />
          </Grid>
          <Grid item xs={6} md={2}>
            <TextField
              label="Port"
              value={form.port}
              onChange={(event) => set({ port: event.target.value.replace(/[^0-9]/g, '') })}
              helperText="587 for STARTTLS"
            />
          </Grid>
          <Grid item xs={6} md={4}>
            <FormControlLabel
              sx={{ mt: 1 }}
              control={
                <Switch
                  checked={form.startTls}
                  onChange={(event) => set({ startTls: event.target.checked })}
                />
              }
              label="Use STARTTLS"
            />
          </Grid>

          <Grid item xs={12} md={6}>
            <TextField
              label="Username"
              value={form.username}
              onChange={(event) => set({ username: event.target.value })}
              helperText="Leave empty for a relay that does not authenticate."
            />
          </Grid>
          <Grid item xs={12} md={6}>
            <TextField
              label="Password"
              type="password"
              value={form.password}
              onChange={(event) => {
                set({ password: event.target.value });
                setPasswordTouched(true);
              }}
              placeholder={settings.data?.passwordSet ? '•••••••• (unchanged)' : ''}
              helperText={
                settings.data?.passwordSet
                  ? 'A password is stored. Leave this empty to keep it.'
                  : 'Stored so the relay can be authenticated against. Never shown again.'
              }
            />
          </Grid>

          <Grid item xs={12} md={6}>
            <TextField
              label="From address"
              placeholder="inventory@example.com"
              value={form.fromAddress}
              onChange={(event) => set({ fromAddress: event.target.value })}
            />
          </Grid>
        </Grid>

        <Stack direction="row" justifyContent="flex-end" sx={{ mt: 2 }}>
          <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>
            Save
          </Button>
        </Stack>
      </Paper>

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Send a test
      </Typography>
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
          <TextField
            label="Send a test message to"
            placeholder="you@example.com"
            value={testTo}
            onChange={(event) => setTestTo(event.target.value)}
          />
          <Button
            variant="outlined"
            sx={{ flexShrink: 0 }}
            disabled={!testTo.trim() || !settings.data?.usable || sendTest.isPending}
            onClick={() => sendTest.mutate()}
          >
            Send test
          </Button>
        </Stack>
        <Typography variant="caption" color="text.secondary">
          Uses the saved settings, so save first. The result — including the relay's own error
          message if it refuses — is reported here rather than hidden in a log.
        </Typography>
      </Paper>
    </>
  );
}
