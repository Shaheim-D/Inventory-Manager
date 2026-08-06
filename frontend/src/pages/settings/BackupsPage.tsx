import { useState } from 'react';
import {
  Alert,
  AlertTitle,
  Button,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import BackupIcon from '@mui/icons-material/CloudDownloadOutlined';
import DownloadIcon from '@mui/icons-material/FileDownloadOutlined';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { PageHeader } from '../../components/PageHeader';
import { EntityTable, type Column } from '../../components/EntityTable';
import { when } from '../../format';

interface Artefact {
  name: string;
  sizeBytes: number;
  createdAt: string;
}

interface BackupSet {
  stamp: string;
  dump: Artefact | null;
  files: Artefact | null;
  complete: boolean;
}

function size(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

/**
 * Taking a backup without leaving the application.
 *
 * <p>The screen is deliberately plain about two things, because both are the
 * kind of detail that only hurts once: a backup is two files that belong
 * together, and a backup sitting on this server is not yet a backup.
 */
export function BackupsPage() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const backups = useQuery({
    queryKey: ['backups'],
    queryFn: () => api.get<BackupSet[]>('/api/admin/backups'),
  });

  const create = useMutation({
    mutationFn: () => api.post<BackupSet>('/api/admin/backups'),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['backups'] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'The backup could not be taken.'),
  });

  const remove = useMutation({
    mutationFn: (stamp: string) => api.del(`/api/admin/backups/${stamp}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['backups'] }),
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'That backup could not be removed.'),
  });

  const columns: Column<BackupSet>[] = [
    {
      header: 'Taken',
      render: (row) => (row.dump ?? row.files ? when((row.dump ?? row.files)!.createdAt) : row.stamp),
      secondary: true,
    },
    {
      header: 'Database',
      render: (row) =>
        row.dump ? size(row.dump.sizeBytes) : <Chip size="small" color="error" label="Missing" />,
    },
    {
      header: 'Attachments',
      render: (row) =>
        row.files ? size(row.files.sizeBytes) : <Chip size="small" color="error" label="Missing" />,
    },
    {
      header: 'Status',
      render: (row) =>
        row.complete ? (
          <Chip size="small" color="success" label="Complete" />
        ) : (
          <Chip size="small" color="error" label="Incomplete" />
        ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Backups"
        help="Take a copy of the database and the uploaded files on demand. Restoring is done from a shell with scripts/restore.sh — see the runbook."
        actions={
          <Button
            variant="contained"
            startIcon={<BackupIcon />}
            disabled={create.isPending}
            onClick={() => create.mutate()}
          >
            {create.isPending ? 'Taking backup…' : 'Take a backup now'}
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Alert severity="warning" sx={{ mb: 2 }}>
        <AlertTitle>A downloaded backup has no permissions</AlertTitle>
        A database backup contains every column of every row — costs, invoice numbers and
        password hashes included — regardless of what any role is allowed to see on screen.
        Treat a downloaded file as the most sensitive thing this system produces. Every backup
        taken and every download is recorded in the audit history.
      </Alert>

      <Alert severity="info" sx={{ mb: 3 }}>
        These are written to a volume on this server, beside the database they protect. That is
        not yet a backup: a copy has to reach somewhere the loss of this machine does not take
        with it. Download them, or point the nightly <code>scripts/backup.sh</code> off-box
        destination at this volume.
      </Alert>

      {create.isPending && <LinearProgress sx={{ mb: 2 }} />}

      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={backups.data ?? []}
          rowKey={(row) => row.stamp}
          loading={backups.isLoading}
          cardTitle={(row) => (row.dump ?? row.files ? when((row.dump ?? row.files)!.createdAt) : row.stamp)}
          emptyMessage="No backups taken from here yet. Nightly backups run from scripts/backup.sh and land wherever that is configured to put them; this screen only lists the ones taken here."
          rowActions={(row) => (
            <Stack direction="row" spacing={1}>
              {/* One file to carry, two files inside it. restore.sh takes the
                  zip directly, so a single download is a complete restore --
                  the pair is only ever split on the server, where nothing has
                  to remember to keep the halves together. */}
              <Button
                size="small"
                variant="outlined"
                startIcon={<DownloadIcon />}
                href={`/api/admin/backups/${row.stamp}/archive`}
                download
              >
                Download
              </Button>
              <Button
                size="small"
                color="error"
                disabled={remove.isPending}
                onClick={() => remove.mutate(row.stamp)}
              >
                Delete
              </Button>
            </Stack>
          )}
        />
      </Paper>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        A download is one <code>.zip</code> holding both halves — the database and the uploaded
        files. <code>scripts/restore.sh</code> takes that zip directly, so there is nothing to
        unpack by hand. They are two files on the server because that is the format the nightly
        job writes and the restore reads.
      </Typography>
    </>
  );
}
