import { useEffect, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import RemoveIcon from '@mui/icons-material/Remove';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Location, PurchaseOrder } from '../../api/types';
import { locationOptions, locationOptionSx, locationPath } from '../../components/locationTree';
import { orderLabel } from './shared';

/**
 * Recording a delivery. This is the one screen most likely to be used standing
 * up holding a box, so it is built for that: full screen below the tablet
 * breakpoint, one card per line rather than a row of narrow cells, and quantity
 * changed by thumb-sized steppers with the keyboard entry still there for
 * someone at a desk receiving forty of something.
 *
 * The over-receipt rule is a database trigger, not a check repeated here. The
 * stepper stops at the outstanding count because that is a helpful thing for the
 * UI to do, but the server is what refuses, and its refusal is surfaced verbatim
 * rather than as a generic failure.
 */
export function ReceiveDialog({
  order,
  open,
  onClose,
}: {
  order: PurchaseOrder | null;
  open: boolean;
  onClose: () => void;
}) {
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('md'));
  const queryClient = useQueryClient();

  const [locationId, setLocationId] = useState('');
  const [notes, setNotes] = useState('');
  const [quantities, setQuantities] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);

  const locations = useQuery({
    queryKey: ['locations'],
    queryFn: () => api.get<Location[]>('/api/locations'),
    enabled: open,
  });

  // Reopening for a different order must not inherit the last one's counts.
  useEffect(() => {
    if (!open) return;
    setQuantities({});
    setNotes('');
    setError(null);
  }, [open, order?.id]);

  const lines = (order?.lineItems ?? []).filter((line) => line.quantityOutstanding > 0);

  function setQuantity(lineItemId: number, value: number, max: number) {
    const clamped = Math.max(0, Math.min(value, max));
    setQuantities((current) => ({ ...current, [lineItemId]: clamped === 0 ? '' : String(clamped) }));
  }

  const entered = lines
    .map((line) => ({ lineItemId: line.id, quantityReceived: Number(quantities[line.id] || '0') }))
    .filter((line) => line.quantityReceived > 0);

  const receive = useMutation({
    mutationFn: () =>
      api.post<PurchaseOrder>(`/api/purchase-orders/${order!.id}/receipts`, {
        locationId: Number(locationId),
        notes: notes.trim() || null,
        lines: entered,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['purchase-orders'] });
      void queryClient.invalidateQueries({ queryKey: ['purchase-order', String(order!.id)] });
      void queryClient.invalidateQueries({ queryKey: ['assets'] });
      onClose();
    },
    onError: (cause) =>
      setError(cause instanceof ApiError ? cause.message : 'Could not record this delivery.'),
  });

  return (
    <Dialog open={open} onClose={onClose} fullScreen={fullScreen} fullWidth maxWidth="md">
      <DialogTitle>{order ? `Receive ${orderLabel(order)}` : 'Receive'}</DialogTitle>
      <DialogContent dividers>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <TextField
          select
          required
          label="Where did it arrive?"
          value={locationId}
          onChange={(event) => setLocationId(event.target.value)}
          sx={{ mb: 2 }}
          helperText="Everything in this delivery is booked into that location."
          // The same hierarchy the assets screen shows: a delivery goes to a
          // rack inside a site far more often than to the site itself, and
          // "Rack 4" on its own does not say which one.
          SelectProps={{ renderValue: (value) => locationPath(locations.data, Number(value)) }}
        >
          {locationOptions(locations.data)
            .filter((option) => option.location.active)
            .map((option) => (
              <MenuItem
                key={option.location.id}
                value={String(option.location.id)}
                sx={locationOptionSx(option.depth)}
              >
                {option.location.name}
              </MenuItem>
            ))}
        </TextField>

        {lines.length === 0 && (
          <Alert severity="success">Everything on this order has already been received.</Alert>
        )}

        <Stack spacing={1.5}>
          {lines.map((line) => {
            const value = Number(quantities[line.id] || '0');
            return (
              <Card key={line.id} variant="outlined">
                <CardContent>
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={2}
                    justifyContent="space-between"
                    alignItems={{ xs: 'stretch', sm: 'center' }}
                  >
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="subtitle1" sx={{ wordBreak: 'break-word' }}>
                        {line.description}
                      </Typography>
                      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.5 }}>
                        <Chip size="small" variant="outlined" label={line.categoryName} />
                        <Typography variant="caption" color="text.secondary">
                          {line.quantityOutstanding} of {line.quantityOrdered} still outstanding
                          {line.serialized ? ' · one asset per unit' : ' · counted as stock'}
                        </Typography>
                      </Stack>
                    </Box>

                    <Stack direction="row" spacing={1} alignItems="center" justifyContent="flex-end">
                      {/* Deliberately not IconButton size="small": these get
                          pressed with a thumb, so they keep a 48px hit area. */}
                      <IconButton
                        aria-label={`One fewer ${line.description}`}
                        onClick={() => setQuantity(line.id, value - 1, line.quantityOutstanding)}
                        disabled={value === 0}
                        sx={{ border: 1, borderColor: 'divider', width: 48, height: 48 }}
                      >
                        <RemoveIcon />
                      </IconButton>
                      <TextField
                        label="Received"
                        value={quantities[line.id] ?? ''}
                        onChange={(event) =>
                          setQuantity(
                            line.id,
                            Number(event.target.value.replace(/[^0-9]/g, '') || '0'),
                            line.quantityOutstanding,
                          )
                        }
                        inputProps={{ inputMode: 'numeric', style: { textAlign: 'center' } }}
                        sx={{ width: 110 }}
                      />
                      <IconButton
                        aria-label={`One more ${line.description}`}
                        onClick={() => setQuantity(line.id, value + 1, line.quantityOutstanding)}
                        disabled={value >= line.quantityOutstanding}
                        sx={{ border: 1, borderColor: 'divider', width: 48, height: 48 }}
                      >
                        <AddIcon />
                      </IconButton>
                      <Button
                        size="small"
                        onClick={() =>
                          setQuantity(line.id, line.quantityOutstanding, line.quantityOutstanding)
                        }
                      >
                        All
                      </Button>
                    </Stack>
                  </Stack>
                </CardContent>
              </Card>
            );
          })}
        </Stack>

        {lines.length > 0 && (
          <TextField
            label="Delivery notes"
            placeholder="Packing slip number, damage, anything short-shipped."
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
            multiline
            minRows={2}
            sx={{ mt: 2 }}
          />
        )}
      </DialogContent>
      <DialogActions sx={{ p: 2 }}>
        <Button onClick={onClose} size="large">
          Cancel
        </Button>
        <Button
          variant="contained"
          size="large"
          disabled={!locationId || entered.length === 0 || receive.isPending}
          onClick={() => receive.mutate()}
        >
          {entered.length === 0
            ? 'Record delivery'
            : `Record ${entered.reduce((sum, line) => sum + line.quantityReceived, 0)} item(s)`}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
