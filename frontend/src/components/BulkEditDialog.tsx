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
  Grid,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';

/**
 * Change one section of many rows at once, with a card that says exactly what
 * will happen before anything does.
 *
 * <p>Organised by section rather than as a flat field list, because that is
 * how somebody thinks about it: they are "updating the support contract", not
 * "setting five unrelated fields". Pick the section and every field in it
 * appears at once — the same grouping the asset form and the report builder
 * use, so the three screens agree on what a section is.
 *
 * <p><b>Blank means keep.</b> A field left empty is not sent at all, so each
 * row keeps whatever it already had. Only what somebody actually typed is
 * written. That is the opposite of the single-asset form, which submits the
 * whole asset and therefore clears anything missing from it — correct there,
 * ruinous here.
 *
 * <p>There is no way to empty a field from this dialog. Blank means keep, and
 * nothing here means clear — a control that wipes a value across a whole
 * selection does not belong beside the ones that fill it in.
 *
 * <p>The card is rendered from the server's preview, which runs the same
 * planning code the update will run — so it describes the actual work rather
 * than the screen's guess at it. What it displayed is sent back on confirm,
 * and a row whose stored value moved in between is refused rather than
 * overwritten.
 */

export interface BulkEditField {
  field: string;
  label: string;
  section: string;
  type: 'TEXT' | 'LONG_TEXT' | 'DATE' | 'NUMBER' | 'MONEY' | 'REFERENCE' | 'CHOICE';
}

/** A value the picker can offer for a field that references something. */
export interface BulkEditOption {
  id: number | string;
  label: string;
}

interface PlannedChange {
  field: string;
  label: string;
  /** null when the field is empty — a real null, not the string "null". */
  from: string | null;
  to: string | null;
  noop: boolean;
}

interface PlannedRow {
  id: number;
  label: string;
  refusal: string | null;
  changes: PlannedChange[];
}

interface TemplateAddition {
  categoryId: number;
  categoryName: string;
  fields: string[];
  assetsInCategory: number;
}

