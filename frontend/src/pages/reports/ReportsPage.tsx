import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardActionArea,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  LinearProgress,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { EntityTable } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';
import { ReportFilters } from './ReportFilters';
import { FieldPicker } from './FieldPicker';
import {
  renderCell,
  type CannedReport,
  type ReportEntity,
  type ReportResult,
  type RunRequest,
  type SavedReport,
} from './reportTypes';

/**
 * Reports (Phase 9 §4.14).
 *
 * <p>Three tabs over one engine: the reports that already exist, a builder for
 * the ones that do not, and the saved ones somebody built before. They produce
 * the same result shape and share the same result table and export buttons —
 * a canned report is a custom report whose columns were decided in advance,
 * and building it that way is what keeps them from drifting apart.
 */
export function ReportsPage() {
  const [tab, setTab] = useState(0);

  return (
    <>
      <PageHeader
        title="Reports"
        subtitle="Run one of the standard reports, or build your own. Everything here can be exported."
      />

      <Tabs value={tab} onChange={(_event, next) => setTab(next)} sx={{ mb: 2 }}>
        <Tab label="Standard reports" />
        <Tab label="Build a report" />
        <Tab label="Saved" />
      </Tabs>

      {tab === 0 && <CannedReportsTab />}
      {tab === 1 && <CustomReportTab />}
      {tab === 2 && <SavedReportsTab />}
    </>
  );
}

// ---------------------------------------------------------------------------
// the reports that already exist
// ---------------------------------------------------------------------------

