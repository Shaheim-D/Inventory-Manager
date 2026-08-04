import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  Grid,
  LinearProgress,
  Paper,
  Stack,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Asset, AuditEvent, Page, PurchaseOrder } from '../../api/types';
import { EntityTable } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';
import { useAuth } from '../../auth/AuthContext';
import { ApproveDialog, PurchaseDialog, ReasonDialog } from './OrderActionDialogs';
import { ReceiveDialog } from './ReceiveDialog';
import { money, orderLabel, RECEIVABLE, StatusChip, when } from './shared';

/**
 * One order, end to end: what was asked for, who decided what, every delivery
 * that has arrived against it, and the assets those deliveries created.
 *
 * The actions offered are derived from the order's status and the viewer's
 * permissions, but the server decides independently — an action that should not
 * be here is refused there too, and the refusal is shown rather than swallowed.
 */
export function PurchaseOrderDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { has, user } = useAuth();

  const [approving, setApproving] = useState(false);
  const [purchasing, setPurchasing] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [receiving, setReceiving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const order = useQuery({
    queryKey: ['purchase-order', id],
    queryFn: () => api.get<PurchaseOrder>(`/api/purchase-orders/${id}`),
  });

  // What this order turned into. Fetched by the order's own id rather than by
  // guessing from descriptions, so a renamed asset still shows up here.
  const created = useQuery({
    queryKey: ['purchase-order-assets', id],
    queryFn: () => api.get<Page<Asset>>(`/api/assets?purchaseOrderId=${id}&size=200`),
    enabled: has('asset:read'),
  });

  const audit = useQuery({
    queryKey: ['purchase-order-audit', id],
    queryFn: () => api.get<Page<AuditEvent>>(`/api/purchase-orders/${id}/audit?size=100`),
    enabled: has('audit:view'),
  });

  const submit = useMutation({
    mutationFn: () => api.post<PurchaseOrder>(`/api/purchase-orders/${id}/submit`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['purchase-order', id] });
      void queryClient.invalidateQueries({ queryKey: ['purchase-orders'] });
    },
    onError: (cause) =>
      setError(cause instanceof ApiError ? cause.message : 'Could not submit this request.'),
  });

  if (order.isLoading) return <LinearProgress />;
  if (order.isError || !order.data) {
    return <Alert severity="error">That purchase order could not be loaded.</Alert>;
  }

  const data = order.data;
  const costVisible = 'total' in data;
  const isAuthor = data.requestedBy === user?.username;

  const actions: React.ReactNode[] = [];
  if (data.status === 'DRAFT' && isAuthor) {
    actions.push(
      <Button key="edit" onClick={() => navigate(`/purchase-orders/order/${id}/edit`)}>
        Edit
      </Button>,
      <Button key="submit" variant="contained" onClick={() => submit.mutate()}>
        Submit for approval
      </Button>,
    );
  }
  if (data.status === 'SUBMITTED' && has('purchase_order:approve')) {
    actions.push(
      <Button key="reject" color="error" onClick={() => setRejecting(true)}>
        Deny
      </Button>,
      <Button key="approve" variant="contained" onClick={() => setApproving(true)}>
        Approve
      </Button>,
    );
  }
  if (data.status === 'APPROVED' && has('purchase_order:approve')) {
    actions.push(
      <Button key="purchase" variant="contained" onClick={() => setPurchasing(true)}>
        Mark purchased
      </Button>,
    );
  }
  if (data.quantityReceived > 0 && has('asset:read')) {
    actions.push(
      <Button key="items" onClick={() => navigate(`/assets?purchaseOrderId=${id}`)}>
        Show items
      </Button>,
    );
  }
  if (RECEIVABLE.includes(data.status) && has('purchase_order:receive')) {
    actions.push(
      <Button key="receive" variant="contained" onClick={() => setReceiving(true)}>
        Record delivery
      </Button>,
    );
  }
  // The service works out whether this particular person may cancel this
  // particular order; the button only asks whether cancelling is possible at all.
  if (
    ['DRAFT', 'SUBMITTED', 'APPROVED', 'ORDERED', 'PARTIALLY_RECEIVED'].includes(data.status) &&
    (isAuthor || has('purchase_order:approve'))
  ) {
    actions.push(
      <Button key="cancel" color="error" onClick={() => setCancelling(true)}>
        Cancel order
      </Button>,
    );
  }

  return (
    <>
      <PageHeader
        title={orderLabel(data)}
        subtitle={
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.5 }}>
            <StatusChip status={data.status} />
            <span>
              Request #{data.id}
              {data.vendor ? ` · ${data.vendor}` : ''}
            </span>
          </Stack>
        }
        actions={<>{actions}</>}
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {/* The reason is the whole point of a denial, so it is the first thing on
          the screen rather than a field further down. */}
      {data.status === 'REJECTED' && (
        <Alert severity="error" sx={{ mb: 2 }}>
          <strong>Denied</strong> by {data.rejectedBy ?? 'a purchaser'} on {when(data.rejectedAt)}
          {' — '}
          {data.rejectionReason}
        </Alert>
      )}

      <Grid container spacing={2} sx={{ mb: 2 }}>
        <Grid item xs={12} md={6}>
          <Card variant="outlined" sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="subtitle1" gutterBottom>
                The request
              </Typography>
              <Field label="Raised by" value={data.requestedBy ?? '—'} />
              <Field label="Submitted" value={when(data.requestedAt)} />
              <Field label="Justification" value={data.justification ?? '—'} />
              <Field label="Notes" value={data.notes ?? '—'} />
            </CardContent>
          </Card>
        </Grid>
        <Grid item xs={12} md={6}>
          <Card variant="outlined" sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="subtitle1" gutterBottom>
                The order
              </Typography>
              <Field label="Order number" value={data.orderNumber ?? 'Not bought yet'} />
              <Field label="Vendor" value={data.vendor ?? '—'} />
              <Field label="Purchase link" value={data.purchaseLink ?? '—'} />
              <Field label="Approved by" value={data.approvedBy ?? '—'} />
              <Field label="Approved" value={when(data.approvedAt)} />
              <Field label="Purchased by" value={data.orderedBy ?? '—'} />
              {/* Labelled "Purchased" rather than "Ordered" because this date is
                  copied onto every asset the order delivers as its purchase date. */}
              <Field label="Purchased" value={when(data.orderedAt)} />
              {costVisible && <Field label="Total" value={money(data.total)} />}
              <Field
                label="Received"
                value={`${data.quantityReceived} of ${data.quantityOrdered} item(s)`}
              />
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Line items
      </Typography>
      <Paper variant="outlined" sx={{ mb: 3 }}>
        <EntityTable
          columns={[
            // Column 0 is the card heading in narrow mode, so it stays a plain
            // string; the line's own notes get a column of their own rather
            // than being tucked under it. The catalogue name wins when there is
            // one, because it is what the received assets are called.
            { header: 'Item', render: (line) => line.deviceLabel ?? line.description },
            {
              header: 'Category',
              render: (line) => (
                <Stack spacing={0.25}>
                  <span>{line.categoryName}</span>
                  <Typography variant="caption" color="text.secondary">
                    {line.serialized ? 'One asset per unit' : 'Counted as stock'}
                  </Typography>
                </Stack>
              ),
            },
            { header: 'Ordered', align: 'right', render: (line) => line.quantityOrdered },
            { header: 'Received', align: 'right', render: (line) => line.quantityReceived },
            {
              header: 'Outstanding',
              align: 'right',
              render: (line) =>
                line.quantityOutstanding === 0 ? (
                  <Chip size="small" color="success" variant="outlined" label="Complete" />
                ) : (
                  line.quantityOutstanding
                ),
            },
            ...(costVisible
              ? [
                  { header: 'Unit price', align: 'right' as const, render: (line: (typeof data.lineItems)[number]) => money(line.unitPrice) },
                  { header: 'Line total', align: 'right' as const, render: (line: (typeof data.lineItems)[number]) => money(line.lineTotal) },
                ]
              : []),
            { header: 'Notes', secondary: true, render: (line) => line.notes ?? '—' },
          ]}
          rows={data.lineItems}
          rowKey={(line) => line.id}
          emptyMessage="This request has no line items."
        />
      </Paper>

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Deliveries
      </Typography>
      {(data.receipts ?? []).length === 0 ? (
        <Paper variant="outlined" sx={{ p: 3, mb: 3 }}>
          <Typography color="text.secondary">
            Nothing has been received against this order yet.
          </Typography>
        </Paper>
      ) : (
        <Stack spacing={1.5} sx={{ mb: 3 }}>
          {(data.receipts ?? []).map((receipt) => (
            <Card key={receipt.id} variant="outlined">
              <CardContent>
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  justifyContent="space-between"
                  spacing={1}
                >
                  <Typography variant="subtitle2">
                    Received by {receipt.receivedBy ?? '—'}
                  </Typography>
                  <Typography variant="body2" color="text.secondary">
                    {when(receipt.receivedAt)}
                  </Typography>
                </Stack>
                <Divider sx={{ my: 1 }} />
                <Stack spacing={0.5}>
                  {receipt.lines.map((line) => (
                    <Typography key={line.lineItemId} variant="body2">
                      {line.quantityReceived} × {line.description}
                    </Typography>
                  ))}
                </Stack>
                {receipt.notes && (
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                    {receipt.notes}
                  </Typography>
                )}
              </CardContent>
            </Card>
          ))}
        </Stack>
      )}

      {has('asset:read') && (created.data?.content.length ?? 0) > 0 && (
        <>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>
            Assets this order created
          </Typography>
          <Paper variant="outlined" sx={{ mb: 3 }}>
            <EntityTable
              columns={[
                { header: 'Asset', render: (asset: Asset) => asset.displayLabel },
                { header: 'Category', render: (asset: Asset) => asset.categoryName },
                { header: 'Location', render: (asset: Asset) => asset.locationName },
                {
                  header: 'Lifecycle',
                  render: (asset: Asset) => (
                    <Chip size="small" variant="outlined" label={asset.lifecycleStateName} />
                  ),
                },
                { header: 'Quantity', align: 'right', render: (asset: Asset) => asset.quantity },
              ]}
              rows={created.data?.content ?? []}
              rowKey={(asset) => asset.id}
              loading={created.isLoading}
              onRowClick={(asset) => navigate(`/assets/${asset.id}`)}
            />
          </Paper>
        </>
      )}

      {has('audit:view') && (
        <>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>
            History
          </Typography>
          <Paper variant="outlined">
            <EntityTable
              columns={[
                {
                  header: 'When',
                  render: (event: AuditEvent) => new Date(event.occurredAt).toLocaleString(),
                },
                { header: 'Who', render: (event: AuditEvent) => event.username },
                { header: 'Action', render: (event: AuditEvent) => event.action },
                {
                  header: 'Detail',
                  render: (event: AuditEvent) => event.newValue ?? event.reason ?? '—',
                },
              ]}
              rows={audit.data?.content ?? []}
              rowKey={(event) => event.id}
              loading={audit.isLoading}
              emptyMessage="Nothing recorded yet."
              cardTitle={(event) => event.action}
            />
          </Paper>
        </>
      )}

      <ApproveDialog open={approving} order={data} onClose={() => setApproving(false)} />
      <PurchaseDialog open={purchasing} order={data} onClose={() => setPurchasing(false)} />
      <ReasonDialog
        open={rejecting}
        order={data}
        action="reject"
        onClose={() => setRejecting(false)}
      />
      <ReasonDialog
        open={cancelling}
        order={data}
        action="cancel"
        onClose={() => setCancelling(false)}
      />
      <ReceiveDialog open={receiving} order={data} onClose={() => setReceiving(false)} />
    </>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ display: 'flex', gap: 2, py: 0.5 }}>
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 130, flexShrink: 0 }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
        {value}
      </Typography>
    </Box>
  );
}
