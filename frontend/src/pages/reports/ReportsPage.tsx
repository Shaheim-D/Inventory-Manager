import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  CardActionArea,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  LinearProgress,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/Edit';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { PageHeader } from '../../components/PageHeader';
import { ReportFilters } from './ReportFilters';
import { FieldPicker } from './FieldPicker';
import { ReportRunner } from './ReportRunner';
import type { CannedReport, ReportEntity, SavedReport } from './reportTypes';

/**
 * Reports (Phase 9 §4.14).
 *
 * <p>One list of reports and one builder. Standard reports and the custom ones
 * somebody saved sit in two sections of the same screen, because from the point
 * of view of running one there is no difference between them — a standard
 * report is simply one whose columns were decided in advance.
 *
 * <p>Which report is open lives in the URL rather than in component state.
 * Anything that remounts this tree would otherwise drop somebody back on the
 * catalogue, losing whatever they were reading; from the URL the screen comes
 * back exactly as it was, and the browser's back button works into the bargain.
 */
export function ReportsPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'build' ? 1 : 0;

  const show = (next: Record<string, string | null>) => {
    const updated = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value === null) updated.delete(key);
      else updated.set(key, value);
    }
    setParams(updated, { replace: false });
  };

  return (
    <>
      <PageHeader
        title="Reports"
        help="Run one of the standard reports, one somebody saved, or build your own. Everything here can be exported."
      />

      <Tabs
        value={tab}
        onChange={(_event, next) =>
          show({ tab: next === 1 ? 'build' : null, report: null, saved: null, edit: null })
        }
        sx={{ mb: 2 }}
      >
        <Tab label="Reports" />
        <Tab label="Build a report" />
      </Tabs>

      {tab === 0 ? <ReportLibrary params={params} show={show} /> : <ReportBuilder params={params} show={show} />}
    </>
  );
}

// ---------------------------------------------------------------------------
// the library: standard reports, then the saved ones
// ---------------------------------------------------------------------------