function CannedReportsTab() {
  const [chosen, setChosen] = useState<CannedReport | null>(null);
  const [filters, setFilters] = useState<Record<string, unknown>>({});

  const reports = useQuery({
    queryKey: ['report-catalogue'],
    queryFn: () => api.get<CannedReport[]>('/api/reports'),
  });

  if (chosen) {
    return (
      <ReportRunner
        title={chosen.title}
        description={chosen.description}
        request={{ reportId: chosen.id, filters }}
        filterKeys={chosen.filters}
        filters={filters}
        onFiltersChange={setFilters}
        onBack={() => {
          setChosen(null);
          setFilters({});
        }}
      />
    );
  }

  return (
    <>
      {reports.isLoading && <LinearProgress />}
      <Grid container spacing={2}>
        {(reports.data ?? []).map((report) => (
          <Grid item xs={12} md={6} lg={4} key={report.id}>
            <Card variant="outlined" sx={{ height: '100%' }}>
              <CardActionArea sx={{ height: '100%' }} onClick={() => setChosen(report)}>
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
    </>
  );
}

// ---------------------------------------------------------------------------
// building one
// ---------------------------------------------------------------------------

/** Every filter the builder can offer, since a custom report has no fixed set. */
const ALL_FILTERS: Record<ReportEntity, string[]> = {
  ASSET: ['categoryIds', 'locationIds', 'lifecycleStateIds', 'purchasedFrom', 'purchasedTo'],
  PURCHASE_ORDER: ['status', 'vendor', 'createdFrom', 'createdTo'],
};

function CustomReportTab() {
  const queryClient = useQueryClient();
  const [entity, setEntity] = useState<ReportEntity>('ASSET');
  const [fields, setFields] = useState<string[]>([]);
  const [filters, setFilters] = useState<Record<string, unknown>>({});
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState('');
  const [saved, setSaved] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: () =>
      api.post<SavedReport>('/api/reports/saved', { name: name.trim(), entity, fields, filters }),
    onSuccess: (report) => {
      setNaming(false);
      setName('');
      setSaved(`Saved as “${report.name}”.`);
      void queryClient.invalidateQueries({ queryKey: ['saved-reports'] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not save that report.'),
  });

  return (
    <Stack spacing={2}>
      {saved && <Alert severity="success" onClose={() => setSaved(null)}>{saved}</Alert>}
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>Report on</Typography>
        <EntityChoice
          value={entity}
          onChange={(next) => {
            setEntity(next);
            // The columns of one entity mean nothing on the other.
            setFields([]);
            setFilters({});
          }}
        />
      </Paper>

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>Columns</Typography>
        <FieldPicker
          entity={entity}
          categoryIds={(filters.categoryIds as number[]) ?? []}
          selected={fields}
          onChange={setFields}
        />
      </Paper>

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>Narrow it down</Typography>
        <ReportFilters accepts={ALL_FILTERS[entity]} value={filters} onChange={setFilters} />
      </Paper>

      <ReportRunner
        title="Custom report"
        request={{ entity, fields, filters }}
        canRun={fields.length > 0}
        extraActions={
          <Button disabled={fields.length === 0} onClick={() => setNaming(true)}>
            Save this report
          </Button>
        }
      />

      <Dialog open={naming} onClose={() => setNaming(false)} fullWidth maxWidth="xs">
        <DialogTitle>Save this report</DialogTitle>
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
          <Button variant="contained" disabled={!name.trim() || save.isPending} onClick={() => save.mutate()}>
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

// ---------------------------------------------------------------------------
// saved ones
// ---------------------------------------------------------------------------

function SavedReportsTab() {
  const queryClient = useQueryClient();
  const [chosen, setChosen] = useState<SavedReport | null>(null);

  const saved = useQuery({
    queryKey: ['saved-reports'],
    queryFn: () => api.get<SavedReport[]>('/api/reports/saved'),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/reports/saved/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['saved-reports'] }),
  });

  if (chosen) {
    return (
      <ReportRunner
        title={chosen.name}
        request={{ savedReportId: chosen.id }}
        onBack={() => setChosen(null)}
      />
    );
  }

  return (
    <Paper variant="outlined">
      <EntityTable
        columns={[
          { header: 'Report', render: (report: SavedReport) => report.name },
          {
            header: 'About',
            render: (report: SavedReport) =>
              report.entity === 'ASSET' ? 'Assets' : 'Purchase orders',
          },
          { header: 'Columns', render: (report: SavedReport) => report.fields.length },
          { header: 'Saved by', render: (report: SavedReport) => report.createdBy ?? '—' },
        ]}
        rows={saved.data ?? []}
        rowKey={(report) => report.id}
        loading={saved.isLoading}
        emptyMessage="No saved reports yet. Build one and save it to keep its columns and filters."
        rowActions={(report) => (
          <>
            <Button size="small" onClick={() => setChosen(report)}>Run</Button>
            <Button size="small" color="error" onClick={() => remove.mutate(report.id)}>
              Delete
            </Button>
          </>
        )}
      />
    </Paper>
  );
}

// ---------------------------------------------------------------------------
// running whichever it is
// ---------------------------------------------------------------------------

function ReportRunner({
  title,
  description,
  request,
  filterKeys,
  filters,
  onFiltersChange,
  onBack,
  canRun = true,
  extraActions,
}: {
  title: string;
  description?: string;
  request: RunRequest;
  filterKeys?: string[];
  filters?: Record<string, unknown>;
  onFiltersChange?: (filters: Record<string, unknown>) => void;
  onBack?: () => void;
  canRun?: boolean;
  extraActions?: React.ReactNode;
}) {
  const [result, setResult] = useState<ReportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = useMutation({
    mutationFn: () => api.post<ReportResult>('/api/reports/run', request),
    onSuccess: (data) => {
      setResult(data);
      setError(null);
    },
    onError: (caught) => {
      setResult(null);
      setError(caught instanceof ApiError ? caught.message : 'Could not run that report.');
    },
  });

  const download = useMutation({
    mutationFn: async (format: 'csv' | 'pdf') => {
      const { blob, filename } = await api.postBlob(`/api/reports/export?format=${format}`, request);
      // A link clicked in code rather than a window.open: the request needs the
      // session cookie and a body, so it cannot be a plain URL.
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = filename ?? `report.${format}`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not export that report.'),
  });

  const columns = useMemo(
    () =>
      (result?.columns ?? []).map((column) => ({
        header: column.label,
        render: (row: Record<string, unknown>) => renderCell(row[column.key]),
      })),
    [result],
  );

  return (
    <Stack spacing={2}>
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          justifyContent="space-between"
          alignItems={{ xs: 'stretch', sm: 'center' }}
          spacing={1}
        >
          <Box>
            <Typography variant="subtitle1">{title}</Typography>
            {description && (
              <Typography variant="body2" color="text.secondary">{description}</Typography>
            )}
          </Box>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {onBack && <Button onClick={onBack}>Back</Button>}
            {extraActions}
            <Button
              variant="contained"
              startIcon={<PlayArrowIcon />}
              disabled={!canRun || run.isPending}
              onClick={() => run.mutate()}
            >
              Run
            </Button>
          </Stack>
        </Stack>

        {filterKeys && filterKeys.length > 0 && onFiltersChange && (
          <>
            <Divider sx={{ my: 2 }} />
            <ReportFilters accepts={filterKeys} value={filters ?? {}} onChange={onFiltersChange} />
          </>
        )}
      </Paper>

      {run.isPending && <LinearProgress />}
      {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

      {result && (
        <>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" color="text.secondary">
              {result.rows.length} row{result.rows.length === 1 ? '' : 's'}
            </Typography>
            <Box sx={{ flexGrow: 1 }} />
            <Button
              size="small"
              startIcon={<DownloadIcon />}
              disabled={download.isPending}
              onClick={() => download.mutate('csv')}
            >
              CSV
            </Button>
            <Button
              size="small"
              startIcon={<PictureAsPdfIcon />}
              disabled={download.isPending}
              onClick={() => download.mutate('pdf')}
            >
              PDF
            </Button>
          </Stack>

          {result.truncated && (
            <Alert severity="warning">
              This report hit the row ceiling and shows only the first part of it. Narrow the
              filters for the whole answer.
            </Alert>
          )}

          <Paper variant="outlined">
            <EntityTable
              columns={columns}
              // Report rows have no identity of their own -- two rows can be
              // genuinely identical -- so position is the only honest key.
              rows={result.rows.map((row, index) => ({ ...row, __row: index }))}
              rowKey={(row) => row.__row as number}
              emptyMessage="Nothing matched those filters."
            />
          </Paper>
        </>
      )}
    </Stack>
  );
}
