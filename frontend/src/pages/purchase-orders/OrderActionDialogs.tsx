import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { PurchaseOrder } from '../../api/types';
import { orderLabel } from './shared';

/**
 * The dialogs behind approve, reject and cancel. They live together because all
 * three are the same shape — confirm an irreversible decision, capture the one
 * thing that decision has to record — and because the approvals queue and the
 * order detail screen both need every one of them.
 */

function useOrderAction(order: PurchaseOrder | null, onDone: () => void, setError: (m: string) => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ action, body }: { action: string; body: unknown }) =>
      api.post<PurchaseOrder>(`/api/purchase-orders/${order!.id}/${action}`, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['purchase-orders'] });
      void queryClient.invalidateQueries({ queryKey: ['purchase-order', String(order!.id)] });
      onDone();
    },
    onError: (cause) =>
      setError(cause instanceof ApiError ? cause.message : 'That did not go through.'),
  });
}

/**
 * Approving is placing the order, so it captures the vendor's order number in
 * the same breath. A CHECK constraint insists on one for any status past this
 * point, which is why the field is required here rather than optional with a
 * reminder later.
 */
export function ApproveDialog({
  order,
  open,
  onClose,
}: {
  order: PurchaseOrder | null;
  open: boolean;
  onClose: () => void;
}) {
  const [orderNumber, setOrderNumber] = useState('');
  const [vendor, setVendor] = useState('');
  const [error, setError] = useState<string | null>(null);
  const approve = useOrderAction(order, onClose, setError);

  useEffect(() => {
    if (!open) return;
    setOrderNumber(order?.orderNumber ?? '');
    setVendor(order?.vendor ?? '');
    setError(null);
  }, [open, order]);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Approve and place {order ? orderLabel(order) : 'this order'}</DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <DialogContentText sx={{ mb: 2 }}>
          Approving records that this order has actually been placed with a vendor. It becomes
          receivable straight away.
        </DialogContentText>
        <Stack spacing={2}>
          <TextField
            label="Vendor order number"
            required
            autoFocus
            value={orderNumber}
            onChange={(event) => setOrderNumber(event.target.value)}
            helperText="How the vendor identifies this order — it is what a delivery gets matched against."
          />
          <TextField
            label="Vendor"
            value={vendor}
            onChange={(event) => setVendor(event.target.value)}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!orderNumber.trim() || approve.isPending}
          onClick={() =>
            approve.mutate({
              action: 'approve',
              body: { orderNumber: orderNumber.trim(), vendor: vendor.trim() || null },
            })
          }
        >
          Approve and place
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** Reject and cancel differ only in whether the reason is compulsory. */
export function ReasonDialog({
  order,
  open,
  action,
  onClose,
}: {
  order: PurchaseOrder | null;
  open: boolean;
  action: 'reject' | 'cancel';
  onClose: () => void;
}) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const run = useOrderAction(order, onClose, setError);

  useEffect(() => {
    if (!open) return;
    setReason('');
    setError(null);
  }, [open]);

  const rejecting = action === 'reject';

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>
        {rejecting ? 'Reject' : 'Cancel'} {order ? orderLabel(order) : 'this order'}
      </DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <DialogContentText sx={{ mb: 2 }}>
          {rejecting
            ? 'The person who raised this will see the reason, so say enough that they know whether to ask again differently.'
            : 'Cancelling closes the order for good. Anything already received against it stays where it is.'}
        </DialogContentText>
        <TextField
          label={rejecting ? 'Reason' : 'Reason (optional)'}
          required={rejecting}
          autoFocus
          multiline
          minRows={3}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Back</Button>
        <Button
          variant="contained"
          color="error"
          disabled={(rejecting && !reason.trim()) || run.isPending}
          onClick={() => run.mutate({ action, body: { reason: reason.trim() || null } })}
        >
          {rejecting ? 'Reject request' : 'Cancel order'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