function ReportLibrary({
  params,
  show,
}: {
  params: URLSearchParams;
  show: (next: Record<string, string | null>) => void;
}) {
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<Record<string, unknown>>({});
  const [removing, setRemoving] = useState<SavedReport | null>(null);

  const reports = useQuery({
    queryKey: ['report-catalogue'],
    queryFn: () => api.get<CannedReport[]>('/api/reports'),
  });
  const saved = useQuery({
    queryKey: ['saved-reports'],
    queryFn: () => api.get<SavedReport[]>('/api/reports/saved'),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/reports/saved/${id}`),
    onSuccess: () => {
      setRemoving(null);
      void queryClient.invalidateQueries({ queryKey: ['saved-reports'] });
    },
  });

  const openId = params.get('report');
  const openSavedId = params.get('saved');
  const open = (reports.data ?? []).find((report) => report.id === openId);
  const openSaved = (saved.data ?? []).find((report) => String(report.id) === openSavedId);

  if (openId && open) {
    return (
      <ReportRunner
        title={open.title}
        description={open.description}
        request={{ reportId: open.id, filters }}
        filterKeys={open.filters}
        filters={filters}
        onFiltersChange={setFilters}
        onBack={() => {
          show({ report: null });
          setFilters({});
        }}
      />
    );
  }

  if (openSavedId && openSaved) {
    return (
      <ReportRunner
        title={openSaved.name}
        description={describe(openSaved)}
        request={{ savedReportId: openSaved.id }}
        onBack={() => show({ saved: null })}
        extraActions={
          <Button
            startIcon={<EditIcon />}
            onClick={() => show({ tab: 'build', saved: null, edit: String(openSaved.id) })}
          >
            Edit
          </Button>
        }
      />
    );
  }

  return (
    <Stack spacing={3}>
      <Stack spacing={1}>
        <Typography variant="subtitle1">Standard reports</Typography>
        {reports.isLoading && <LinearProgress />}
        <Grid container spacing={2}>
          {(reports.data ?? []).map((report) => (
            <Grid item xs={12} md={6} lg={4} key={report.id}>
              <Card variant="outlined" sx={{ height: '100%' }}>
                <CardActionArea sx={{ height: '100%' }} onClick={() => show({ report: report.id })}>
                  <CardContent>
                    <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
                      <Typography variant="subtitle1">{report.title}</Typography>
                      {report.summary && <Chip size="small" variant="outlined" label="Summary" />}
                    </Stack>
                    <Typography variant="body2" color="text.secondary">
                      {report.description}
                    </Typography>
                  </CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Stack>

      <Stack spacing={1}>
        <Stack direction="row" alignItems="center" spacing={1}>
          <Typography variant="subtitle1">Custom reports</Typography>
          <Chip size="small" variant="outlined" label={(saved.data ?? []).length} />
        </Stack>

        {saved.isLoading && <LinearProgress />}
        {!saved.isLoading && (saved.data ?? []).length === 0 && (
          <Paper variant="outlined" sx={{ p: 3 }}>
            <Typography variant="body2" color="text.secondary">
              Nothing saved yet. Build a report and save it to keep its columns and filters — it
              will appear here for anyone who can run reports.
            </Typography>
          </Paper>
        )}

        <Grid container spacing={2}>
          {(saved.data ?? []).map((report) => (
            <Grid item xs={12} md={6} lg={4} key={report.id}>
              <Card variant="outlined" sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
                <CardActionArea sx={{ flexGrow: 1 }} onClick={() => show({ saved: String(report.id) })}>
                  <CardContent>
                    <Typography variant="subtitle1" sx={{ mb: 1 }}>{report.name}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {describe(report)}
                    </Typography>
                    {report.createdBy && (
                      <Typography variant="caption" color="text.secondary">
                        Saved by {report.createdBy}
                      </Typography>
                    )}
                  </CardContent>
                </CardActionArea>
                <Stack direction="row" spacing={1} sx={{ px: 1, pb: 1 }}>
                  <Button
                    size="small"
                    startIcon={<EditIcon />}
                    onClick={() => show({ tab: 'build', edit: String(report.id) })}
                  >
                    View &amp; edit
                  </Button>
                  <Button
                    size="small"
                    color="error"
                    startIcon={<DeleteOutlineIcon />}
                    onClick={() => setRemoving(report)}
                  >
                    Delete
                  </Button>
                </Stack>
              </Card>
            </Grid>
          ))}
        </Grid>
      </Stack>

      <Dialog open={removing !== null} onClose={() => setRemoving(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Delete “{removing?.name}”?</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">
            Saved reports are shared, so this removes it for everybody. The data it reported on is
            untouched.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRemoving(null)}>Cancel</Button>
          <Button
            variant="contained"
            color="error"
            disabled={remove.isPending}
            onClick={() => removing && remove.mutate(removing.id)}
          >
            Delete
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

/** A one-line summary of what a saved report is, for its card. */
function describe(report: SavedReport): string {
  const entity = report.entity === 'ASSET' ? 'Assets' : 'Purchase orders';
  const columns = `${report.fields.length} column${report.fields.length === 1 ? '' : 's'}`;
  const filters = Object.keys(report.filters ?? {}).length;
  return filters === 0
    ? `${entity} — ${columns}, unfiltered`
    : `${entity} — ${columns}, ${filters} filter${filters === 1 ? '' : 's'}`;
}

// ---------------------------------------------------------------------------
// the builder
// ---------------------------------------------------------------------------

/** Every filter the builder can offer, since a custom report has no fixed set. */
const ALL_FILTERS: Record<ReportEntity, string[]> = {
  ASSET: ['categoryIds', 'locationIds', 'lifecycleStateIds', 'purchasedFrom', 'purchasedTo'],
  PURCHASE_ORDER: ['status', 'vendor', 'createdFrom', 'createdTo'],
};

/**
 * A draft survives a reload and anything that remounts the page. It is not
 * worth putting in the URL — a column list makes an unreadable query string —
 * but losing twenty ticked boxes to a stray re-render is exactly what this
 * screen was reported for.
 */
const DRAFT_KEY = 'inventory-manager.report-draft';

interface Draft {
  entity: ReportEntity;
  fields: string[];
  filters: Record<string, unknown>;
}

function readDraft(): Draft | null {
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY);
    return raw ? (JSON.parse(raw) as Draft) : null;
  } catch {
    return null;
  }
}

function ReportBuilder({
  params,
  show,
}: {
  params: URLSearchParams;
  show: (next: Record<string, string | null>) => void;
}) {
  const queryClient = useQueryClient();
  const editId = params.get('edit');

  const saved = useQuery({
    queryKey: ['saved-reports'],
    queryFn: () => api.get<SavedReport[]>('/api/reports/saved'),
    enabled: editId !== null,
  });
  const editing = (saved.data ?? []).find((report) => String(report.id) === editId) ?? null;

  const draft = editId ? null : readDraft();
  const [entity, setEntity] = useState<ReportEntity>(draft?.entity ?? 'ASSET');
  const [fields, setFields] = useState<string[]>(draft?.fields ?? []);
  const [filters, setFilters] = useState<Record<string, unknown>>(draft?.filters ?? {});
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Loading a saved report to look at or change: the builder is the screen that
  // already shows a report's settings, so there is no second one to maintain.
  useEffect(() => {
    if (!editing) return;
    setEntity(editing.entity);
    setFields(editing.fields);
    setFilters(editing.filters ?? {});
    setName(editing.name);
  }, [editing]);

  useEffect(() => {
    if (editId) return;
    sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ entity, fields, filters }));
  }, [editId, entity, fields, filters]);

  const save = useMutation({
    mutationFn: () =>
      editing
        ? api.put<SavedReport>(`/api/reports/saved/${editing.id}`,
            { name: name.trim(), entity, fields, filters })
        : api.post<SavedReport>('/api/reports/saved',
            { name: name.trim(), entity, fields, filters }),
    onSuccess: (report) => {
      setNaming(false);
      setNotice(editing ? `Saved changes to “${report.name}”.` : `Saved as “${report.name}”.`);
      void queryClient.invalidateQueries({ queryKey: ['saved-reports'] });
      if (!editing) setName('');
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not save that report.'),
  });

  const startFresh = () => {
    show({ edit: null });
    setEntity('ASSET');
    setFields([]);
    setFilters({});
    setName('');
  };

  return (
    <Stack spacing={2}>
      {notice && <Alert severity="success" onClose={() => setNotice(null)}>{notice}</Alert>}
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

      {editing && (
        <Alert severity="info" action={<Button size="small" onClick={startFresh}>Start a new one</Button>}>
          Editing “{editing.name}”. Saving updates it for everybody who runs it.
        </Alert>
      )}

      <ReportRunner
        title={editing ? editing.name : 'Custom report'}
        request={{ entity, fields, filters }}
        canRun={fields.length > 0}
        extraActions={
          <Button
            disabled={fields.length === 0}
            onClick={() => {
              setError(null);
              setNaming(true);
            }}
          >
            {editing ? 'Save changes' : 'Save this report'}
          </Button>
        }
        optionsAbove={
          <Stack spacing={2}>
            <Stack spacing={1}>
              <Typography variant="subtitle2" color="text.secondary">Report on</Typography>
              <EntityChoice
                value={entity}
                onChange={(next) => {
                  setEntity(next);
                  // The columns of one entity mean nothing on the other.
                  setFields([]);
                  setFilters({});
                }}
              />
            </Stack>

            {/* Filters first: narrowing to a category is what decides which
                custom fields are worth offering below, so choosing it after
                picking columns was the wrong way round. */}
            <Stack spacing={1}>
              <Typography variant="subtitle2" color="text.secondary">Narrow it down</Typography>
              <ReportFilters accepts={ALL_FILTERS[entity]} value={filters} onChange={setFilters} />
            </Stack>

            <Stack spacing={1}>
              <Typography variant="subtitle2" color="text.secondary">Columns</Typography>
              <FieldPicker
                entity={entity}
                categoryIds={(filters.categoryIds as number[]) ?? []}
                selected={fields}
                onChange={setFields}
              />
            </Stack>
          </Stack>
        }
      />

      <Dialog open={naming} onClose={() => setNaming(false)} fullWidth maxWidth="xs">
        <DialogTitle>{editing ? 'Save changes' : 'Save this report'}</DialogTitle>
        <DialogContent>
          <TextField
            autoFocus
            label="Name"
            sx={{ mt: 1 }}
            value={name}
            onChange={(event) => setName(event.target.value)}
            helperText="Saving keeps the columns and filters. Anyone who can run reports can run it."
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setNaming(false)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!name.trim() || save.isPending}
            onClick={() => save.mutate()}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

function EntityChoice({
  value,
  onChange,
}: {
  value: ReportEntity;
  onChange: (entity: ReportEntity) => void;
}) {
  return (
    <Stack direction="row" spacing={1}>
      <Chip
        label="Assets"
        color={value === 'ASSET' ? 'primary' : 'default'}
        variant={value === 'ASSET' ? 'filled' : 'outlined'}
        onClick={() => onChange('ASSET')}
      />
      <Chip
        label="Purchase orders"
        color={value === 'PURCHASE_ORDER' ? 'primary' : 'default'}
        variant={value === 'PURCHASE_ORDER' ? 'filled' : 'outlined'}
        onClick={() => onChange('PURCHASE_ORDER')}
      />
    </Stack>
  );
}
