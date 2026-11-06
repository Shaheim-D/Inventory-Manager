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
import EditOutlinedIcon from '@mui/icons-material/EditOutlined';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import { BulkEditDialog } from './BulkEditDialog';
import type { BulkEditOption } from './BulkEditDialog';

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
  /**
   * The delete endpoint, e.g. `/api/assets/bulk-delete`. Omitted for a viewer
   * who may edit but not delete, which hides the Delete button rather than
   * offering one that would be refused.
   */
  endpoint?: string;
  selected: Set<string | number>;
  onClear: () => void;
  /** Query keys to refetch once rows have gone. */
  invalidate: string[][];
  /** "asset" / "location" — used in the confirmation sentence. */
  noun: string;
  /**
   * The plural, when it is not just the singular plus an "s".
   *
   * <p>Here because `${noun}s` produced "2 categorys selected" on screen.
   * English plurals are not a rule this can derive, so the one caller that
   * needs it says so rather than every caller pretending the rule works.
   */
  pluralNoun?: string;
  disabled?: boolean;
  /**
   * Turns on "Edit selected" beside Delete, pointing at this base path (e.g.
   * `/api/assets`). Omitted on a list that has no bulk edit, so the bar stays
   * exactly what it was for those.
   */
  editBasePath?: string;
  /** Passed through to the edit dialog — choices for fields that reference something. */
  editOptions?: Record<string, BulkEditOption[]>;
  editDateFields?: string[];
}

/**
 * Select rows, then do something to all of them — one bar, used by every list
 * that supports it.
 *
 * <p>Two actions share it because they share a selection: removing the rows,
 * and editing a field across them. Which buttons appear follows what the viewer
 * may do, so somebody who can edit but not delete gets the one they can use
 * rather than a button that would be refused.
 *
 * <p>Three things it insists on, all of them because bulk anything is where
 * somebody changes forty rows without reading forty confirmations:
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
export function BulkActionBar({
  endpoint,
  selected,
  onClear,
  invalidate,
  noun,
  pluralNoun,
  disabled,
  editBasePath,
  editOptions,
  editDateFields,
}: Props) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [editing, setEditing] = useState(false);
  const [result, setResult] = useState<BulkResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const remove = useMutation({
    mutationFn: () =>
      api.post<BulkResult>(endpoint!, { ids: Array.from(selected).map(Number) }),
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
  const many = pluralNoun ?? `${noun}s`;
  const plural = count === 1 ? noun : many;

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mt: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {result && result.refused.length > 0 && (
        <Alert severity="warning" sx={{ mt: 2 }} onClose={() => setResult(null)}>
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
        <Alert severity="success" sx={{ mt: 2 }} onClose={() => setResult(null)}>
          {result.removed.length} {result.removed.length === 1 ? noun : many} moved to the
          Recycle Bin.
        </Alert>
      )}

      {count > 0 && (
        <Paper
          variant="outlined"
          sx={{
            mt: 2,
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
          {editBasePath && (
            <Button
              size="small"
              variant="outlined"
              startIcon={<EditOutlinedIcon />}
              disabled={disabled}
              onClick={() => setEditing(true)}
            >
              Edit {count}
            </Button>
          )}
          {endpoint && (
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
          )}
        </Paper>
      )}

      {editBasePath && (
        <BulkEditDialog
          open={editing}
          onClose={() => setEditing(false)}
          basePath={editBasePath}
          selected={selected}
          noun={noun}
          pluralNoun={pluralNoun}
          invalidate={invalidate}
          options={editOptions}
          dateFields={editDateFields}
          onApplied={onClear}
        />
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
