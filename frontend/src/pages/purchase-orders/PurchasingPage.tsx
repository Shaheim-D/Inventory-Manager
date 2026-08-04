import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, LinearProgress, Link, Paper, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { PurchaseOrder } from '../../api/types';
import { EntityTable, type Column } from '../../components/EntityTable';
import { PurchaseDialog } from './OrderActionDialogs';
import { money, when } from './shared';

/**
 * Orders that have been agreed to but not bought yet.
 *
 * This queue exists because approving and buying are separate acts that happen
 * days apart. Somebody has to actually go to the vendor, and until they do
 * there is no order number, no real price, and nothing to receive against.
 * Marking one purchased is what sets the purchase date on everything it will
 * eventually deliver.
 */
export function PurchasingPage() {
  const navigate = useNavigate();
  const [purchasing, setPurchasing] = useState<PurchaseOrder | null>(null);

  const orders = useQuery({
    queryKey: ['purchase-orders', 'purchasing'],
    queryFn: () => api.get<PurchaseOrder[]>('/api/purchase-orders?status=APPROVED'),
  });

  const rows = orders.data ?? [];
  const costVisible = rows.some((order) => 'total' in order);

  const columns: Column<PurchaseOrder>[] = [
    { header: 'Request', render: (order) => `Request #${order.id}` },
    { header: 'Requested by', render: (order) => order.requestedBy ?? '—' },
    { header: 'Approved by', render: (order) => order.approvedBy ?? '—' },
    {
      header: 'Suggested vendor',
      render: (order) =>
        order.purchaseLink ? (
          // The requester's link is the fastest route to actually buying it,
          // so it is a link rather than text to be copied out.
          <Link href={order.purchaseLink} target="_blank" rel="noopener noreferrer">
            {order.vendor ?? 'Open link'}
          </Link>
        ) : (
          (order.vendor ?? '—')
        ),
    },
    {
      header: 'Items',
      render: (order) => (
        <Stack spacing={0.25}>
          {order.lineItems.map((line) => (
            <Typography key={line.id} variant="body2">
              {line.quantityOrdered} × {line.deviceLabel ?? line.description}
            </Typography>
          ))}
        </Stack>
      ),
    },
    ...(costVisible
      ? [{ header: 'Estimate', align: 'right' as const, render: (order: PurchaseOrder) => money(order.total) }]
      : []),
    {
      header: 'Approved',
      align: 'right',
      secondary: true,
      render: (order) => when(order.approvedAt),
    },
  ];

  return (
    <>
      {orders.isFetching && !orders.isLoading && <LinearProgress sx={{ mb: 1 }} />}

      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={rows}
          rowKey={(order) => order.id}
          loading={orders.isLoading}
          emptyMessage="Nothing has been approved and is waiting to be bought."
          onRowClick={(order) => navigate(`/purchase-orders/order/${order.id}`)}
          rowActions={(order) => (
            <Button size="small" variant="contained" onClick={() => setPurchasing(order)}>
              Mark purchased
            </Button>
          )}
        />
      </Paper>

      <PurchaseDialog
        open={Boolean(purchasing)}
        order={purchasing}
        onClose={() => setPurchasing(null)}
      />
    </>
  );
}
