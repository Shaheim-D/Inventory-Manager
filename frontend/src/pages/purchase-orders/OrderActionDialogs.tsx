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
 * Approving agrees to the request and nothing more. It asks for no order number
 * because there is nothing to number yet — the thing has not been bought.
 * Requiring one here only ever meant somebody invented it.
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
  const [error, setError] = useState<string | null>(null);
  const approve = useOrderAction(order, onClose, setError);

  useEffect(() => {
    if (open) setError(null);
  }, [open]);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Approve {order ? orderLabel(order) : 'this request'}</DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <DialogContentText>
          This agrees to the request. It moves to Purchasing, where someone records the order number
          and the price once it has actually been bought.
        </DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={approve.isPending}
          onClick={() => approve.mutate({ action: 'approve', body: {} })}
        >
          Approve
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Recording that it has been bought. This is where the vendor's order number
 * comes from, and where the vendor and link may change — a requester says where
 * they think it should come from, and the purchaser knows where it actually came
 * from. Whatever this ends up saying is what the received assets record.
 */
export function PurchaseDialog({
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
  const [purchaseLink, setPurchaseLink] = useState('');
  const [error, setError] = useState<string | null>(null);
  const purchase = useOrderAction(order, onClose, setError);

  useEffect(() => {
    if (!open) return;
    setOrderNumber(order?.orderNumber ?? '');
    setVendor(order?.vendor ?? '');
    setPurchaseLink(order?.purchaseLink ?? '');
    setError(null);
  }, [open, order]);

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Mark {order ? orderLabel(order) : 'this order'} purchased</DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <DialogContentText sx={{ mb: 2 }}>
          Today becomes the purchase date of everything this order delivers, and it becomes
          receivable straight away.
        </DialogContentText>
        <Stack spacing={2}>
          <TextField
            label="Order number"
            required
            autoFocus
            value={orderNumber}
            onChange={(event) => setOrderNumber(event.target.value)}
            helperText="How the vendor identifies this order. It is copied onto every asset it delivers."
          />
          <TextField
            label="Vendor"
            value={vendor}
            onChange={(event) => setVendor(event.target.value)}
            helperText="Where it was actually bought, which need not be where the requester suggested."
          />
          <TextField
            label="Purchase link"
            value={purchaseLink}
            onChange={(event) => setPurchaseLink(event.target.value)}
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!orderNumber.trim() || purchase.isPending}
          onClick={() =>
            purchase.mutate({
              action: 'purchase',
              body: {
                orderNumber: orderNumber.trim(),
                vendor: vendor.trim() || null,
                purchaseLink: purchaseLink.trim() || null,
              },
            })
          }
        >
          Mark purchased
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** Deny and cancel differ only in whether the reason is compulsory. */
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
        {rejecting ? 'Deny' : 'Cancel'} {order ? orderLabel(order) : 'this order'}
      </DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <DialogContentText sx={{ mb: 2 }}>
          {rejecting
            ? 'The reason is shown to the person who raised this and to anyone else who can see the order, so say enough that they know whether to ask again differently.'
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
          {rejecting ? 'Deny request' : 'Cancel order'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
