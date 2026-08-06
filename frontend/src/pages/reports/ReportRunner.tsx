import { useMemo, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Collapse,
  Divider,
  LinearProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import PictureAsPdfIcon from '@mui/icons-material/PictureAsPdf';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import TuneIcon from '@mui/icons-material/Tune';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import { EntityTable } from '../../components/EntityTable';
import { ReportFilters } from './ReportFilters';
import { renderCell, type ReportResult, type RunRequest } from './reportTypes';

/** The cache key for a result, so the same request run twice is the same answer. */
const resultKey = (request: RunRequest) => ['report-result', JSON.stringify(request)];

/**
 * Running a report and showing what came back.
 *
 * <p>The result lives in the query cache under a key derived from the request
 * itself, not in component state. That is deliberate: anything that remounts
 * this screen — a re-render of the router tree, a session refresh, coming back
 * to it from elsewhere — used to lose the table somebody was reading. Keyed on
 * the request, the table comes straight back instead.
 *
 * <p>The options fold away once there is something to look at. A report is
 * read, and reading it on the third of the screen left over after the controls
 * is not reading it — but the controls are one click from returning, because
 * "run it again with one more column" is the next thing anybody does.
 */
export function ReportRunner({
  title,
  description,
  request,
  filterKeys,
  filters,
  onFiltersChange,
  onBack,
  canRun = true,
  extraActions,
  optionsAbove,
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
  /** The builder's own controls, folded away with everything else after a run. */
  optionsAbove?: React.ReactNode;
}) {
  const queryClient = useQueryClient();

  // What produced the table currently on screen. Held separately from the live
  // request so that editing an option does not blank the report somebody is
  // still reading -- it goes stale, and says so, until they run it again.
  const [ran, setRan] = useState<RunRequest | null>(() => {
    const cached = queryClient.getQueryData<ReportResult>(resultKey(request));
    return cached ? request : null;
  });
  const [optionsOpen, setOptionsOpen] = useState(() => ran === null);
  const [error, setError] = useState<string | null>(null);

  const result = useQuery({
    queryKey: resultKey(ran ?? request),
    queryFn: () => api.post<ReportResult>('/api/reports/run', ran ?? request),
    enabled: ran !== null,
    // A report is a snapshot somebody asked for, not live data. Re-running it
    // behind their back would change the thing they are reading mid-read.
    staleTime: Infinity,
    gcTime: 30 * 60 * 1000,
    retry: false,
  });

  const stale = ran !== null && JSON.stringify(ran) !== JSON.stringify(request);

  const run = () => {
    setError(null);
    setRan(request);
    setOptionsOpen(false);
    void queryClient.invalidateQueries({ queryKey: resultKey(request) });
  };

  const download = useMutation({
    mutationFn: async (format: 'csv' | 'pdf') => {
      const { blob, filename } = await api.postBlob(
        `/api/reports/export?format=${format}`, ran ?? request);
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

  const data = result.data;
  const columns = useMemo(
    () =>
      (data?.columns ?? []).map((column) => ({
        header: column.label,
        render: (row: Record<string, unknown>) => renderCell(row[column.key]),
      })),
    [data],
  );

  const failure = result.error instanceof ApiError ? result.error.message : null;

  return (
    <Stack spacing={2}>
      <Paper variant="outlined">
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          justifyContent="space-between"
          alignItems={{ xs: 'stretch', sm: 'center' }}
          spacing={1}
          sx={{ p: 2 }}
        >
          <Box>
            <Typography variant="subtitle1">{title}</Typography>
            {description && (
              <Typography variant="body2" color="text.secondary">{description}</Typography>
            )}
          </Box>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
            {onBack && <Button onClick={onBack}>Back</Button>}
            {extraActions}
            {/* Only worth showing once there is something to fold. */}
            {data && (
              <Button
                startIcon={<TuneIcon />}
                endIcon={optionsOpen ? <ExpandLessIcon /> : <ExpandMoreIcon />}
                onClick={() => setOptionsOpen((open) => !open)}
              >
                Options
              </Button>
            )}
            <Button
              variant="contained"
              startIcon={<PlayArrowIcon />}
              disabled={!canRun || result.isFetching}
              onClick={run}
            >
              Run
            </Button>
          </Stack>
        </Stack>

        <Collapse in={optionsOpen || !data} unmountOnExit={false}>
          <Divider />
          <Box sx={{ p: 2 }}>
            {optionsAbove}
            {filterKeys && filterKeys.length > 0 && onFiltersChange && (
              <ReportFilters accepts={filterKeys} value={filters ?? {}} onChange={onFiltersChange} />
            )}
          </Box>
        </Collapse>
      </Paper>

      {result.isFetching && <LinearProgress />}
      {(error || failure) && (
        <Alert severity="error" onClose={() => setError(null)}>{error ?? failure}</Alert>
      )}

      {stale && data && (
        <Alert severity="info" action={<Button size="small" onClick={run}>Run again</Button>}>
          The options have changed since this was generated.
        </Alert>
      )}

      {data && (
        <>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" color="text.secondary">
              {data.rows.length} row{data.rows.length === 1 ? '' : 's'}
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

          {data.truncated && (
            <Alert severity="warning">
              This report hit the row ceiling and shows only the first part of it. Narrow the
              filters for the whole answer.
            </Alert>
          )}

          <Paper variant="outlined">
            <EntityTable
              columns={columns}
              // Report rows have no identity of their own — two rows can be
              // genuinely identical — so position is the only honest key.
              rows={data.rows.map((row, index) => ({ ...row, __row: index }))}
              rowKey={(row) => row.__row as number}
              emptyMessage="Nothing matched those filters."
            />
          </Paper>
        </>
      )}
    </Stack>
  );
}
