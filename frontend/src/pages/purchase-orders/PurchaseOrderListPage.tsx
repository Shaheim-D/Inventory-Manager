import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Button,
  Grid,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { PurchaseOrder } from '../../api/types';
import { EntityTable, type Column } from '../../components/EntityTable';
import { useAuth } from '../../auth/AuthContext';
import { ALL_STATUSES, orderLabel, statusLabel, StatusChip } from './shared';
import { money } from '../../format';

/**
 * My Requests and All Orders are one view with a scope switch rather than two,
 * because they answer the same question from two distances and the columns do
 * not differ. The server does the filtering either way — and it never shows
 * anyone else's draft, so All Orders is everything visible rather than
 * everything that exists.
 */
export function PurchaseOrderListPage() {
  const navigate = useNavigate();
  const { has } = useAuth();

  const [mine, setMine] = useState(true);
  const [status, setStatus] = useState('');

  const params = new URLSearchParams();
  if (mine) params.set('mine', 'true');
  if (status) params.set('status', status);

  const orders = useQuery({
    queryKey: ['purchase-orders', params.toString()],
    queryFn: () => api.get<PurchaseOrder[]>(`/api/purchase-orders?${params.toString()}`),
  });

  const rows = orders.data ?? [];
  // The cost column appears only when the server actually sent a total. Same
  // rule as the asset list: react to what arrived, never re-derive the rule.
  const costVisible = rows.some((order) => 'total' in order);

  const columns: Column<PurchaseOrder>[] = [
    // Column 0 doubles as the card heading in narrow mode, so it stays a plain
    // string and the vendor detail moves to a column of its own.
    { header: 'Order', render: (order) => orderLabel(order) },
    { header: 'Status', render: (order) => <StatusChip status={order.status} /> },
    {
      header: 'Vendor',
      render: (order) => order.vendor ?? '—',
    },
    { header: 'Requested by', render: (order) => order.requestedBy ?? '—' },
    {
      header: 'Items',
      align: 'right',
      render: (order) => `${order.lineItems.length} line${order.lineItems.length === 1 ? '' : 's'}`,
    },
    {
      header: 'Received',
      align: 'right',
      // Two numbers rather than a percentage: a receiver wants to know how many
      // are still outstanding, not what fraction of the order has landed.
      render: (order) => (
        <Tooltip title={`${order.quantityOrdered - order.quantityReceived} still outstanding`}>
          <span>
            {order.quantityReceived} / {order.quantityOrdered}
          </span>
        </Tooltip>
      ),
    },
    ...(costVisible
      ? [{ header: 'Pre-tax total', align: 'right' as const, render: (order: PurchaseOrder) => money(order.total) }]
      : []),
    {
      header: 'Raised',
      align: 'right',
      secondary: true,
      render: (order) => (order.createdAt ? new Date(order.createdAt).toLocaleDateString() : '—'),
    },
  ];

  return (
    <>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        justifyContent="space-between"
        alignItems={{ xs: 'stretch', sm: 'center' }}
        spacing={2}
        sx={{ mb: 2 }}
      >
        <Typography variant="body2" color="text.secondary">
          {orders.data ? `${rows.length} order${rows.length === 1 ? '' : 's'}` : 'Loading…'}
        </Typography>
        {has('purchase_order:create') && (
          <Button variant="contained" onClick={() => navigate('/purchase-orders/new')}>
            New request
          </Button>
        )}
      </Stack>

      {/* Scope sits beside status as a filter rather than as a second row of
          tabs. The page already has one tab bar; a second one under it reads as
          a hierarchy that is not there — these are two ways of narrowing the
          same list. */}
      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} sm={6} md={4}>
            <TextField
              select
              label="Show"
              value={mine ? 'mine' : 'all'}
              onChange={(event) => setMine(event.target.value === 'mine')}
            >
              <MenuItem value="mine">My requests</MenuItem>
              <MenuItem value="all">All orders</MenuItem>
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6} md={4}>
            <TextField
              select
              label="Status"
              value={status}
              onChange={(event) => setStatus(event.target.value)}
            >
              <MenuItem value="">Any status</MenuItem>
              {ALL_STATUSES.map((option) => (
                <MenuItem key={option} value={option}>
                  {statusLabel(option)}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
        </Grid>
      </Paper>

      {orders.isFetching && !orders.isLoading && <LinearProgress sx={{ mb: 1 }} />}

      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={rows}
          rowKey={(order) => order.id}
          loading={orders.isLoading}
          emptyMessage={
            mine
              ? 'You have not raised any purchase requests yet.'
              : 'No purchase orders match this filter.'
          }
          onRowClick={(order) => navigate(`/purchase-orders/order/${order.id}`)}
          rowActions={(order) => (
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              {/* The point at which somebody names what arrived: straight from
                  the order to the rows it created, ready to have serials and
                  asset tags put on them. */}
              {order.quantityReceived > 0 && has('asset:read') && (
                <Button size="small" onClick={() => navigate(`/assets?purchaseOrderId=${order.id}`)}>
                  Show items
                </Button>
              )}
              <Button size="small" onClick={() => navigate(`/purchase-orders/order/${order.id}`)}>
                Open
              </Button>
            </Stack>
          )}
        />
      </Paper>
    </>
  );
}