interface Plan {
  applied: boolean;
  fields: string[];
  rows: PlannedRow[];
  templateAdditions: TemplateAddition[];
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
  invalidate: string[][];
  /** Choices for REFERENCE and CHOICE fields, keyed by field name. */
  options?: Record<string, BulkEditOption[]>;
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
  onApplied,
}: Props) {
  const queryClient = useQueryClient();
  const [section, setSection] = useState<string | null>(null);
  const [entered, setEntered] = useState<Record<string, string>>({});
  const [plan, setPlan] = useState<Plan | null>(null);
  const [error, setError] = useState<string | null>(null);

  const ids = useMemo(() => Array.from(selected).map(Number), [selected]);
  const count = ids.length;
  const many = pluralNoun ?? `${noun}s`;
  const plural = count === 1 ? noun : many;

  const fields = useQuery({
    queryKey: ['bulk-edit-fields', basePath],
    queryFn: () => api.get<BulkEditField[]>(`${basePath}/bulk-update/fields`),
    enabled: open,
  });

  const sections = useMemo(() => {
    const grouped = new Map<string, BulkEditField[]>();
    for (const field of fields.data ?? []) {
      const list = grouped.get(field.section) ?? [];
      list.push(field);
      grouped.set(field.section, list);
    }
    return grouped;
  }, [fields.data]);

  /**
   * Only what somebody actually filled in. Everything else never reaches the
   * server, which is what makes "blank means keep" true rather than merely
   * intended.
   *
   * <p>There is deliberately no way to empty a field from here. The API still
   * understands an explicit null as "clear this", so the capability exists —
   * but a switch for it sat beside every box, and a control that destroys data
   * across a whole selection does not belong one mis-click away from one that
   * fills it in.
   */
  const changes = useMemo(() => {
    const payload: Record<string, string> = {};
    for (const [field, value] of Object.entries(entered)) {
      if (value !== '' && value !== undefined) payload[field] = value;
    }
    return payload;
  }, [entered]);

  const touchedCount = Object.keys(changes).length;

  const preview = useMutation({
    mutationFn: () => api.post<Plan>(`${basePath}/bulk-update/preview`, { ids, changes }),
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
        changes,
        // Exactly what the card displayed, so a row somebody else moved in
        // between is refused rather than silently overwritten.
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
    setSection(null);
    setEntered({});
    setPlan(null);
    setError(null);
    onClose();
  }

  const applied = plan?.applied ?? false;

  return (
    <Dialog open={open} onClose={reset} maxWidth="md" fullWidth>
      <DialogTitle>
        {applied
          ? 'Done'
          : plan
            ? `Review ${plan.changingCount} ${plan.changingCount === 1 ? noun : many}`
            : section
              ? `${section} — ${count} ${plural}`
              : `Edit ${count} ${plural}`}
      </DialogTitle>

      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        {/* ---------- step one: which section ---------- */}
        {!plan && !section && (
          <>
            <DialogContentText sx={{ mb: 2 }}>
              Which part of {count === 1 ? 'this' : 'these'} {plural} are you updating? You will
              get every field in that section, and can fill in as many or as few as you need.
            </DialogContentText>
            <Grid container spacing={1}>
              {[...sections.entries()].map(([name, inSection]) => (
                <Grid item xs={12} sm={6} key={name}>
                  <Button
                    fullWidth
                    variant="outlined"
                    sx={{ justifyContent: 'flex-start', textAlign: 'left', py: 1.5 }}
                    onClick={() => setSection(name)}
                  >
                    <Box>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {name}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {inSection.map((field) => field.label).join(', ')}
                      </Typography>
                    </Box>
                  </Button>
                </Grid>
              ))}
            </Grid>
            {fields.isLoading && <Typography variant="body2">Loading…</Typography>}
          </>
        )}

        {/* ---------- step two: the fields in it ---------- */}
        {!plan && section && (
          <>
            <Alert severity="info" sx={{ mb: 2 }}>
              <strong>Anything you leave blank stays as it is</strong> on every {noun} — only the
              fields you fill in are written.
            </Alert>

            <Grid container spacing={2}>
              {(sections.get(section) ?? []).map((field) => {
                const choices = options[field.field];
                return (
                  <Grid item xs={12} sm={6} key={field.field}>
                    {choices ? (
                      <TextField
                        select
                        label={field.label}
                        value={entered[field.field] ?? ''}
                        onChange={(event) =>
                          setEntered({ ...entered, [field.field]: event.target.value })
                        }
                      >
                        <MenuItem value="">
                          <em>Leave unchanged</em>
                        </MenuItem>
                        {choices.map((choice) => (
                          <MenuItem key={choice.id} value={String(choice.id)}>
                            {choice.label}
                          </MenuItem>
                        ))}
                      </TextField>
                    ) : (
                      <TextField
                        label={field.label}
                        type={
                          field.type === 'DATE'
                            ? 'date'
                            : field.type === 'NUMBER' || field.type === 'MONEY'
                              ? 'number'
                              : 'text'
                        }
                        multiline={field.type === 'LONG_TEXT'}
                        minRows={field.type === 'LONG_TEXT' ? 2 : undefined}
                        InputLabelProps={field.type === 'DATE' ? { shrink: true } : undefined}
                        value={entered[field.field] ?? ''}
                        placeholder="Leave unchanged"
                        onChange={(event) =>
                          setEntered({ ...entered, [field.field]: event.target.value })
                        }
                      />
                    )}
                  </Grid>
                );
              })}
            </Grid>
          </>
        )}

        {/* ---------- step three: the confirmation card ---------- */}
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
              {apply.isPending
                ? 'Applying…'
                : `Confirm ${plan.changingCount} ${plan.changingCount === 1 ? noun : many}`}
            </Button>
          </>
        ) : section ? (
          <>
            <Button onClick={() => setSection(null)}>Back</Button>
            <Button
              variant="contained"
              disabled={touchedCount === 0 || preview.isPending}
              onClick={() => preview.mutate()}
            >
              {preview.isPending ? 'Checking…' : 'Review changes'}
            </Button>
          </>
        ) : (
          <Button onClick={reset}>Cancel</Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

/**
 * What will happen, grouped so that four hundred identical moves read as one
 * line and the exceptions stand out.
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
        <Alert severity="info" sx={{ mb: 2 }}>
          <AlertTitle>Nothing has changed yet</AlertTitle>
          Each line below reads <em>old value → new value</em>. Any field not listed here is not
          being touched on any {noun}.
        </Alert>
      )}

      <Stack spacing={1.5}>
        {[...groups.values()].map(({ change, rows }) => (
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

      {/* The one effect that reaches past the selected rows, so it is called
          out rather than listed among them. */}
      {plan.templateAdditions.length > 0 && (
        <Alert severity="warning" sx={{ mt: 2 }}>
          <AlertTitle>
            {plan.applied ? 'Also added to these forms' : 'This also changes a category form'}
          </AlertTitle>
          <Stack spacing={0.5} sx={{ mt: 1 }}>
            {plan.templateAdditions.map((addition) => (
              <Typography key={addition.categoryId} variant="body2">
                <strong>{addition.categoryName}</strong> {plan.applied ? 'gained' : 'gains'}{' '}
                {addition.fields.join(', ')} — not currently on its form, so the value would
                otherwise be stored where nobody could see it. That form is shared by all{' '}
                {addition.assetsInCategory} {addition.assetsInCategory === 1 ? noun : many} in the
                category.
              </Typography>
            ))}
          </Stack>
        </Alert>
      )}

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

/** An empty field reads as a dash rather than as blank space. */
function displayValue(value: string | null) {
  return value === null || value === '' ? '—' : value;
}
