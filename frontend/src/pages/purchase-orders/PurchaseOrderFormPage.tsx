import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Divider,
  Grid,
  IconButton,
  LinearProgress,
  MenuItem,
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
import type { Category, PurchaseOrder } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { useAuth } from '../../auth/AuthContext';
import { money } from './shared';

interface LineDraft {
  /** Local only — line items are replaced wholesale on save, never patched. */
  key: string;
  categoryId: string;
  description: string;
  quantityOrdered: string;
  unitPrice: string;
  notes: string;
}

function emptyLine(): LineDraft {
  return {
    key: Math.random().toString(36).slice(2),
    categoryId: '',
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
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()]);
  const [error, setError] = useState<string | null>(null);

  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
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
    setLines(
      order.lineItems.length === 0
        ? [emptyLine()]
        : order.lineItems.map((item) => ({
            key: String(item.id),
            categoryId: String(item.categoryId),
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

  function body() {
    return {
      justification: justification.trim() || null,
      notes: notes.trim() || null,
      lineItems: lines
        .filter((line) => line.categoryId && line.description.trim())
        .map((line) => ({
          categoryId: Number(line.categoryId),
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
      navigate(`/purchase-orders/${order.id}`);
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
            <Button size="small" onClick={() => navigate(`/purchase-orders/${id}`)}>
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
        subtitle="Say what is needed and why. A purchaser turns it into a real order with the vendor."
        actions={
          <Button onClick={() => navigate(editing ? `/purchase-orders/${id}` : '/purchase-orders')}>
            Cancel
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack spacing={2}>
          <TextField
            label="Justification"
            placeholder="Why this is needed — the person approving it reads this first."
            value={justification}
            onChange={(event) => setJustification(event.target.value)}
            multiline
            minRows={2}
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

      <Typography variant="subtitle1" sx={{ mb: 1 }}>
        What is being ordered
      </Typography>

      <Stack spacing={2}>
        {lines.map((line, index) => (
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
                <Grid item xs={12} md={3}>
                  <TextField
                    select
                    label="Category"
                    required
                    value={line.categoryId}
                    onChange={(event) => setLine(line.key, { categoryId: event.target.value })}
                    helperText={
                      // Receiving behaves differently for the two kinds, and
                      // this is the moment that gets decided.
                      categoryOf(categories.data, line.categoryId)?.serialized
                        ? 'Received one asset per unit'
                        : line.categoryId
                          ? 'Received as a single counted row'
                          : ' '
                    }
                  >
                    {(categories.data ?? []).map((category) => (
                      <MenuItem key={category.id} value={String(category.id)}>
                        {category.name}
                      </MenuItem>
                    ))}
                  </TextField>
                </Grid>
                <Grid item xs={12} md={costVisible ? 4 : 6}>
                  <TextField
                    label="Description"
                    required
                    placeholder="Make and model, or what it is"
                    value={line.description}
                    onChange={(event) => setLine(line.key, { description: event.target.value })}
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
                  <Grid item xs={6} md={3}>
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
                <Grid item xs={12}>
                  <TextField
                    label="Line notes"
                    value={line.notes}
                    onChange={(event) => setLine(line.key, { notes: event.target.value })}
                  />
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        ))}
      </Stack>

      <Button
        startIcon={<AddIcon />}
        sx={{ mt: 2 }}
        onClick={() => setLines((current) => [...current, emptyLine()])}
      >
        Add another line
      </Button>

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

function categoryOf(categories: Category[] | undefined, id: string): Category | undefined {
  return id ? categories?.find((category) => String(category.id) === id) : undefined;
}
