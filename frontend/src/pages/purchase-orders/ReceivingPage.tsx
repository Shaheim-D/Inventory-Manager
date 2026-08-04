import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  LinearProgress,
  LinearProgress as Bar,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { PurchaseOrder } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { ReceiveDialog } from './ReceiveDialog';
import { orderLabel, RECEIVABLE, StatusChip } from './shared';

/**
 * The receiving queue: everything actually placed with a vendor and not yet
 * fully delivered.
 *
 * This is the one screen deliberately not built on `EntityTable`. A table's job
 * is comparison across rows, and receiving is not that — you have one box in
 * front of you and you are looking for its order. So it is a card list at every
 * width, with the order number large enough to match against a packing slip at
 * arm's length, rather than a dense grid that happens to fall back to cards on a
 * phone.
 */
export function ReceivingPage() {
  const navigate = useNavigate();
  const [receiving, setReceiving] = useState<PurchaseOrder | null>(null);

  const orders = useQuery({
    queryKey: ['purchase-orders', 'receiving'],
    queryFn: () => api.get<PurchaseOrder[]>('/api/purchase-orders'),
  });

  const queue = (orders.data ?? []).filter((order) => RECEIVABLE.includes(order.status));

  // The dialog holds its own copy, so it has to follow the refreshed list after
  // a partial receipt rather than showing the counts from before it.
  const current = receiving ? (queue.find((order) => order.id === receiving.id) ?? receiving) : null;

  return (
    <>
      <PageHeader
        title="Receiving"
        subtitle="Orders placed with a vendor and still waiting on some or all of their delivery."
      />

      {orders.isLoading && <LinearProgress />}

      {!orders.isLoading && queue.length === 0 && (
        <Paper variant="outlined" sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="text.secondary">
            Nothing is waiting to be received. Orders appear here once a purchaser has placed them.
          </Typography>
        </Paper>
      )}

      <Stack spacing={2}>
        {queue.map((order) => {
          const outstanding = order.quantityOrdered - order.quantityReceived;
          return (
            <Card key={order.id} variant="outlined">
              <CardContent>
                <Stack
                  direction={{ xs: 'column', md: 'row' }}
                  spacing={2}
                  justifyContent="space-between"
                  alignItems={{ xs: 'stretch', md: 'center' }}
                >
                  <Box sx={{ minWidth: 0 }}>
                    <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
                      <Typography variant="h6" sx={{ wordBreak: 'break-word' }}>
                        {orderLabel(order)}
                      </Typography>
                      <StatusChip status={order.status} />
                    </Stack>
                    <Typography variant="body2" color="text.secondary">
                      {order.vendor ? `${order.vendor} · ` : ''}Requested by {order.requestedBy ?? '—'}
                    </Typography>
                  </Box>

                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                    <Button size="large" onClick={() => navigate(`/purchase-orders/${order.id}`)}>
                      Details
                    </Button>
                    <Button size="large" variant="contained" onClick={() => setReceiving(order)}>
                      Receive
                    </Button>
                  </Stack>
                </Stack>

                <Box sx={{ mt: 2 }}>
                  <Stack direction="row" justifyContent="space-between" sx={{ mb: 0.5 }}>
                    <Typography variant="caption" color="text.secondary">
                      {order.quantityReceived} of {order.quantityOrdered} received
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {outstanding} outstanding
                    </Typography>
                  </Stack>
                  <Bar
                    variant="determinate"
                    value={
                      order.quantityOrdered === 0
                        ? 0
                        : (order.quantityReceived / order.quantityOrdered) * 100
                    }
                  />
                </Box>

                <Divider sx={{ my: 2 }} />

                <Stack spacing={0.75}>
                  {order.lineItems.map((line) => (
                    <Stack
                      key={line.id}
                      direction="row"
                      spacing={1}
                      justifyContent="space-between"
                      alignItems="center"
                    >
                      <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
                        {line.description}
                      </Typography>
                      <Chip
                        size="small"
                        variant="outlined"
                        color={line.quantityOutstanding === 0 ? 'success' : 'default'}
                        label={
                          line.quantityOutstanding === 0
                            ? 'Complete'
                            : `${line.quantityOutstanding} of ${line.quantityOrdered} to come`
                        }
                      />
                    </Stack>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          );
        })}
      </Stack>

      <ReceiveDialog open={Boolean(current)} order={current} onClose={() => setReceiving(null)} />
    </>
  );
}
