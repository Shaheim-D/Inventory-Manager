import { useState } from 'react';
import {
  Alert,
  AlertTitle,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';

interface Refusal {
  id: number;
  label: string;
  reason: string;
}

interface BulkResult {
  removed: number[];
  refused: Refusal[];
}

interface Props {
  /** The bulk endpoint, e.g. `/api/assets/bulk-delete`. */
  endpoint: string;
  selected: Set<string | number>;
  onClear: () => void;
  /** Query keys to refetch once rows have gone. */
  invalidate: string[][];
  /** "asset" / "location" — used in the confirmation sentence. */
  noun: string;
  disabled?: boolean;
}

/**
 * Select rows, then remove them — one bar, used by every list that supports it.
 *
 * <p>Three things it insists on, all of them because bulk delete is where
 * somebody removes forty rows without reading forty confirmations:
 *
 * <ul>
 *   <li>It says how many and what kind before doing anything.
 *   <li>It says where they go, because "delete" reads as permanent and here it
 *       is not — everything lands in the Recycle Bin and comes back.
 *   <li>It reports refusals individually. Nineteen of twenty going through is a
 *       success, and the one that did not needs its own reason on screen rather
 *       than a failed batch nobody can interpret.
 * </ul>
 */
export function BulkDeleteBar({
  endpoint,
  selected,
  onClear,
  invalidate,
  noun,
  disabled,
}: Props) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<BulkResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const remove = useMutation({
    mutationFn: () =>
      api.post<BulkResult>(endpoint, { ids: Array.from(selected).map(Number) }),
    onSuccess: (data) => {
      setError(null);
      setResult(data);
      setConfirming(false);
      onClear();
      invalidate.forEach((key) => void queryClient.invalidateQueries({ queryKey: key }));
    },
    onError: (caught) => {
      setConfirming(false);
      setError(caught instanceof ApiError ? caught.message : 'Those could not be removed.');
    },
  });

  const count = selected.size;
  const plural = count === 1 ? noun : `${noun}s`;

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {result && result.refused.length > 0 && (
        <Alert severity="warning" sx={{ mb: 2 }} onClose={() => setResult(null)}>
          <AlertTitle>
            {result.removed.length} removed, {result.refused.length} could not go
          </AlertTitle>
          <Stack spacing={0.5} sx={{ mt: 1 }}>
            {result.refused.map((refusal) => (
              <Typography key={refusal.id} variant="body2">
                <strong>{refusal.label}</strong> — {refusal.reason}
              </Typography>
            ))}
          </Stack>
        </Alert>
      )}

      {result && result.refused.length === 0 && result.removed.length > 0 && (
        <Alert severity="success" sx={{ mb: 2 }} onClose={() => setResult(null)}>
          {result.removed.length} {result.removed.length === 1 ? noun : `${noun}s`} moved to the
          Recycle Bin.
        </Alert>
      )}

      {count > 0 && (
        <Paper
          variant="outlined"
          sx={{
            mb: 2,
            px: 2,
            py: 1.5,
            display: 'flex',
            alignItems: 'center',
            gap: 2,
            flexWrap: 'wrap',
            bgcolor: 'action.hover',
          }}
        >
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {count} {plural} selected
          </Typography>
          <Button size="small" onClick={onClear}>
            Clear
          </Button>
          <Button
            size="small"
            color="error"
            variant="contained"
            startIcon={<DeleteOutlineIcon />}
            disabled={disabled || remove.isPending}
            onClick={() => setConfirming(true)}
          >
            {remove.isPending ? 'Removing…' : `Delete ${count}`}
          </Button>
        </Paper>
      )}

      <Dialog open={confirming} onClose={() => setConfirming(false)}>
        <DialogTitle>
          Delete {count} {plural}?
        </DialogTitle>
        <DialogContent>
          <DialogContentText>
            They move to the <strong>Recycle Bin</strong>, where they can be recovered at any
            time. Nothing is erased.
          </DialogContentText>
          <DialogContentText sx={{ mt: 2 }}>
            Anything that cannot go — a location with locations inside it, a category with assets
            still filed under it — is left alone and reported back.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Cancel</Button>
          <Button color="error" variant="contained" onClick={() => remove.mutate()}>
            Delete {count}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
