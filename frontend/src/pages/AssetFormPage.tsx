import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Divider,
  Grid,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Asset, Category, CustomFieldDefinition, Location } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { DynamicFieldForm } from '../components/DynamicFieldForm';

type FormState = Record<string, unknown>;

export function AssetFormPage() {
  const { id } = useParams<{ id: string }>();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [form, setForm] = useState<FormState>({ assigneeType: 'NONE', quantity: 1 });
  const [customFields, setCustomFields] = useState<Record<string, unknown>>({});
  const [error, setError] = useState<string | null>(null);

  const categories = useQuery({ queryKey: ['categories'], queryFn: () => api.get<Category[]>('/api/categories') });
  const locations = useQuery({ queryKey: ['locations'], queryFn: () => api.get<Location[]>('/api/locations') });

  const existing = useQuery({
    queryKey: ['asset', id],
    queryFn: () => api.get<Asset>(`/api/assets/${id}`),
    enabled: editing,
  });

  useEffect(() => {
    if (!existing.data) return;
    const asset = existing.data;
    setForm({
      categoryId: asset.categoryId,
      locationId: asset.locationId,
      name: asset.name ?? '',
      manufacturer: asset.manufacturer ?? '',
      model: asset.model ?? '',
      serialNumber: asset.serialNumber ?? '',
      assetTag: asset.assetTag ?? '',
      managementIp: asset.managementIp ?? '',
      hostname: asset.hostname ?? '',
      firmwareVersion: asset.firmwareVersion ?? '',
      softwareVersion: asset.softwareVersion ?? '',
      deviceRole: asset.deviceRole ?? '',
      purchaseDate: asset.purchaseDate ?? '',
      vendor: asset.vendor ?? '',
      warrantyStart: asset.warrantyStart ?? '',
      warrantyExpiration: asset.warrantyExpiration ?? '',
      condition: asset.condition ?? '',
      customerName: asset.customerName ?? '',
      notes: asset.notes ?? '',
      assigneeType: asset.assigneeType,
      quantity: asset.quantity,
      // Only seeded when the server sent them. A field the viewer cannot see is
      // never in this form, and therefore never submitted back as a blank.
      ...('purchasePrice' in asset ? { purchasePrice: asset.purchasePrice ?? '' } : {}),
      ...('invoiceNumber' in asset ? { invoiceNumber: asset.invoiceNumber ?? '' } : {}),
      ...('purchaseLink' in asset ? { purchaseLink: asset.purchaseLink ?? '' } : {}),
      ...('assigneeText' in asset ? { assigneeText: asset.assigneeText ?? '' } : {}),
      ...('assigneeUserId' in asset ? { assigneeUserId: asset.assigneeUserId ?? '' } : {}),
    });
    setCustomFields({ ...asset.customFields });
  }, [existing.data]);

  const categoryId = form.categoryId ? Number(form.categoryId) : undefined;
  const category = categories.data?.find((entry) => entry.id === categoryId);

  const definitions = useQuery({
    queryKey: ['custom-fields', categoryId],
    queryFn: () => api.get<CustomFieldDefinition[]>(`/api/categories/${categoryId}/custom-fields`),
    enabled: Boolean(categoryId),
  });

  const hiddenCore = useMemo(
    () => new Set(existing.data?.hiddenFields ?? []),
    [existing.data?.hiddenFields],
  );

  const save = useMutation({
    mutationFn: (payload: unknown) =>
      editing ? api.put<Asset>(`/api/assets/${id}`, payload) : api.post<Asset>('/api/assets', payload),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['assets'] });
      void queryClient.invalidateQueries({ queryKey: ['asset', String(saved.id)] });
      navigate(`/assets/${saved.id}`);
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not save this asset.'),
  });

  function set(field: string, value: unknown) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);

    const payload: Record<string, unknown> = { customFields };
    for (const [key, value] of Object.entries(form)) {
      payload[key] = value === '' ? null : value;
    }
    payload.categoryId = Number(form.categoryId);
    payload.locationId = Number(form.locationId);
    payload.quantity = category?.serialized ? 1 : Number(form.quantity ?? 1);
    if (form.purchasePrice !== undefined && form.purchasePrice !== '') {
      payload.purchasePrice = Number(form.purchasePrice);
    }
    if (form.assigneeUserId !== undefined && form.assigneeUserId !== '') {
      payload.assigneeUserId = Number(form.assigneeUserId);
    }
    save.mutate(payload);
  }

  if (editing && existing.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  const assigneeHidden = hiddenCore.has('assignee_text') || hiddenCore.has('assignee_user_id');

  return (
    <Box component="form" onSubmit={submit}>
      <PageHeader
        title={editing ? 'Edit asset' : 'New asset'}
        subtitle={editing ? existing.data?.displayLabel : 'Fields adapt to the category you choose.'}
        actions={
          <>
            <Button onClick={() => navigate(editing ? `/assets/${id}` : '/assets')}>Cancel</Button>
            <Button type="submit" variant="contained" disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Save'}
            </Button>
          </>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
        <Grid container spacing={2}>
          <Grid item xs={12} sm={6}>
            <TextField
              select
              required
              label="Category"
              value={form.categoryId ?? ''}
              onChange={(event) => set('categoryId', event.target.value)}
            >
              {(categories.data ?? []).map((entry) => (
                <MenuItem key={entry.id} value={entry.id}>
                  {entry.name}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6}>
            <TextField
              select
              required
              label="Location"
              value={form.locationId ?? ''}
              onChange={(event) => set('locationId', event.target.value)}
            >
              {(locations.data ?? [])
                .filter((entry) => entry.active)
                .map((entry) => (
                  <MenuItem key={entry.id} value={entry.id}>
                    {entry.name}
                  </MenuItem>
                ))}
            </TextField>
          </Grid>

          <Field label="Name" field="name" form={form} set={set} sm={6} />
          <Field label="Asset tag" field="assetTag" form={form} set={set} sm={6} />
          <Field label="Serial number" field="serialNumber" form={form} set={set} sm={6} />
          <Field label="Hostname" field="hostname" form={form} set={set} sm={6} />
          <Field label="Management IP" field="managementIp" form={form} set={set} sm={6} />
          <Field label="Manufacturer" field="manufacturer" form={form} set={set} sm={6} />
          <Field label="Model" field="model" form={form} set={set} sm={6} />
          <Field label="Firmware version" field="firmwareVersion" form={form} set={set} sm={6} />
          <Field label="Software version" field="softwareVersion" form={form} set={set} sm={6} />
          <Field label="Device role" field="deviceRole" form={form} set={set} sm={6} />
          <Field label="Condition" field="condition" form={form} set={set} sm={6} />

          {/* Quantity is a bulk-category concept; the form reads is_serialized
              rather than special-casing any category by name. */}
          {category && !category.serialized && (
            <Grid item xs={12} sm={6}>
              <TextField
                label="Quantity on hand"
                type="number"
                inputProps={{ min: 1 }}
                value={form.quantity ?? 1}
                onChange={(event) => set('quantity', event.target.value)}
                helperText="Changing this records a physical verification."
              />
            </Grid>
          )}

          <Grid item xs={12}>
            <Divider sx={{ my: 1 }} />
            <Typography variant="subtitle2" color="text.secondary">
              Purchase & warranty
            </Typography>
          </Grid>

          <Field label="Purchase date" field="purchaseDate" form={form} set={set} sm={6} type="date" />
          <Field label="Vendor" field="vendor" form={form} set={set} sm={6} />
          {!hiddenCore.has('purchase_price') && (
            <Field label="Purchase price" field="purchasePrice" form={form} set={set} sm={6} type="number" />
          )}
          {!hiddenCore.has('invoice_number') && (
            <Field label="Invoice number" field="invoiceNumber" form={form} set={set} sm={6} />
          )}
          {!hiddenCore.has('purchase_link') && (
            <Field label="Purchase link" field="purchaseLink" form={form} set={set} sm={6} />
          )}
          <Field label="Warranty start" field="warrantyStart" form={form} set={set} sm={6} type="date" />
          <Field label="Warranty expiration" field="warrantyExpiration" form={form} set={set} sm={6} type="date" />

          {!assigneeHidden && (
            <>
              <Grid item xs={12}>
                <Divider sx={{ my: 1 }} />
                <Typography variant="subtitle2" color="text.secondary">
                  Custody
                </Typography>
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  select
                  label="Assignee type"
                  value={form.assigneeType ?? 'NONE'}
                  onChange={(event) => set('assigneeType', event.target.value)}
                >
                  <MenuItem value="NONE">Nobody</MenuItem>
                  <MenuItem value="FREE_TEXT">A named person</MenuItem>
                  <MenuItem value="USER">An Inventory Manager user</MenuItem>
                </TextField>
              </Grid>
              {form.assigneeType === 'FREE_TEXT' && (
                <Field label="Assignee name" field="assigneeText" form={form} set={set} sm={6} />
              )}
              {form.assigneeType === 'USER' && (
                <Field label="Assignee user id" field="assigneeUserId" form={form} set={set} sm={6} type="number" />
              )}
            </>
          )}

          {definitions.data && definitions.data.length > 0 && (
            <>
              <Grid item xs={12}>
                <Divider sx={{ my: 1 }} />
                <Typography variant="subtitle2" color="text.secondary">
                  {category?.name} fields
                </Typography>
              </Grid>
              <Grid item xs={12}>
                <DynamicFieldForm
                  definitions={definitions.data}
                  values={customFields}
                  onChange={(fieldName, value) =>
                    setCustomFields((current) => ({ ...current, [fieldName]: value }))
                  }
                />
              </Grid>
            </>
          )}

          <Grid item xs={12}>
            <TextField
              label="Notes"
              value={form.notes ?? ''}
              onChange={(event) => set('notes', event.target.value)}
              multiline
              minRows={3}
            />
          </Grid>
        </Grid>

        <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 3 }}>
          <Button onClick={() => navigate(editing ? `/assets/${id}` : '/assets')}>Cancel</Button>
          <Button type="submit" variant="contained" disabled={save.isPending}>
            Save
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}

function Field({
  label,
  field,
  form,
  set,
  sm,
  type,
}: {
  label: string;
  field: string;
  form: FormState;
  set: (field: string, value: unknown) => void;
  sm?: number;
  type?: string;
}) {
  return (
    <Grid item xs={12} sm={sm ?? 6}>
      <TextField
        label={label}
        type={type ?? 'text'}
        InputLabelProps={type === 'date' ? { shrink: true } : undefined}
        value={form[field] ?? ''}
        onChange={(event) => set(field, event.target.value)}
      />
    </Grid>
  );
}
