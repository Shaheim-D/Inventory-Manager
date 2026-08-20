import {
  Box,
  Card,
  CardActionArea,
  CardContent,
  Checkbox,
  CircularProgress,
  Divider,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import InboxIcon from '@mui/icons-material/InboxOutlined';
import type { ReactNode } from 'react';

export interface Column<T> {
  /** Sort key the server understands; omit to make the column unsortable. */
  key?: string;
  header: string;
  render: (row: T) => ReactNode;
  /** Hidden in card mode — keeps a phone card down to what matters. */
  secondary?: boolean;
  align?: 'left' | 'right';
}

interface Props<T> {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string | number;
  loading?: boolean;
  emptyMessage?: string;
  onRowClick?: (row: T) => void;
  /** Card-mode heading; falls back to the first column. */
  cardTitle?: (row: T) => ReactNode;
  rowActions?: (row: T) => ReactNode;
  page?: number;
  size?: number;
  totalElements?: number;
  onPageChange?: (page: number) => void;
  onSizeChange?: (size: number) => void;
  sort?: string;
  direction?: 'asc' | 'desc';
  onSortChange?: (sort: string, direction: 'asc' | 'desc') => void;
  /**
   * Turns on a checkbox column (and a checkbox on each card). Supplying it is
   * the whole opt-in — every screen that does gets identical select-all,
   * partial-state and card behaviour, which is the reason selection lives here
   * rather than being rebuilt per screen.
   */
  selectable?: boolean;
  selectedIds?: Set<string | number>;
  onSelectionChange?: (next: Set<string | number>) => void;
}

/**
 * The one table in the application. It has two render modes rather than one:
 * a data grid above the tablet breakpoint and a stacked card list below it,
 * because a dense multi-column table cannot be squeezed onto a phone no matter
 * what the CSS says. Every screen that uses this inherits both modes for free.
 */
export function EntityTable<T>({
  columns,
  rows,
  rowKey,
  loading,
  emptyMessage = 'Nothing to show yet.',
  onRowClick,
  cardTitle,
  rowActions,
  page,
  size,
  totalElements,
  onPageChange,
  onSizeChange,
  sort,
  direction,
  onSortChange,
  selectable,
  selectedIds,
  onSelectionChange,
}: Props<T>) {
  const theme = useTheme();
  const compact = useMediaQuery(theme.breakpoints.down('md'));

  const selected = selectedIds ?? new Set<string | number>();
  const selecting = Boolean(selectable && onSelectionChange);

  const toggle = (key: string | number) => {
    const next = new Set(selected);
    if (next.has(key)) next.delete(key);
    else next.add(key);
    onSelectionChange?.(next);
  };

  // Select-all covers the rows actually on screen, not every row the filter
  // would match on other pages. Selecting things somebody cannot see and then
  // deleting them is the kind of surprise this feature must not have.
  const pageKeys = rows.map(rowKey);
  const allOnPageSelected = pageKeys.length > 0 && pageKeys.every((key) => selected.has(key));
  const someOnPageSelected = pageKeys.some((key) => selected.has(key));

  const toggleAllOnPage = () => {
    const next = new Set(selected);
    if (allOnPageSelected) pageKeys.forEach((key) => next.delete(key));
    else pageKeys.forEach((key) => next.add(key));
    onSelectionChange?.(next);
  };

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (rows.length === 0) {
    return (
      // Every list in the application shows its empty state through here, so it
      // is worth being an actual designed state rather than a line of grey text
      // floating in the middle of a page. The message itself still comes from
      // the screen, because "no assets yet" and "no results for that search"
      // are different things and only the screen knows which one this is.
      <Box
        sx={{
          px: 3,
          py: 6,
          textAlign: 'center',
          border: 1,
          borderStyle: 'dashed',
          borderColor: 'divider',
          borderRadius: 3,
          bgcolor: 'background.paper',
        }}
      >
        <InboxIcon sx={{ fontSize: 32, color: 'text.disabled', mb: 1 }} />
        <Typography color="text.secondary" sx={{ maxWidth: '52ch', mx: 'auto' }}>
          {emptyMessage}
        </Typography>
      </Box>
    );
  }

  const pagination =
    page !== undefined && totalElements !== undefined && onPageChange ? (
      <TablePagination
        component="div"
        count={totalElements}
        page={page}
        rowsPerPage={size ?? 25}
        rowsPerPageOptions={[10, 25, 50, 100]}
        onPageChange={(_, next) => onPageChange(next)}
        onRowsPerPageChange={(event) => onSizeChange?.(Number(event.target.value))}
      />
    ) : null;

  if (compact) {
    return (
      <Box>
        <Stack spacing={1.5}>
          {rows.map((row) => {
            const body = (
              <CardContent>
                <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 1 }}>
                  {selecting && (
                    <Checkbox
                      size="small"
                      sx={{ mt: -1, ml: -1 }}
                      checked={selected.has(rowKey(row))}
                      onChange={() => toggle(rowKey(row))}
                      onClick={(event) => event.stopPropagation()}
                    />
                  )}
                  <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                    {cardTitle ? cardTitle(row) : columns[0].render(row)}
                  </Typography>
                </Box>
                <Stack spacing={0.75}>
                  {columns
                    .filter((column) => !column.secondary)
                    .slice(cardTitle ? 0 : 1)
                    .map((column) => (
                      <Box key={column.header} sx={{ display: 'flex', gap: 1 }}>
                        <Typography variant="body2" color="text.secondary" sx={{ minWidth: 120 }}>
                          {column.header}
                        </Typography>
                        <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
                          {column.render(row)}
                        </Typography>
                      </Box>
                    ))}
                </Stack>
              </CardContent>
            );

            return (
              <Card key={rowKey(row)}>
                {onRowClick ? <CardActionArea onClick={() => onRowClick(row)}>{body}</CardActionArea> : body}
                {rowActions && (
                  <>
                    <Divider />
                    <Box sx={{ p: 1.5, display: 'flex', gap: 1, flexWrap: 'wrap' }}>{rowActions(row)}</Box>
                  </>
                )}
              </Card>
            );
          })}
        </Stack>
        {pagination}
      </Box>
    );
  }

  return (
    <Box>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow>
              {selecting && (
                <TableCell padding="checkbox">
                  <Checkbox
                    size="small"
                    checked={allOnPageSelected}
                    indeterminate={someOnPageSelected && !allOnPageSelected}
                    onChange={toggleAllOnPage}
                    inputProps={{ 'aria-label': 'Select all on this page' }}
                  />
                </TableCell>
              )}
              {columns.map((column) => (
                <TableCell key={column.header} align={column.align}>
                  {column.key && onSortChange ? (
                    <TableSortLabel
                      active={sort === column.key}
                      direction={sort === column.key ? direction : 'asc'}
                      onClick={() =>
                        onSortChange(
                          column.key!,
                          sort === column.key && direction === 'asc' ? 'desc' : 'asc',
                        )
                      }
                    >
                      {column.header}
                    </TableSortLabel>
                  ) : (
                    column.header
                  )}
                </TableCell>
              ))}
              {rowActions && <TableCell align="right">Actions</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((row) => (
              <TableRow
                key={rowKey(row)}
                hover={Boolean(onRowClick)}
                sx={onRowClick ? { cursor: 'pointer' } : undefined}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                selected={selecting && selected.has(rowKey(row))}
              >
                {selecting && (
                  <TableCell padding="checkbox" onClick={(event) => event.stopPropagation()}>
                    <Checkbox
                      size="small"
                      checked={selected.has(rowKey(row))}
                      onChange={() => toggle(rowKey(row))}
                    />
                  </TableCell>
                )}
                {columns.map((column) => (
                  <TableCell key={column.header} align={column.align}>
                    {column.render(row)}
                  </TableCell>
                ))}
                {rowActions && (
                  <TableCell align="right" onClick={(event) => event.stopPropagation()}>
                    <Stack direction="row" spacing={1} justifyContent="flex-end">
                      {rowActions(row)}
                    </Stack>
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {pagination}
    </Box>
  );
}
