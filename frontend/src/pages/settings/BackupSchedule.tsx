import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  FormControlLabel,
  Grid,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { when } from '../../format';

export interface BackupSettings {
  scheduleEnabled: boolean;
  scheduleHour: number;
  scheduleMinute: number;
  retentionDays: number | null;
  destinationType: 'LOCAL_PATH' | 'SFTP' | 'S3' | null;
  destinationPath: string | null;
  destinationCredentialsRef: string | null;
  lastRunAt: string | null;
  lastRunStatus: 'SUCCESS' | 'FAILED' | null;
  lastRunDetail: string | null;
  environmentFallback: {
    destinationType: string | null;
    destinationPath: string | null;
    retentionDays: number | null;
  };
}

const DESTINATIONS = [
  { value: 'LOCAL_PATH', label: 'A mounted path', hint: 'A NAS share or second disk already mounted on this VM — anything that is not the disk PostgreSQL runs on.' },
  { value: 'SFTP', label: 'SFTP', hint: 'user@host:/path. The credential box below names an .env entry holding the key path, never the key itself.' },
  { value: 'S3', label: 'S3 or compatible', hint: 's3://bucket/prefix. Works with AWS, Backblaze B2, MinIO — anything speaking S3.' },
] as const;

/**
 * When the nightly backup runs, where the copies go, and whether last night
 * worked.
 *
 * <p>These used to be `BACKUP_*` in `.env` plus a crontab line, which meant the
 * one setting deciding whether this system can be recovered was the one setting
 * an administrator could not see. They are a row now.
 *
 * <p><b>Saving here does not make the application take the backup.</b>
 * `scripts/backup.sh` still does, from the host, because that is what keeps
 * working on the morning the application will not start — which is the morning
 * last night's dump matters. This screen writes the schedule it reads.
 *
 * <p>Anything left blank falls back to `.env`, and the fields prefill from
 * those values, so an installation that already had them configured turns the
 * schedule on in one click rather than by re-typing what it already knew.
 */
