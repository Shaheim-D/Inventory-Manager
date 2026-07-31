import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Autocomplete,
  Box,
  Chip,
  Button,
  CircularProgress,
  Divider,
  Grid,
  InputAdornment,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type {
  Asset,
  AssignableUser,
  Category,
  CoreFieldConfig,
  CustomFieldDefinition,
  DeviceModel,
  Location,
} from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { DynamicFieldForm } from '../components/DynamicFieldForm';

type FormState = Record<string, unknown>;

/** A fixed ladder. Consistent wording beats free text as soon as anyone wants to
 *  filter or report on condition. */
const CONDITIONS = ['New', 'Excellent', 'Good', 'Fair', 'Poor', 'Needs Repair', 'Beyond Repair'];

const WARRANTY_TERMS = [
  { months: 3, label: '3 months' },
  { months: 6, label: '6 months' },
  { months: 12, label: '1 year' },
  { months: 24, label: '2 years' },
  { months: 36, label: '3 years' },
  { months: 60, label: '5 years' },
  { months: 84, label: '7 years' },
  { months: 120, label: '10 years' },
];

export function AssetFormPage() {
  const { id } = useParams<{ id: string }>();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [form, setForm] = useState<FormState>({ assigneeType: 'NONE', quantity: 1 });
  const [customFields, setCustomFields] = useState<Record<string, unknown>>({});
  const [subcategoryIds, setSubcategoryIds] = useState<number[]>([]);
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
      warrantyTermMonths: asset.warrantyTermMonths ?? '',
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
    setSubcategoryIds(asset.subcategories.map((c) => c.id));
  }, [existing.data]);

  const categoryId = form.categoryId ? Number(form.categoryId) : undefined;
  const category = categories.data?.find((entry) => entry.id === categoryId);

  // Which core columns this category actually uses. A Vehicle asks for make and
  // model; it does not ask for a firmware version, because that means nothing
  // for a truck. Separate from field visibility, which is about permission.
  const coreFields = useQuery({
    queryKey: ['core-fields', categoryId],
    queryFn: () => api.get<CoreFieldConfig>(`/api/categories/${categoryId}/core-fields`),
    enabled: Boolean(categoryId),
  });

  const uses = (field: string) =>
    !coreFields.data || coreFields.data.applicable.includes(field);

  // A vehicle has a Make, not a Manufacturer. Wording only; same column.
  const label = (field: string, fallback: string) => coreFields.data?.labels[field] ?? fallback;

  // Devices the catalog offers for this category, used to pre-fill three columns.
  const deviceModels = useQuery({
    queryKey: ['device-models', categoryId],
    queryFn: () => api.get<DeviceModel[]>(`/api/device-models?categoryId=${categoryId}`),
    enabled: Boolean(categoryId),
  });

  const definitions = useQuery({
    queryKey: ['custom-fields', categoryId],
    queryFn: () => api.get<CustomFieldDefinition[]>(`/api/categories/${categoryId}/custom-fields`),
    enabled: Boolean(categoryId),
  });

  const assignableUsers = useQuery({
    queryKey: ['assignable-users'],
    queryFn: () => api.get<AssignableUser[]>('/api/users/assignable'),
  });

  // What the combo shows: the matching user when one is recorded, else raw text.
  // USER is a refinement of EMPLOYEE, so the selector shows them as one choice.
  const assignmentMode =
    form.assigneeType === 'USER' ? 'EMPLOYEE' : ((form.assigneeType as string) ?? 'NONE');

  const selectedAssignee = useMemo<AssignableUser | string>(() => {
    if (form.assigneeUserId) {
      const match = (assignableUsers.data ?? []).find((u) => u.id === Number(form.assigneeUserId));
      if (match) return match;
    }
    return (form.assigneeText as string) ?? '';
  }, [form.assigneeUserId, form.assigneeText, assignableUsers.data]);

  const derivedExpiration = useMemo(() => {
    const start = form.warrantyStart as string | undefined;
    const months = Number(form.warrantyTermMonths ?? 0);
    if (!start || !months) return null;
    const date = new Date(start + 'T00:00:00');
    date.setMonth(date.getMonth() + months);
    return date.toISOString().slice(0, 10);
  }, [form.warrantyStart, form.warrantyTermMonths]);

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

    const payload: Record<string, unknown> = { customFields, subcategoryIds };
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
    payload.warrantyTermMonths =
      form.warrantyTermMonths === '' || form.warrantyTermMonths == null
        ? null
        : Number(form.warrantyTermMonths);
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

          <Grid item xs={12}>
            {/*
              The first category picked is the primary one and the only thing
              that decides which fields, custom fields, and lifecycle apply.
              These are filing labels, nothing more.
            */}
            <Autocomplete
              multiple
              options={(categories.data ?? []).filter((c) => c.id !== categoryId)}
              getOptionLabel={(c) => c.name}
              value={(categories.data ?? []).filter((c) => subcategoryIds.includes(c.id))}
              onChange={(_, next) => setSubcategoryIds(next.map((c) => c.id))}
              renderTags={(value, getTagProps) =>
                value.map((option, index) => (
                  <Chip
                    {...getTagProps({ index })}
                    key={option.id}
                    size="small"
                    label={option.name}
                    variant="outlined"
                    sx={{
                      // The remove control only appears on hover, so a row of
                      // chips reads cleanly until you reach for one.
                      '& .MuiChip-deleteIcon': { opacity: 0, transition: 'opacity 120ms' },
                      '&:hover .MuiChip-deleteIcon': { opacity: 1 },
                    }}
                  />
                ))
              }
              renderInput={(params) => (
                <TextField
                  {...params}
                  label="Sub-category"
                  placeholder={subcategoryIds.length ? '' : 'Optional — extra groupings for searching'}
                  helperText="Organisation only. The primary category above decides which fields you get."
                />
              )}
            />
          </Grid>

          {(deviceModels.data ?? []).length > 0 && (
            <Grid item xs={12}>
              <Autocomplete
                options={deviceModels.data ?? []}
                getOptionLabel={(d) => `${d.manufacturer} ${d.model}${d.deviceRole ? ' · ' + d.deviceRole : ''}`}
                onChange={(_, device) => {
                  if (!device) return;
                  // Copies the values onto the asset rather than referencing the
                  // catalog row, so retiring a device later never rewrites history.
                  set('manufacturer', device.manufacturer);
                  set('model', device.model);
                  if (device.deviceRole) set('deviceRole', device.deviceRole);
                  // A starting point, not a price list — still editable below.
                  if (device.defaultPrice != null) set('purchasePrice', device.defaultPrice);
                }}
                renderInput={(params) => (
                  <TextField
                    {...params}
                    label="Start from a known device"
                    helperText="Optional — fills in manufacturer, model, and device role. Everything stays editable."
                  />
                )}
              />
            </Grid>
          )}

          <Field label="Name" field="name" form={form} set={set} sm={6} />
          {uses('asset_tag') && <Field label="Asset tag" field="assetTag" form={form} set={set} sm={6} />}
          {uses('serial_number') && <Field label="Serial number" field="serialNumber" form={form} set={set} sm={6} />}
          {uses('hostname') && <Field label="Hostname" field="hostname" form={form} set={set} sm={6} />}
          {uses('management_ip') && <Field label="Management IP" field="managementIp" form={form} set={set} sm={6} />}
          {uses('manufacturer') && (
            <Field label={label('manufacturer', 'Manufacturer')} field="manufacturer" form={form} set={set} sm={6} />
          )}
          {uses('model') && <Field label={label('model', 'Model')} field="model" form={form} set={set} sm={6} />}
          {uses('firmware_version') && <Field label="Firmware version" field="firmwareVersion" form={form} set={set} sm={6} />}
          {uses('software_version') && <Field label="Software version" field="softwareVersion" form={form} set={set} sm={6} />}
          {uses('device_role') && <Field label="Device role" field="deviceRole" form={form} set={set} sm={6} />}
          {uses('condition') && (
          <Grid item xs={12} sm={6}>
            <TextField
              select
              label="Condition"
              value={form.condition ?? ''}
              onChange={(event) => set('condition', event.target.value)}
            >
              <MenuItem value="">
                <em>Not recorded</em>
              </MenuItem>
              {CONDITIONS.map((condition) => (
                <MenuItem key={condition} value={condition}>
                  {condition}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          )}

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

          {uses('purchase_date') && <Field label="Purchase date" field="purchaseDate" form={form} set={set} sm={6} type="date" />}
          {uses('vendor') && <Field label="Vendor" field="vendor" form={form} set={set} sm={6} />}
          {/* Two independent reasons a field may be absent: the category does not
              use it, or this viewer may not see it. Both have to pass. */}
          {uses('purchase_price') && !hiddenCore.has('purchase_price') && (
            <Grid item xs={12} sm={6}>
              {/* Deliberately not type="number": the spinner arrows are useless
                  for a price somebody reads off an invoice, and a stray scroll
                  over the field silently changes it. */}
              <TextField
                label="Purchase price"
                value={form.purchasePrice ?? ''}
                onChange={(event) => set('purchasePrice', event.target.value.replace(/[^0-9.]/g, ''))}
                inputProps={{ inputMode: 'decimal' }}
                InputProps={{ startAdornment: <InputAdornment position="start">$</InputAdornment> }}
              />
            </Grid>
          )}
          {uses('invoice_number') && !hiddenCore.has('invoice_number') && (
            <Field label="Invoice number" field="invoiceNumber" form={form} set={set} sm={6} />
          )}
          {uses('purchase_link') && !hiddenCore.has('purchase_link') && (
            <Field label="Purchase link" field="purchaseLink" form={form} set={set} sm={6} />
          )}
          {uses('warranty_start') && (
            <>
              <Field label="Warranty start" field="warrantyStart" form={form} set={set} sm={4} type="date" />
              <Grid item xs={12} sm={4}>
                {/* Nobody is told a warranty expires on a date; they are told it
                    runs two years. The end date is derived from this. */}
                <TextField
                  select
                  label="Warranty length"
                  value={form.warrantyTermMonths ?? ''}
                  onChange={(event) => set('warrantyTermMonths', event.target.value)}
                >
                  <MenuItem value="">
                    <em>Not recorded</em>
                  </MenuItem>
                  {WARRANTY_TERMS.map((term) => (
                    <MenuItem key={term.months} value={term.months}>
                      {term.label}
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  label="Expires"
                  value={derivedExpiration ?? ''}
                  disabled
                  helperText={derivedExpiration ? 'Calculated from start and length' : 'Set a start date and length'}
                />
              </Grid>
            </>
          )}

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
                  label="Assignment"
                  value={assignmentMode}
                  onChange={(event) => {
                    const mode = event.target.value;
                    set('assigneeText', '');
                    set('assigneeUserId', '');
                    // EMPLOYEE settles into USER if a known account is picked below.
                    set('assigneeType', mode);
                  }}
                >
                  <MenuItem value="NONE">Unassigned</MenuItem>
                  <MenuItem value="EMPLOYEE">Assigned to — employee</MenuItem>
                  <MenuItem value="CUSTOMER">Assigned to — customer</MenuItem>
                </TextField>
              </Grid>

              {assignmentMode === 'EMPLOYEE' && (
                <Grid item xs={12} sm={6}>
                  {/*
                    Picking a known account records the user id; typing anything
                    else records the name. Whoever hands over a laptop should not
                    have to classify the recipient first.
                  */}
                  <Autocomplete
                    freeSolo
                    options={assignableUsers.data ?? []}
                    getOptionLabel={(option) => (typeof option === 'string' ? option : option.username)}
                    isOptionEqualToValue={(option, value) =>
                      typeof option !== 'string' && typeof value !== 'string' && option.id === value.id
                    }
                    value={selectedAssignee}
                    onChange={(_, next) => {
                      if (next && typeof next !== 'string') {
                        set('assigneeType', 'USER');
                        set('assigneeUserId', next.id);
                        set('assigneeText', '');
                      } else {
                        set('assigneeType', 'EMPLOYEE');
                        set('assigneeUserId', '');
                        set('assigneeText', next ?? '');
                      }
                    }}
                    onInputChange={(_, text, reason) => {
                      if (reason !== 'input') return;
                      set('assigneeType', 'EMPLOYEE');
                      set('assigneeUserId', '');
                      set('assigneeText', text);
                    }}
                    renderInput={(params) => (
                      <TextField {...params} label="Employee" helperText="Pick a user, or type any name" />
                    )}
                  />
                </Grid>
              )}

              {assignmentMode === 'CUSTOMER' && (
                <Field label="Customer" field="assigneeText" form={form} set={set} sm={6} />
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
