import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Chip,
  LinearProgress,
  Link,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { ImportBatch, ImportBatchDetail, ImportRow } from '../api/types';
import { EntityTable } from '../components/EntityTable';
import { PageHeader } from '../components/PageHeader';

const STATUS_COLOR: Record<string, 'default' | 'info' | 'success' | 'error'> = {
  PENDING: 'default',
  VALIDATED: 'info',
  COMMITTED: 'success',
  FAILED: 'error',
};

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString() : '—';
}

/**
 * Bulk import. Upload, look at exactly what will happen, then commit.
 *
 * The preview is the feature: an import that just runs leaves you discovering
 * six hundred half-right assets afterwards. Nothing is created until the commit
 * button is pressed, and the rows shown here are the same parsed rows that will
 * be applied — not a second reading of the file.
 */
export function ImportPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [selected, setSelected] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const batches = useQuery({
    queryKey: ['imports'],
    queryFn: () => api.get<ImportBatch[]>('/api/imports'),
  });

  const detail = useQuery({
    queryKey: ['import', selected],
    queryFn: () => api.get<ImportBatchDetail>(`/api/imports/${selected}`),
    enabled: selected != null,
  });

  const upload = useMutation({
    mutationFn: (file: File) => api.upload<ImportBatch>('/api/imports', file),
    onSuccess: (batch) => {
      setError(null);
      setSelected(batch.id);
      void queryClient.invalidateQueries({ queryKey: ['imports'] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not read that file.'),
  });

  const commit = useMutation({
    mutationFn: (id: number) => api.post<ImportBatch>(`/api/imports/${id}/commit`, {}),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['imports'] });
      void queryClient.invalidateQueries({ queryKey: ['import', selected] });
      void queryClient.invalidateQueries({ queryKey: ['assets'] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not commit that import.'),
  });

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

  const current = detail.data;
  const canCommit = current?.status === 'VALIDATED' && current.successCount > 0;

  return (
    <>
      <PageHeader
        title="Bulk import"
        subtitle="Load assets from a spreadsheet. Nothing is created until you have seen what will happen and committed it."
        actions={
          <Stack direction="row" spacing={1}>
            <Button onClick={() => void downloadTemplate()}>Download template</Button>
            <Button variant="contained" component="label" disabled={upload.isPending}>
              Upload a CSV
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
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {upload.isPending && <LinearProgress sx={{ mb: 2 }} />}

      {!current && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Start with the template — it has the column names this importer accepts and one filled
          example row. <Link component="button" onClick={() => void downloadTemplate()}>Download it</Link>{' '}
          , fill it in, and upload. Category and Location are matched by name and must already exist.
        </Alert>
      )}

      {current && (
        <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 }, mb: 2 }}>
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="h6">{current.filename}</Typography>
            <Chip size="small" color={STATUS_COLOR[current.status] ?? 'default'} label={current.status} />
            <Typography variant="body2" color="text.secondary">
              {current.rowCount} rows · {current.successCount} will import · {current.failureCount}{' '}
              skipped
            </Typography>
            <Box sx={{ flexGrow: 1 }} />
            {canCommit && (
              <Button
                variant="contained"
                disabled={commit.isPending}
                onClick={() => commit.mutate(current.id)}
              >
                Import {current.successCount} row{current.successCount === 1 ? '' : 's'}
              </Button>
            )}
          </Stack>

          {current.status === 'VALIDATED' && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              Nothing has been created yet. Rows marked with a problem will be skipped — the rest
              import unaffected.
            </Typography>
          )}
          {current.status === 'COMMITTED' && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              Imported. Skipped rows are kept below as the record of what did not come in.
            </Typography>
          )}
        </Paper>
      )}

      {current && (
        <Paper variant="outlined" sx={{ mb: 3 }}>
          <EntityTable
            columns={[
              { header: 'Line', render: (row: ImportRow) => row.rowNumber },
              {
                header: 'Status',
                render: (row: ImportRow) => (
                  <Chip
                    size="small"
                    variant={row.status === 'INVALID' ? 'filled' : 'outlined'}
                    color={
                      row.status === 'INVALID' ? 'error' : row.status === 'IMPORTED' ? 'success' : 'info'
                    }
                    label={row.status === 'VALID' ? 'Will import' : row.status}
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
            rows={current.rows}
            rowKey={(row) => row.rowNumber}
            loading={detail.isLoading}
            emptyMessage="That file had no data rows."
            cardTitle={(row) => `Line ${row.rowNumber}`}
            rowActions={(row) =>
              row.createdAssetId ? (
                <Button size="small" onClick={() => navigate(`/assets/${row.createdAssetId}`)}>
                  Open
                </Button>
              ) : null
            }
          />
        </Paper>
      )}

      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        Recent imports
      </Typography>
      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'File', render: (batch: ImportBatch) => batch.filename },
            {
              header: 'Status',
              render: (batch: ImportBatch) => (
                <Chip size="small" color={STATUS_COLOR[batch.status] ?? 'default'} label={batch.status} />
              ),
            },
            { header: 'Rows', align: 'right', render: (batch: ImportBatch) => batch.rowCount },
            { header: 'Imported', align: 'right', render: (batch: ImportBatch) => batch.successCount },
            { header: 'Skipped', align: 'right', render: (batch: ImportBatch) => batch.failureCount },
            { header: 'When', render: (batch: ImportBatch) => formatDate(batch.importedAt) },
            { header: 'By', secondary: true, render: (batch: ImportBatch) => batch.importedBy },
          ]}
          rows={batches.data ?? []}
          rowKey={(batch) => batch.id}
          loading={batches.isLoading}
          emptyMessage="No imports yet."
          cardTitle={(batch) => batch.filename}
          rowActions={(batch) => (
            <Button size="small" onClick={() => setSelected(batch.id)}>
              View
            </Button>
          )}
        />
      </Paper>
    </>
  );
}
