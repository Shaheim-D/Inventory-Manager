import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
  Stack,
  Typography,
} from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { ImportBatchDetail, ImportRow } from '../api/types';
import { EntityTable } from './EntityTable';

/**
 * Import from a spreadsheet, without leaving the Assets page.
 *
 * The staged file is deliberately not kept: an import is something you do, and
 * the assets it created are the record of it — each with its own audit history.
 * Closing this dialog throws the staging away.
 */
export function ImportDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [batch, setBatch] = useState<ImportBatchDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refreshAssets = () => {
    void queryClient.invalidateQueries({ queryKey: ['assets'] });
  };

  const fail = (caught: unknown, fallback: string) =>
    setError(caught instanceof ApiError ? caught.message : fallback);

  const upload = useMutation({
    mutationFn: (file: File) => api.upload<ImportBatchDetail>('/api/imports', file),
    onSuccess: (result) => {
      setError(null);
      setBatch(result);
    },
    onError: (caught) => fail(caught, 'Could not read that file.'),
  });

  const importAll = useMutation({
    mutationFn: () => api.post<ImportBatchDetail>(`/api/imports/${batch!.id}/commit`, {}),
    onSuccess: (result) => {
      setError(null);
      setBatch(result);
      refreshAssets();
    },
    onError: (caught) => fail(caught, 'Could not import those rows.'),
  });

  const importRow = useMutation({
    mutationFn: (rowNumber: number) =>
      api.post<ImportBatchDetail>(`/api/imports/${batch!.id}/rows/${rowNumber}/commit`, {}),
    onSuccess: (result) => {
      setError(null);
      setBatch(result);
      refreshAssets();
    },
    onError: (caught) => fail(caught, 'Could not import that row.'),
  });

  const recheck = useMutation({
    mutationFn: () => api.post<ImportBatchDetail>(`/api/imports/${batch!.id}/revalidate`, {}),
    onSuccess: (result) => {
      setError(null);
      setBatch(result);
    },
    onError: (caught) => fail(caught, 'Could not check the file again.'),
  });

  const close = () => {
    // Nothing to keep. The assets that were created are the record.
    if (batch) void api.del(`/api/imports/${batch.id}`).catch(() => undefined);
    setBatch(null);
    setError(null);
    onClose();
  };

  const downloadTemplate = async () => {
    try {
      const blob = await api.getBlob('/api/imports/template');
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'inventory-import-template.csv';
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      setError('Could not download the template.');
    }
  };

  const rows = batch?.rows ?? [];
  const waiting = rows.filter((row) => row.status === 'VALID').length;
  const imported = rows.filter((row) => row.status === 'IMPORTED').length;
  const failed = rows.filter((row) => row.status === 'INVALID').length;
  const busy = upload.isPending || importAll.isPending || importRow.isPending || recheck.isPending;

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth={batch ? 'lg' : 'sm'}>
      <DialogTitle>{batch ? `Import — ${batch.filename}` : 'Import assets'}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        {busy && <LinearProgress sx={{ mb: 2 }} />}

        {!batch && (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Start from the template — it carries the column names this importer accepts and one
              filled example row. Category and Location are matched by name and must already exist.
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Button variant="outlined" fullWidth onClick={() => void downloadTemplate()}>
                Download template
              </Button>
              <Button variant="contained" fullWidth component="label" disabled={upload.isPending}>
                Choose a CSV
                <input
                  type="file"
                  accept=".csv,text/csv"
                  hidden
                  onChange={(event) => {
                    const file = event.target.files?.[0];
                    if (file) upload.mutate(file);
                    event.target.value = '';
                  }}
                />
              </Button>
            </Stack>
            <Typography variant="caption" color="text.secondary">
              Nothing is created when you upload. You will see every row and choose what to import.
            </Typography>
          </Stack>
        )}

        {batch && (
          <>
            <Stack
              direction="row"
              spacing={1.5}
              alignItems="center"
              flexWrap="wrap"
              useFlexGap
              sx={{ mb: 2 }}
            >
              <Chip size="small" color="info" variant="outlined" label={`${waiting} to import`} />
              {imported > 0 && <Chip size="small" color="success" label={`${imported} imported`} />}
              {failed > 0 && <Chip size="small" color="error" label={`${failed} with problems`} />}
              <Box sx={{ flexGrow: 1 }} />
              {failed > 0 && (
                <Button size="small" onClick={() => recheck.mutate()} disabled={busy}>
                  Check again
                </Button>
              )}
              <Button
                variant="contained"
                onClick={() => importAll.mutate()}
                disabled={busy || waiting === 0}
              >
                Import all {waiting > 0 ? waiting : ''}
              </Button>
            </Stack>

            {failed > 0 && (
              <Alert severity="info" sx={{ mb: 2 }}>
                Fix the underlying data — create a missing location, say — then use{' '}
                <strong>Check again</strong>. There is no need to upload the file a second time.
              </Alert>
            )}

            <EntityTable
              columns={[
                { header: 'Line', render: (row: ImportRow) => row.rowNumber },
                {
                  header: 'Status',
                  render: (row: ImportRow) => (
                    <Chip
                      size="small"
                      variant={row.status === 'VALID' ? 'outlined' : 'filled'}
                      color={
                        row.status === 'INVALID'
                          ? 'error'
                          : row.status === 'IMPORTED'
                            ? 'success'
                            : 'info'
                      }
                      label={row.status === 'VALID' ? 'Ready' : row.status === 'IMPORTED' ? 'Imported' : 'Problem'}
                    />
                  ),
                },
                {
                  header: 'Asset',
                  render: (row: ImportRow) =>
                    row.data.name || row.data.serial_number || row.data.asset_tag || '—',
                },
                { header: 'Category', render: (row: ImportRow) => row.data.category ?? '—' },
                { header: 'Location', render: (row: ImportRow) => row.data.location ?? '—' },
                {
                  header: 'Problem',
                  render: (row: ImportRow) =>
                    row.errorMessage ? (
                      <Typography variant="body2" color="error">
                        {row.errorMessage}
                      </Typography>
                    ) : (
                      '—'
                    ),
                },
              ]}
              rows={rows}
              rowKey={(row) => row.rowNumber}
              emptyMessage="That file had no data rows."
              cardTitle={(row) => `Line ${row.rowNumber}`}
              rowActions={(row) =>
                row.status === 'IMPORTED' ? (
                  <Button
                    size="small"
                    onClick={() => {
                      close();
                      navigate(`/assets/${row.createdAssetId}`);
                    }}
                  >
                    View
                  </Button>
                ) : row.status === 'VALID' ? (
                  <Button size="small" disabled={busy} onClick={() => importRow.mutate(row.rowNumber)}>
                    Import
                  </Button>
                ) : null
              }
            />
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={close}>{batch && imported > 0 ? 'Done' : 'Cancel'}</Button>
      </DialogActions>
    </Dialog>
  );
}
