import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, LinearProgress, Paper, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { PurchaseOrder } from '../../api/types';
import { EntityTable, type Column } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';
import { ApproveDialog, ReasonDialog } from './OrderActionDialogs';
import { money, when } from './shared';

/**
 * The approvals queue. It shares its shape with the other review queues in the
 * application on purpose — a list of things waiting on a decision, each with its
 * decisions as row actions — so a reviewer who has used the verification queue
 * already knows how this one works.
 */
export function ApprovalsPage() {
  const navigate = useNavigate();
  const [approving, setApproving] = useState<PurchaseOrder | null>(null);
  const [rejecting, setRejecting] = useState<PurchaseOrder | null>(null);

  const orders = useQuery({
    queryKey: ['purchase-orders', 'approvals'],
    queryFn: () => api.get<PurchaseOrder[]>('/api/purchase-orders?status=SUBMITTED'),
  });

  const rows = orders.data ?? [];
  const costVisible = rows.some((order) => 'total' in order);

  const columns: Column<PurchaseOrder>[] = [
    { header: 'Request', render: (order) => `Request #${order.id}` },
    { header: 'Requested by', render: (order) => order.requestedBy ?? '—' },
    {
      header: 'Justification',
      // The whole point of the queue is reading this, so it is not truncated to
      // a tidy width -- an approver deciding on a summary is guessing.
      render: (order) => (
        <Typography variant="body2" sx={{ maxWidth: 420, whiteSpace: 'pre-wrap' }}>
          {order.justification ?? <em>No justification given</em>}
        </Typography>
      ),
    },
    {
      header: 'Items',
      render: (order) => (
        <Stack spacing={0.25}>
          {order.lineItems.map((line) => (
            <Typography key={line.id} variant="body2">
              {line.quantityOrdered} × {line.description}
            </Typography>
          ))}
        </Stack>
      ),
    },
    ...(costVisible
      ? [{ header: 'Total', align: 'right' as const, render: (order: PurchaseOrder) => money(order.total) }]
      : []),
    {
      header: 'Waiting since',
      align: 'right',
      secondary: true,
      render: (order) => when(order.requestedAt),
    },
  ];

  return (
    <>
      <PageHeader
        title="Approvals"
        subtitle="Purchase requests waiting on a decision. Approving one places it with the vendor."
      />

      {orders.isFetching && !orders.isLoading && <LinearProgress sx={{ mb: 1 }} />}

      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={rows}
          rowKey={(order) => order.id}
          loading={orders.isLoading}
          emptyMessage="Nothing is waiting for approval."
          onRowClick={(order) => navigate(`/purchase-orders/${order.id}`)}
          rowActions={(order) => (
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              <Button size="small" variant="contained" onClick={() => setApproving(order)}>
                Approve
              </Button>
              <Button size="small" color="error" onClick={() => setRejecting(order)}>
                Reject
              </Button>
            </Stack>
          )}
        />
      </Paper>

      <ApproveDialog open={Boolean(approving)} order={approving} onClose={() => setApproving(null)} />
      <ReasonDialog
        open={Boolean(rejecting)}
        order={rejecting}
        action="reject"
        onClose={() => setRejecting(null)}
      />
    </>
  );
}
