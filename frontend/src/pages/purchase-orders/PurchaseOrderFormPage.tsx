import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  IconButton,
  LinearProgress,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Category, DeviceModel, PurchaseOrder } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { CategoryPicker } from '../../components/CategoryPicker';
import { useAuth } from '../../auth/AuthContext';
import { money } from './shared';

interface LineDraft {
  /** Local only — line items are replaced wholesale on save, never patched. */
  key: string;
  categoryId: string;
  deviceModelId: string;
  description: string;
  quantityOrdered: string;
  unitPrice: string;
  notes: string;
}

function emptyLine(): LineDraft {
  return {
    key: Math.random().toString(36).slice(2),
    categoryId: '',
    deviceModelId: '',
    description: '',
    quantityOrdered: '1',
    unitPrice: '',
    notes: '',
  };
}

/**
 * New request, and editing one that has not been submitted yet. The two are the
 * same form because they are the same act — a draft is a request still being
 * written, and the server refuses an edit the moment it stops being one.
 *
 * Unit price is offered only with `purchase_order:cost:view`. Someone without it
 * can still raise a request; they just cannot put a number on it, which is the
 * correct division — knowing what you need is not the same job as knowing what
 * it costs.
 */
export function PurchaseOrderFormPage() {
  const { id } = useParams();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { has } = useAuth();
  const costVisible = has('purchase_order:cost:view');

  const [justification, setJustification] = useState('');
  const [notes, setNotes] = useState('');
  const [vendor, setVendor] = useState('');
  const [purchaseLink, setPurchaseLink] = useState('');
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()]);
  const [error, setError] = useState<string | null>(null);

  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
  });

  const devices = useQuery({
    queryKey: ['device-models'],
    queryFn: () => api.get<DeviceModel[]>('/api/device-models'),
  });

  const existing = useQuery({
    queryKey: ['purchase-order', id],
    queryFn: () => api.get<PurchaseOrder>(`/api/purchase-orders/${id}`),
    enabled: editing,
  });

  useEffect(() => {
    const order = existing.data;
    if (!order) return;
    setJustification(order.justification ?? '');
    setNotes(order.notes ?? '');
    setVendor(order.vendor ?? '');
    setPurchaseLink(order.purchaseLink ?? '');
    setLines(
      order.lineItems.length === 0
        ? [emptyLine()]
        : order.lineItems.map((item) => ({
            key: String(item.id),
            categoryId: String(item.categoryId),
            deviceModelId: item.deviceModelId == null ? '' : String(item.deviceModelId),
            description: item.description,
            quantityOrdered: String(item.quantityOrdered),
            unitPrice: item.unitPrice == null ? '' : String(item.unitPrice),
            notes: item.notes ?? '',
          })),
    );
  }, [existing.data]);

  function setLine(key: string, patch: Partial<LineDraft>) {
    setLines((current) => current.map((line) => (line.key === key ? { ...line, ...patch } : line)));
  }

  /**
   * Picking a catalogue device fills what the catalogue knows: the category, a
   * description, and the price it was last bought at. It deliberately does not
   * touch the vendor — the same switch is sold by several distributors, and
   * having the last one silently win is exactly the mistake that gets a PO sent
   * to the wrong place.
   */
  function chooseDevice(line: LineDraft, device: DeviceModel | null) {
    if (!device) {
      setLine(line.key, { deviceModelId: '' });
      return;
    }
    setLine(line.key, {
      deviceModelId: String(device.id),
      categoryId: device.categoryId != null ? String(device.categoryId) : line.categoryId,
      description: line.description.trim() || `${device.manufacturer} ${device.model}`,
      unitPrice:
        costVisible && !line.unitPrice && device.defaultPrice != null
          ? String(device.defaultPrice)
          : line.unitPrice,
    });
  }

  function body() {
    return {
      justification: justification.trim() || null,
      notes: notes.trim() || null,
      vendor: vendor.trim() || null,
      purchaseLink: purchaseLink.trim() || null,
      lineItems: lines
        .filter((line) => line.categoryId && line.description.trim())
        .map((line) => ({
          categoryId: Number(line.categoryId),
          deviceModelId: line.deviceModelId ? Number(line.deviceModelId) : null,
          description: line.description.trim(),
          quantityOrdered: Number(line.quantityOrdered || '0'),
          // An omitted price and a price of zero are different claims, so an
          // empty box stays null rather than becoming 0.00.
          unitPrice: costVisible && line.unitPrice !== '' ? Number(line.unitPrice) : null,
          notes: line.notes.trim() || null,
        })),
    };
  }

  const save = useMutation({
    mutationFn: async (submitNow: boolean) => {
      if (editing) {
        const updated = await api.put<PurchaseOrder>(`/api/purchase-orders/${id}`, body());
        return submitNow
          ? api.post<PurchaseOrder>(`/api/purchase-orders/${updated.id}/submit`)
          : updated;
      }
      return api.post<PurchaseOrder>(`/api/purchase-orders?submit=${submitNow}`, body());
    },
    onSuccess: (order) => {
      void queryClient.invalidateQueries({ queryKey: ['purchase-orders'] });
      void queryClient.invalidateQueries({ queryKey: ['purchase-order', String(order.id)] });
      navigate(`/purchase-orders/order/${order.id}`);
    },
    onError: (cause) =>
      setError(cause instanceof ApiError ? cause.message : 'Could not save this request.'),
  });

  const usable = lines.filter((line) => line.categoryId && line.description.trim());
  const runningTotal = usable.reduce(
    (sum, line) => sum + Number(line.unitPrice || '0') * Number(line.quantityOrdered || '0'),
    0,
  );

  if (editing && existing.isLoading) return <LinearProgress />;
  if (editing && existing.data && existing.data.status !== 'DRAFT') {
    return (
      <>
        <PageHeader title="Purchase request" />
        <Alert
          severity="info"
          action={
            <Button size="small" onClick={() => navigate(`/purchase-orders/order/${id}`)}>
              View it
            </Button>
          }
        >
          This request has already been submitted, so it can no longer be edited.
        </Alert>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={editing ? 'Edit purchase request' : 'New purchase request'}
        help="Say what is needed and why. A purchaser approves it, then buys it."
        actions={
          <Button onClick={() => navigate(editing ? `/purchase-orders/order/${id}` : '/purchase-orders')}>
            Cancel
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Where to buy it
      </Typography>
      <Paper variant="outlined" sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={4}>
            <TextField
              label="Vendor"
              placeholder="Who to buy it from"
              value={vendor}
              onChange={(event) => setVendor(event.target.value)}
              helperText="A suggestion. The purchaser confirms it, and may buy elsewhere."
            />
          </Grid>
          <Grid item xs={12} md={8}>
            <TextField
              label="Purchase link"
              placeholder="https://…"
              value={purchaseLink}
              onChange={(event) => setPurchaseLink(event.target.value)}
              helperText="The page it should be bought from, so nobody has to go looking for it."
            />
          </Grid>
        </Grid>
      </Paper>

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        What is being ordered
      </Typography>

      <Stack spacing={2}>
        {lines.map((line, index) => {
          const device = devices.data?.find((entry) => String(entry.id) === line.deviceModelId) ?? null;
          const category = categories.data?.find((entry) => String(entry.id) === line.categoryId);

          return (
            <Card key={line.key} variant="outlined">
              <CardContent>
                <Stack
                  direction="row"
                  justifyContent="space-between"
                  alignItems="center"
                  sx={{ mb: 1 }}
                >
                  <Typography variant="subtitle2" color="text.secondary">
                    Line {index + 1}
                  </Typography>
                  <Tooltip title="Remove this line">
                    <span>
                      <IconButton
                        size="small"
                        disabled={lines.length === 1}
                        onClick={() =>
                          setLines((current) => current.filter((entry) => entry.key !== line.key))
                        }
                      >
                        <DeleteOutlineIcon fontSize="small" />
                      </IconButton>
                    </span>
                  </Tooltip>
                </Stack>

                <Grid container spacing={2}>
                  <Grid item xs={12} md={5}>
                    {/* Free text is still allowed: buying something not in the
                        catalogue is normal, and the picker must not be a gate. */}
                    <Autocomplete
                      options={(devices.data ?? []).filter((entry) => entry.active)}
                      value={device}
                      onChange={(_, next) => chooseDevice(line, next)}
                      getOptionLabel={(entry) => `${entry.manufacturer} - ${entry.model}`}
                      groupBy={(entry) => entry.categoryName ?? 'Uncategorised'}
                      isOptionEqualToValue={(a, b) => a.id === b.id}
                      renderInput={(params) => (
                        <TextField
                          {...params}
                          label="Device from the catalogue"
                          helperText="Optional. Names the received assets and fills the category and price."
                        />
                      )}
                    />
                  </Grid>
                  <Grid item xs={12} md={3}>
                    {/*
                      A picker rather than a select: the list opens beneath the
                      field instead of over it, it can be typed into, and a
                      category that does not exist yet can be made here rather
                      than by abandoning a half-written request.
                    */}
                    <CategoryPicker
                      required
                      value={line.categoryId ? Number(line.categoryId) : null}
                      onChange={(categoryId) =>
                        setLine(line.key, { categoryId: categoryId == null ? '' : String(categoryId) })
                      }
                      emptyLabel="Choose a category"
                      helperText={
                        // Receiving behaves differently for the two kinds, and
                        // this is the moment that gets decided.
                        category?.serialized
                          ? 'Received one asset per unit'
                          : line.categoryId
                            ? 'Received as a single counted row'
                            : ' '
                      }
                    />
                  </Grid>
                  <Grid item xs={6} md={2}>
                    <TextField
                      label="Quantity"
                      required
                      value={line.quantityOrdered}
                      onChange={(event) =>
                        setLine(line.key, {
                          quantityOrdered: event.target.value.replace(/[^0-9]/g, ''),
                        })
                      }
                    />
                  </Grid>
                  {costVisible && (
                    <Grid item xs={6} md={2}>
                      <TextField
                        label="Unit price"
                        placeholder="0.00"
                        value={line.unitPrice}
                        onChange={(event) =>
                          setLine(line.key, { unitPrice: event.target.value.replace(/[^0-9.]/g, '') })
                        }
                        helperText={
                          line.unitPrice
                            ? money(Number(line.unitPrice) * Number(line.quantityOrdered || '0'))
                            : ' '
                        }
                      />
                    </Grid>
                  )}
                  <Grid item xs={12} md={6}>
                    <TextField
                      label="Description"
                      required
                      placeholder="What it is, if the catalogue does not already say"
                      value={line.description}
                      onChange={(event) => setLine(line.key, { description: event.target.value })}
                    />
                  </Grid>
                  <Grid item xs={12} md={6}>
                    <TextField
                      label="Line notes"
                      value={line.notes}
                      onChange={(event) => setLine(line.key, { notes: event.target.value })}
                    />
                  </Grid>
                </Grid>
              </CardContent>
            </Card>
          );
        })}
      </Stack>

      <Button
        startIcon={<AddIcon />}
        sx={{ mt: 2, mb: 3 }}
        onClick={() => setLines((current) => [...current, emptyLine()])}
      >
        Add another line
      </Button>

      {/* At the bottom on purpose: the explanation reads better after the thing
          being explained, and an approver has the list in view while reading it. */}
      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        Why it is needed
      </Typography>
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Stack spacing={2}>
          <TextField
            label="Justification"
            placeholder="The person approving this reads it first."
            value={justification}
            onChange={(event) => setJustification(event.target.value)}
            multiline
            minRows={3}
          />
          <TextField
            label="Notes"
            placeholder="Anything else worth recording against the order."
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
            multiline
            minRows={2}
          />
        </Stack>
      </Paper>

      <Divider sx={{ my: 3 }} />

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ xs: 'stretch', sm: 'center' }}
      >
        <Box>
          {costVisible && runningTotal > 0 && (
            <Typography variant="body2" color="text.secondary">
              Estimated total {money(runningTotal)}
            </Typography>
          )}
          {usable.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              Every line needs a category and a description before this can be saved.
            </Typography>
          )}
        </Box>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
          <Button
            variant="outlined"
            disabled={usable.length === 0 || save.isPending}
            onClick={() => save.mutate(false)}
          >
            Save draft
          </Button>
          <Button
            variant="contained"
            disabled={usable.length === 0 || save.isPending}
            onClick={() => save.mutate(true)}
          >
            Submit for approval
          </Button>
        </Stack>
      </Stack>
    </>
  );
}
