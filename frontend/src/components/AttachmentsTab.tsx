import { useRef, useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Attachment, ReferenceEnums } from '../api/types';
import { EntityTable } from './EntityTable';
import { useAuth } from '../auth/AuthContext';

const CATEGORY_LABELS: Record<string, string> = {
  PHOTO: 'Photo',
  INVOICE: 'Invoice',
  PURCHASE_ORDER: 'Purchase order',
  MANUAL: 'Manual',
  SUPPORT_CONTRACT: 'Support contract',
  WARRANTY_DOCUMENT: 'Warranty document',
  CONFIG_BACKUP: 'Config backup',
  RECEIPT: 'Receipt',
  MISCELLANEOUS: 'Other',
};

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString() : '—';
}

/**
 * Files held against an asset. Downloads go through the API rather than a
 * direct link so the server can keep serving them as attachments — an uploaded
 * page rendered inline under this origin would run as whoever opened it.
 */
export function AttachmentsTab({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const { has } = useAuth();
  const canUpload = has('attachment:upload');
  const canDelete = has('attachment:delete');

  const fileInput = useRef<HTMLInputElement>(null);
  const [adding, setAdding] = useState(false);
  const [category, setCategory] = useState('MISCELLANEOUS');
  const [chosen, setChosen] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);

  const files = useQuery({
    queryKey: ['asset-attachments', assetId],
    queryFn: () => api.get<Attachment[]>(`/api/assets/${assetId}/attachments`),
  });

  const enums = useQuery({
    queryKey: ['reference-enums'],
    queryFn: () => api.get<ReferenceEnums>('/api/reference/enums'),
    enabled: canUpload,
  });

  const upload = useMutation({
    mutationFn: () =>
      api.upload(
        `/api/assets/${assetId}/attachments?fileCategory=${encodeURIComponent(category)}`,
        chosen!,
      ),
    onSuccess: () => {
      close();
      void queryClient.invalidateQueries({ queryKey: ['asset-attachments', assetId] });
      void queryClient.invalidateQueries({ queryKey: ['asset-audit', assetId] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not upload that file.'),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/assets/${assetId}/attachments/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['asset-attachments', assetId] });
      void queryClient.invalidateQueries({ queryKey: ['asset-audit', assetId] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not remove that file.'),
  });

  const close = () => {
    setAdding(false);
    setChosen(null);
    setCategory('MISCELLANEOUS');
    setError(null);
    if (fileInput.current) fileInput.current.value = '';
  };

  const download = async (attachment: Attachment) => {
    try {
      const blob = await api.getBlob(`/api/assets/${assetId}/attachments/${attachment.id}`);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = attachment.originalFilename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Could not download that file.');
    }
  };

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {canUpload && (
        <Stack direction="row" justifyContent="flex-end" sx={{ mb: 2 }}>
          <Button variant="contained" onClick={() => setAdding(true)}>
            Upload a file
          </Button>
        </Stack>
      )}

      <Paper variant="outlined">
        <EntityTable
          columns={[
            {
              header: 'Kind',
              render: (file: Attachment) => (
                <Chip
                  size="small"
                  variant="outlined"
                  label={CATEGORY_LABELS[file.fileCategory] ?? file.fileCategory}
                />
              ),
            },
            { header: 'File', render: (file: Attachment) => file.originalFilename },
            { header: 'Uploaded', render: (file: Attachment) => formatDate(file.uploadedAt) },
            { header: 'By', secondary: true, render: (file: Attachment) => file.uploadedBy },
          ]}
          rows={files.data ?? []}
          rowKey={(file) => file.id}
          loading={files.isLoading}
          emptyMessage="No files yet. Photos, invoices, manuals, and config backups all belong here."
          cardTitle={(file) => file.originalFilename}
          rowActions={(file) => (
            <>
              <Button size="small" onClick={() => void download(file)}>
                Download
              </Button>
              {canDelete && (
                <Button size="small" color="error" onClick={() => remove.mutate(file.id)}>
                  Remove
                </Button>
              )}
            </>
          )}
        />
      </Paper>

      <Dialog open={adding} onClose={close} fullWidth maxWidth="sm">
        <DialogTitle>Upload a file</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              select
              label="Kind of file"
              value={category}
              onChange={(event) => setCategory(event.target.value)}
            >
              {(enums.data?.attachmentCategories ?? Object.keys(CATEGORY_LABELS)).map((value) => (
                <MenuItem key={value} value={value}>
                  {CATEGORY_LABELS[value] ?? value}
                </MenuItem>
              ))}
            </TextField>

            <Button variant="outlined" component="label">
              {chosen ? chosen.name : 'Choose a file'}
              <input
                ref={fileInput}
                type="file"
                hidden
                onChange={(event) => setChosen(event.target.files?.[0] ?? null)}
              />
            </Button>

            <Typography variant="caption" color="text.secondary">
              Up to 25 MB. Files are stored on the server alongside the database and are covered by
              the same backup.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Cancel</Button>
          <Button variant="contained" disabled={!chosen || upload.isPending} onClick={() => upload.mutate()}>
            Upload
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
