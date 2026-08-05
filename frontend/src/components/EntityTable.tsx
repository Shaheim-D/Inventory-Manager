import {
  Box,
  Card,
  CardActionArea,
  CardContent,
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
}: Props<T>) {
  const theme = useTheme();
  const compact = useMediaQuery(theme.breakpoints.down('md'));

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
                <Typography variant="subtitle1" fontWeight={600} gutterBottom>
                  {cardTitle ? cardTitle(row) : columns[0].render(row)}
                </Typography>
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
              >
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