export function BackupSchedule() {
  const queryClient = useQueryClient();

  const settings = useQuery({
    queryKey: ['backup-settings'],
    queryFn: () => api.get<BackupSettings>('/api/admin/backups/settings'),
  });

  const [form, setForm] = useState({
    scheduleEnabled: false,
    time: '02:15',
    retentionDays: '',
    destinationType: '' as '' | 'LOCAL_PATH' | 'SFTP' | 'S3',
    destinationPath: '',
    destinationCredentialsRef: '',
  });
  const [banner, setBanner] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    const data = settings.data;
    if (!data) return;
    const fallback = data.environmentFallback;
    setForm({
      scheduleEnabled: data.scheduleEnabled,
      time: `${String(data.scheduleHour).padStart(2, '0')}:${String(data.scheduleMinute).padStart(2, '0')}`,
      // Prefilled from .env where this row says nothing, so turning the
      // schedule on does not mean re-entering what the deployment already knew.
      retentionDays: String(data.retentionDays ?? fallback.retentionDays ?? 180),
      destinationType: (data.destinationType ?? fallback.destinationType ?? '') as typeof form.destinationType,
      destinationPath: data.destinationPath ?? fallback.destinationPath ?? '',
      destinationCredentialsRef: data.destinationCredentialsRef ?? '',
    });
  }, [settings.data]);

  const set = (patch: Partial<typeof form>) => setForm((current) => ({ ...current, ...patch }));

  const save = useMutation({
    mutationFn: () => {
      const [hour, minute] = form.time.split(':');
      return api.put<BackupSettings>('/api/admin/backups/settings', {
        scheduleEnabled: form.scheduleEnabled,
        scheduleHour: Number(hour),
        scheduleMinute: Number(minute),
        retentionDays: form.retentionDays ? Number(form.retentionDays) : null,
        destinationType: form.destinationType || null,
        destinationPath: form.destinationPath.trim() || null,
        destinationCredentialsRef: form.destinationCredentialsRef.trim() || null,
      });
    },
    onSuccess: () => {
      setBanner({ kind: 'success', text: 'Saved.' });
      void queryClient.invalidateQueries({ queryKey: ['backup-settings'] });
    },
    onError: (caught) =>
      setBanner({
        kind: 'error',
        text: caught instanceof ApiError ? caught.message : 'Could not save the schedule.',
      }),
  });

  const chosen = DESTINATIONS.find((d) => d.value === form.destinationType);
  const data = settings.data;

  return (
    <Paper variant="outlined" sx={{ p: 2.5, mb: 3 }}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ mb: 1 }}>
        <Typography variant="h6">Nightly backup</Typography>
        <LastRun data={data} />
      </Stack>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        The backup itself is taken by <code>scripts/backup.sh</code> on this VM, not by the
        application — so it still runs on a morning when the application will not start, which is
        the morning it matters. This is where it reads its schedule.
      </Typography>

      {banner && (
        <Alert severity={banner.kind} sx={{ mb: 2 }} onClose={() => setBanner(null)}>
          {banner.text}
        </Alert>
      )}

      <FormControlLabel
        control={
          <Switch
            checked={form.scheduleEnabled}
            onChange={(event) => set({ scheduleEnabled: event.target.checked })}
          />
        }
        label="Back up every night"
      />

      <Grid container spacing={2} sx={{ mt: 0.5 }}>
        <Grid item xs={12} sm={4}>
          <TextField
            fullWidth
            type="time"
            label="At"
            value={form.time}
            onChange={(event) => set({ time: event.target.value })}
            helperText="This VM's local time"
            InputLabelProps={{ shrink: true }}
          />
        </Grid>
        <Grid item xs={12} sm={4}>
          <TextField
            fullWidth
            type="number"
            label="Keep for"
            value={form.retentionDays}
            onChange={(event) => set({ retentionDays: event.target.value })}
            helperText="Days. 180 is six months."
            inputProps={{ min: 1, max: 3650 }}
          />
        </Grid>
        <Grid item xs={12} sm={4}>
          <TextField
            select
            fullWidth
            label="Copy to"
            value={form.destinationType}
            onChange={(event) => set({ destinationType: event.target.value as typeof form.destinationType })}
          >
            {DESTINATIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
        </Grid>

        <Grid item xs={12} sm={form.destinationType === 'LOCAL_PATH' ? 12 : 7}>
          <TextField
            fullWidth
            label="Destination"
            value={form.destinationPath}
            onChange={(event) => set({ destinationPath: event.target.value })}
            helperText={chosen?.hint ?? 'Choose where the copies go.'}
          />
        </Grid>
        {form.destinationType !== 'LOCAL_PATH' && form.destinationType !== '' && (
          <Grid item xs={12} sm={5}>
            <TextField
              fullWidth
              label="Credential variable"
              value={form.destinationCredentialsRef}
              onChange={(event) => set({ destinationCredentialsRef: event.target.value })}
              helperText="The NAME of an .env entry, e.g. BACKUP_SSH_KEY — never the secret itself"
            />
          </Grid>
        )}
      </Grid>

      <Alert severity="info" sx={{ mt: 2 }}>
        Cron calls <code>backup.sh --if-due</code> hourly and it does nothing unless the time above
        has passed without a run — so a VM that was powered off overnight backs up at the next hour
        instead of skipping a day. Install the entry once:
        <Typography component="pre" variant="caption" sx={{ mt: 1, mb: 0, overflowX: 'auto' }}>
          5 * * * * /opt/inventory-manager/scripts/backup.sh --if-due &gt;&gt; /var/log/im-backup.log 2&gt;&amp;1
        </Typography>
      </Alert>

      <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 2 }}>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? 'Saving…' : 'Save schedule'}
        </Button>
        {data?.lastRunDetail && (
          <Typography variant="caption" color="text.secondary">
            Last run: {data.lastRunDetail}
          </Typography>
        )}
      </Stack>
    </Paper>
  );
}

/**
 * The half of this screen that actually gets looked at. A schedule whose result
 * nobody can see is a schedule nobody trusts, and a blank space reads as
 * success — so "never run" is stated rather than left empty.
 */
function LastRun({ data }: { data: BackupSettings | undefined }) {
  if (!data) return null;
  if (!data.lastRunAt) {
    return <Chip size="small" color="default" label="Never run" />;
  }
  const failed = data.lastRunStatus === 'FAILED';
  return (
    <Chip
      size="small"
      color={failed ? 'error' : 'success'}
      label={`${failed ? 'Failed' : 'Succeeded'} ${when(data.lastRunAt)}`}
    />
  );
}
