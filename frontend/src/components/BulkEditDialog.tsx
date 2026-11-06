import { useMemo, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';

/**
 * Change the same thing about many rows, with a card that says what will happen
 * before anything does.
 *
 * <p>Two steps on purpose. The first picks fields and values; the second shows
 * every before → after the server computed and asks for a decision. The card is
 * rendered from the preview endpoint, which runs the same planning code the
 * update will run — so it describes the actual work rather than the screen's
 * guess at it.
 *
 * <p>The card is also binding. What it displayed is sent back with the
 * confirmation, and a row somebody else has changed in between is refused with
 * that as the reason instead of being quietly overwritten.
 *
 * <p>Grouped rather than listed row by row: a selection of four hundred assets
 * all moving to the same date is one sentence, not four hundred. Rows that
 * differ — because they started somewhere else, or are already there, or cannot
 * go — are the ones named individually, because those are the ones worth
 * reading.
 */

export interface BulkEditField {
  field: string;
  label: string;
}

/** A value the picker can offer for a field that references something. */
export interface BulkEditOption {
  id: number | string;
  label: string;
}

interface PlannedChange {
  field: string;
  label: string;
  from: string;
  to: string;
  noop: boolean;
}

interface PlannedRow {
  id: number;
  label: string;
  refusal: string | null;
  changes: PlannedChange[];
}

interface Plan {
  applied: boolean;
  fields: string[];
  rows: PlannedRow[];
  changingCount: number;
  unchangedCount: number;
  refusedCount: number;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** e.g. `/api/assets` — preview and apply hang off it. */
  basePath: string;
  selected: Set<string | number>;
  noun: string;
  pluralNoun?: string;
  /** Query keys to refetch once rows have changed. */
  invalidate: string[][];
  /**
   * Choices for fields that reference something else, keyed by field name —
   * locations, support levels, lifecycle states. A field absent from here is
   * typed into rather than chosen.
   */
  options?: Record<string, BulkEditOption[]>;
  /** Fields whose value is a date, so the input offers a date picker. */
  dateFields?: string[];
  onApplied?: () => void;
}

export function BulkEditDialog({
  open,
  onClose,
  basePath,
  selected,
  noun,
  pluralNoun,
  invalidate,
  options = {},
  dateFields = [],
  onApplied,
}: Props) {
  const queryClient = useQueryClient();
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [plan, setPlan] = useState<Plan | null>(null);
  const [error, setError] = useState<string | null>(null);

  const ids = useMemo(() => Array.from(selected).map(Number), [selected]);
  const count = ids.length;
  const many = pluralNoun ?? `${noun}s`;
  const plural = count === 1 ? noun : many;

  // What this viewer may be offered, which is not the same as what exists: a
  // field somebody cannot see is never in this list.
  const fields = useQuery({
    queryKey: ['bulk-edit-fields', basePath],
    queryFn: () => api.get<BulkEditField[]>(`${basePath}/bulk-update/fields`),
    enabled: open,
  });

  const preview = useMutation({
    mutationFn: () =>
      api.post<Plan>(`${basePath}/bulk-update/preview`, { ids, changes: chosen }),
    onSuccess: (data) => {
      setError(null);
      setPlan(data);
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not work out those changes.'),
  });

  const apply = useMutation({
    mutationFn: () =>
      api.post<Plan>(`${basePath}/bulk-update`, {
        ids,
        changes: chosen,
        // Exactly what the card displayed. The server refuses any row whose
        // stored value no longer matches, which is what makes confirming a
        // decision about something real rather than about a stale screen.
        expected: Object.fromEntries(
          (plan?.rows ?? []).map((row) => [
            row.id,
            Object.fromEntries(row.changes.map((change) => [change.field, change.from])),
          ]),
        ),
      }),
    onSuccess: (data) => {
      setPlan(data);
      invalidate.forEach((key) => void queryClient.invalidateQueries({ queryKey: key }));
      onApplied?.();
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Those changes could not be made.'),
  });

  function reset() {
    setChosen({});
    setPlan(null);
    setError(null);
    onClose();
  }

  const chosenFields = Object.keys(chosen);
  const applied = plan?.applied ?? false;

  return (
    <Dialog open={open} onClose={reset} maxWidth="md" fullWidth>
      <DialogTitle>
        {applied
          ? 'Done'
          : plan
            ? `Review ${plan.changingCount} ${plan.changingCount === 1 ? noun : many}`
            : `Edit ${count} ${plural}`}
      </DialogTitle>

      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        {/* ---------- step one: what to change ---------- */}
        {!plan && (
          <>
            <DialogContentText sx={{ mb: 2 }}>
              Pick what to change. Anything you leave alone stays exactly as it is on every{' '}
              {noun} — this edits only the fields you fill in.
            </DialogContentText>

            <Stack spacing={2}>
              <TextField
                select
                label="Add a field to change"
                value=""
                onChange={(event) => setChosen({ ...chosen, [event.target.value]: '' })}
                helperText={
                  fields.isLoading
                    ? 'Loading…'
                    : 'Identifiers like serial number are not listed: one value cannot apply to many.'
                }
              >
                {(fields.data ?? [])
                  .filter((field) => !(field.field in chosen))
                  .map((field) => (
                    <MenuItem key={field.field} value={field.field}>
                      {field.label}
                    </MenuItem>
                  ))}
              </TextField>

              {chosenFields.map((name) => {
                const field = fields.data?.find((candidate) => candidate.field === name);
                const choices = options[name];
                return (
                  <Stack key={name} direction="row" spacing={1} alignItems="flex-start">
                    {choices ? (
                      <TextField
                        select
                        label={field?.label ?? name}
                        value={chosen[name]}
                        onChange={(event) => setChosen({ ...chosen, [name]: event.target.value })}
                      >
                        {choices.map((choice) => (
                          <MenuItem key={choice.id} value={String(choice.id)}>
                            {choice.label}
                          </MenuItem>
                        ))}
                      </TextField>
                    ) : (
                      <TextField
                        label={field?.label ?? name}
                        type={dateFields.includes(name) ? 'date' : 'text'}
                        InputLabelProps={dateFields.includes(name) ? { shrink: true } : undefined}
                        value={chosen[name]}
                        onChange={(event) => setChosen({ ...chosen, [name]: event.target.value })}
                        helperText="Leave empty to clear this field on every row."
                      />
                    )}
                    <Button
                      size="small"
                      sx={{ mt: 1 }}
                      onClick={() => {
                        const next = { ...chosen };
                        delete next[name];
                        setChosen(next);
                      }}
                    >
                      Remove
                    </Button>
                  </Stack>
                );
              })}
            </Stack>
          </>
        )}

        {/* ---------- step two: the confirmation card ---------- */}
        {plan && <PlanCard plan={plan} noun={noun} many={many} />}
      </DialogContent>

      <DialogActions>
        {applied ? (
          <Button variant="contained" onClick={reset}>
            Close
          </Button>
        ) : plan ? (
          <>
            {/* Deliberately not "Cancel": the question on screen is whether to
                go ahead now, and "not ready" is the honest answer to it. It
                goes back to the fields rather than discarding the work. */}
            <Button onClick={() => setPlan(null)}>Not ready</Button>
            <Button
              variant="contained"
              disabled={apply.isPending || plan.changingCount === 0}
              onClick={() => apply.mutate()}
            >
              {apply.isPending ? 'Applying…' : `Confirm ${plan.changingCount} change${
                plan.changingCount === 1 ? '' : 's'
              }`}
            </Button>
          </>
        ) : (
          <>
            <Button onClick={reset}>Cancel</Button>
            <Button
              variant="contained"
              disabled={chosenFields.length === 0 || preview.isPending}
              onClick={() => preview.mutate()}
            >
              {preview.isPending ? 'Checking…' : 'Review changes'}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}

/**
 * The card itself: what will change, grouped so that four hundred identical
 * moves read as one line and the exceptions stand out.
 */
function PlanCard({ plan, noun, many }: { plan: Plan; noun: string; many: string }) {
  const [showAll, setShowAll] = useState(false);

  const changing = plan.rows.filter(
    (row) => !row.refusal && row.changes.some((change) => !change.noop),
  );
  const unchanged = plan.rows.filter(
    (row) => !row.refusal && !row.changes.some((change) => !change.noop),
  );
  const refused = plan.rows.filter((row) => row.refusal);

  // One line per (field, from → to) combination. Most bulk edits produce a
  // single group, which is the whole summary somebody needs.
  const groups = new Map<string, { change: PlannedChange; rows: PlannedRow[] }>();
  for (const row of changing) {
    for (const change of row.changes) {
      if (change.noop) continue;
      const key = `${change.field}\u0000${change.from}\u0000${change.to}`;
      const existing = groups.get(key);
      if (existing) existing.rows.push(row);
      else groups.set(key, { change, rows: [row] });
    }
  }

  return (
    <>
      {plan.applied ? (
        <Alert severity={refused.length ? 'warning' : 'success'} sx={{ mb: 2 }}>
          <AlertTitle>
            {changing.length} {changing.length === 1 ? noun : many} updated
            {refused.length > 0 && `, ${refused.length} could not be`}
          </AlertTitle>
        </Alert>
      ) : (
        <DialogContentText sx={{ mb: 2 }}>
          This is what will happen. Nothing has changed yet.
        </DialogContentText>
      )}

      <Stack spacing={1.5}>
        {Array.from(groups.values()).map(({ change, rows }) => (
          <Box key={`${change.field}-${change.from}-${change.to}`}>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
              <Chip size="small" label={`${rows.length} ${rows.length === 1 ? noun : many}`} />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {change.label}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {displayValue(change.from)}
              </Typography>
              <ArrowForwardIcon fontSize="small" color="disabled" />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {displayValue(change.to)}
              </Typography>
            </Stack>
            {rows.length <= 5 && (
              <Typography variant="caption" color="text.secondary">
                {rows.map((row) => row.label).join(', ')}
              </Typography>
            )}
          </Box>
        ))}
      </Stack>

      {unchanged.length > 0 && (
        <Alert severity="info" sx={{ mt: 2 }}>
          {unchanged.length} already {unchanged.length === 1 ? 'has' : 'have'} these values and{' '}
          {plan.applied ? 'were' : 'will be'} left alone.
        </Alert>
      )}

      {refused.length > 0 && (
        <Alert severity="warning" sx={{ mt: 2 }}>
          <AlertTitle>
            {refused.length} cannot {plan.applied ? 'be changed' : 'go'}
          </AlertTitle>
          <Stack spacing={0.5} sx={{ mt: 1 }}>
            {refused.map((row) => (
              <Typography key={row.id} variant="body2">
                <strong>{row.label}</strong> — {row.refusal}
              </Typography>
            ))}
          </Stack>
        </Alert>
      )}

      {changing.length > 5 && (
        <>
          <Divider sx={{ my: 2 }} />
          <Button size="small" onClick={() => setShowAll(!showAll)}>
            {showAll ? 'Hide the full list' : `Show all ${changing.length} by name`}
          </Button>
          {showAll && (
            <Stack spacing={0.25} sx={{ mt: 1, maxHeight: 240, overflowY: 'auto' }}>
              {changing.map((row) => (
                <Typography key={row.id} variant="caption" color="text.secondary">
                  {row.label}
                </Typography>
              ))}
            </Stack>
          )}
        </>
      )}
    </>
  );
}

/** "null" and "" both mean nothing is recorded; neither should be shown raw. */
function displayValue(value: string) {
  return value === 'null' || value === '' ? '—' : value;
}
