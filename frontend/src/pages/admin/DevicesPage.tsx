import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  InputAdornment,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Category, DeviceModel } from '../../api/types';
import { CategoryPicker } from '../../components/CategoryPicker';
import { EntityTable } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';

/**
 * The Devices catalog. Keeping a short list of known manufacturer / model /
 * device-role combinations is what stops the same router being entered as
 * "Cisco", "cisco", and "Cicso" across three assets — and it means creating an
 * asset is mostly picking, not typing.
 */
export function DevicesPage() {
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  // The nav's plus shortcut opens the dialog straight away.
  const [editing, setEditing] = useState<Partial<DeviceModel> | null>(
    searchParams.get('new') === '1' ? { active: true } : null,
  );
  const [error, setError] = useState<string | null>(null);
  const [categoryFilter, setCategoryFilter] = useState('');

  const devices = useQuery({
    queryKey: ['device-models'],
    queryFn: () => api.get<DeviceModel[]>('/api/device-models'),
  });
  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
  });

  const save = useMutation({
    mutationFn: (payload: Partial<DeviceModel>) =>
      payload.id
        ? api.put<DeviceModel>(`/api/device-models/${payload.id}`, payload)
        : api.post<DeviceModel>('/api/device-models', payload),
    onSuccess: () => {
      setEditing(null);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['device-models'] });
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not save.'),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/device-models/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['device-models'] }),
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not remove.'),
  });

  const rows = (devices.data ?? []).filter(
    (device) => !categoryFilter || String(device.categoryId ?? '') === categoryFilter,
  );

  return (
    <>
      <PageHeader
        title="Devices"
        subtitle="Known manufacturer, model, and device-role combinations, offered when creating an asset."
        actions={
          <Button variant="contained" onClick={() => setEditing({ active: true })}>
            New device
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <TextField
          select
          label="Category"
          value={categoryFilter}
          onChange={(event) => setCategoryFilter(event.target.value)}
          sx={{ maxWidth: 320 }}
        >
          <MenuItem value="">All devices</MenuItem>
          {(categories.data ?? []).map((category) => (
            <MenuItem key={category.id} value={String(category.id)}>
              {category.name}
            </MenuItem>
          ))}
        </TextField>
      </Paper>

      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'Manufacturer', render: (d: DeviceModel) => d.manufacturer },
            { header: 'Model', render: (d: DeviceModel) => d.model },
            { header: 'Device role', render: (d: DeviceModel) => d.deviceRole ?? '—' },
            {
              header: 'Retail price',
              align: 'right',
              render: (d: DeviceModel) =>
                d.defaultPrice == null
                  ? '—'
                  : Number(d.defaultPrice).toLocaleString(undefined, { style: 'currency', currency: 'USD' }),
            },
            {
              header: 'Category',
              render: (d: DeviceModel) =>
                d.categoryName ? (
                  <Chip size="small" variant="outlined" label={d.categoryName} />
                ) : (
                  <Chip size="small" label="Any category" />
                ),
            },
            {
              header: 'Offered',
              render: (d: DeviceModel) => (d.active ? 'Yes' : 'Retired'),
            },
            { header: 'Notes', secondary: true, render: (d: DeviceModel) => d.notes ?? '—' },
          ]}
          rows={rows}
          rowKey={(d) => d.id}
          loading={devices.isLoading}
          emptyMessage="No devices in the catalog yet. Add the models you stock and they will be offered when creating assets."
          cardTitle={(d) => `${d.manufacturer} ${d.model}`}
          rowActions={(d) => (
            <>
              <Button size="small" onClick={() => setEditing(d)}>
                Edit
              </Button>
              <Button size="small" color="error" onClick={() => remove.mutate(d.id)}>
                Remove
              </Button>
            </>
          )}
        />
      </Paper>

      <Dialog
        open={Boolean(editing)}
        onClose={() => {
          setEditing(null);
          if (searchParams.get('new')) setSearchParams({}, { replace: true });
        }}
        fullWidth
        maxWidth="sm"
      >
        <DialogTitle>{editing?.id ? 'Edit device' : 'New device'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Manufacturer"
              required
              value={editing?.manufacturer ?? ''}
              onChange={(event) => setEditing({ ...editing, manufacturer: event.target.value })}
            />
            <TextField
              label="Model"
              required
              value={editing?.model ?? ''}
              onChange={(event) => setEditing({ ...editing, model: event.target.value })}
            />
            <TextField
              label="Device role"
              value={editing?.deviceRole ?? ''}
              onChange={(event) => setEditing({ ...editing, deviceRole: event.target.value })}
              helperText="Optional — pre-filled onto the asset, e.g. Core Router, Edge Switch"
            />
            <TextField
              label="Retail price"
              value={editing?.defaultPrice ?? ''}
              onChange={(event) =>
                setEditing({
                  ...editing,
                  defaultPrice: event.target.value === '' ? null : Number(event.target.value.replace(/[^0-9.]/g, '')),
                })
              }
              inputProps={{ inputMode: 'decimal' }}
              InputProps={{ startAdornment: <InputAdornment position="start">$</InputAdornment> }}
              helperText="Copied onto a new asset as a starting point, and editable there."
            />
            {/*
              Creatable, because the reason somebody is adding a device is
              often that they have started stocking a kind of hardware nothing
              covers yet. Leaving the page to make the category and coming back
              would lose what they had already typed here.
            */}
            <CategoryPicker
              value={editing?.categoryId ?? null}
              onChange={(categoryId) => setEditing({ ...editing, categoryId })}
              helperText="Leave as Any category to offer this device everywhere. Type a name that does not exist yet to create the category here."
            />
            <TextField
              label="Notes"
              multiline
              minRows={2}
              value={editing?.notes ?? ''}
              onChange={(event) => setEditing({ ...editing, notes: event.target.value })}
            />
            <FormControlLabel
              control={
                <Checkbox
                  checked={editing?.active ?? true}
                  onChange={(event) => setEditing({ ...editing, active: event.target.checked })}
                />
              }
              label="Offer this device when creating assets"
            />
            <Typography variant="caption" color="text.secondary">
              Un-ticking keeps existing assets untouched — they copied these values when they were
              created — and simply stops offering it for new ones.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditing(null)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!editing?.manufacturer || !editing?.model || save.isPending}
            onClick={() => editing && save.mutate(editing)}
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
