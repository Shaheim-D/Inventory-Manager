import { useState } from 'react';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  IconButton,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Category, CoreFieldConfig, CustomFieldDefinition } from '../../api/types';
import { EntityTable } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';

export function CategoriesPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Partial<Category> | null>(null);
  const [fieldsFor, setFieldsFor] = useState<Category | null>(null);
  const [coreFieldsFor, setCoreFieldsFor] = useState<Category | null>(null);
  const [error, setError] = useState<string | null>(null);

  const categories = useQuery({ queryKey: ['categories'], queryFn: () => api.get<Category[]>('/api/categories') });

  const save = useMutation({
    mutationFn: (payload: Partial<Category>) =>
      payload.id
        ? api.put<Category>(`/api/categories/${payload.id}`, payload)
        : api.post<Category>('/api/categories', payload),
    onSuccess: () => {
      setEditing(null);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not save.'),
  });

  return (
    <>
      <PageHeader
        title="Categories & custom fields"
        subtitle="Categories are data, not schema: adding one, or changing what it tracks, never needs a deployment."
        actions={
          <Button variant="contained" onClick={() => setEditing({ serialized: true })}>
            New category
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'Name', render: (category: Category) => category.name, secondary: true },
            { header: 'Description', render: (category: Category) => category.description ?? '—' },
            {
              header: 'Tracking',
              render: (category: Category) => (
                <Chip
                  size="small"
                  variant="outlined"
                  label={category.serialized ? 'One row per unit' : 'Bulk quantity'}
                />
              ),
            },
            {
              header: 'Verification interval',
              render: (category: Category) =>
                category.verificationIntervalDays ? `${category.verificationIntervalDays} days` : 'Off',
            },
          ]}
          rows={categories.data ?? []}
          rowKey={(category) => category.id}
          loading={categories.isLoading}
          cardTitle={(category) => category.name}
          rowActions={(category) => (
            <>
              <Button size="small" onClick={() => setEditing(category)}>
                Edit
              </Button>
              <Button size="small" onClick={() => setCoreFieldsFor(category)}>
                Fields used
              </Button>
              <Button size="small" onClick={() => setFieldsFor(category)}>
                Custom fields
              </Button>
            </>
          )}
        />
      </Paper>

      <Dialog open={Boolean(editing)} onClose={() => setEditing(null)} fullWidth maxWidth="sm">
        <DialogTitle>{editing?.id ? 'Edit category' : 'New category'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Name"
              required
              value={editing?.name ?? ''}
              onChange={(event) => setEditing({ ...editing, name: event.target.value })}
            />
            <TextField
              label="Description"
              value={editing?.description ?? ''}
              onChange={(event) => setEditing({ ...editing, description: event.target.value })}
            />
            <FormControlLabel
              control={
                <Checkbox
                  checked={editing?.serialized ?? true}
                  onChange={(event) => setEditing({ ...editing, serialized: event.target.checked })}
                />
              }
              label="Serialized — each unit received becomes its own asset row"
            />
            <TextField
              label="Verification interval (days)"
              type="number"
              value={editing?.verificationIntervalDays ?? ''}
              onChange={(event) =>
                setEditing({
                  ...editing,
                  verificationIntervalDays: event.target.value === '' ? null : Number(event.target.value),
                })
              }
              helperText="Leave blank to disable staleness checking for this category."
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditing(null)}>Cancel</Button>
          <Button variant="contained" onClick={() => editing && save.mutate(editing)} disabled={save.isPending}>
            Save
          </Button>
        </DialogActions>
      </Dialog>

      {fieldsFor && <CustomFieldsDialog category={fieldsFor} onClose={() => setFieldsFor(null)} />}
      {coreFieldsFor && (
        <CoreFieldsDialog category={coreFieldsFor} onClose={() => setCoreFieldsFor(null)} />
      )}
    </>
  );
}

function CustomFieldsDialog({ category, onClose }: { category: Category; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<Partial<CustomFieldDefinition>>({ fieldType: 'TEXT', sortOrder: 0 });
  // One entry per option rather than one comma-separated string: an option
  // containing a comma is perfectly normal, and splitting on one silently
  // mangles it.
  const [enumOptions, setEnumOptions] = useState<string[]>(['']);
  const [error, setError] = useState<string | null>(null);

  // forAdministration returns every definition, including ones this admin's own
  // permissions would hide on an asset — editing them is what this screen is for.
  const fields = useQuery({
    queryKey: ['custom-fields-admin', category.id],
    queryFn: () =>
      api.get<CustomFieldDefinition[]>(`/api/categories/${category.id}/custom-fields?forAdministration=true`),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['custom-fields-admin', category.id] });
    void queryClient.invalidateQueries({ queryKey: ['custom-fields', category.id] });
  };

  const add = useMutation({
    mutationFn: (payload: Partial<CustomFieldDefinition>) =>
      api.post(`/api/categories/${category.id}/custom-fields`, {
        ...payload,
        enumOptions:
          payload.fieldType === 'ENUM' ? enumOptions.map((o) => o.trim()).filter(Boolean) : null,
      }),
    onSuccess: () => {
      setDraft({ fieldType: 'TEXT', sortOrder: 0 });
      setEnumOptions(['']);
      setError(null);
      invalidate();
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not add the field.'),
  });

  const remove = useMutation({
    mutationFn: (fieldId: number) => api.del(`/api/categories/${category.id}/custom-fields/${fieldId}`),
    onSuccess: invalidate,
  });

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{category.name} — custom fields</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Custom fields are scoped to this category alone. Values are stored in one JSONB column and
          validated against these definitions when an asset is saved.
        </Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <EntityTable
          columns={[
            { header: 'Field', render: (field: CustomFieldDefinition) => field.fieldName },
            { header: 'Type', render: (field: CustomFieldDefinition) => field.fieldType },
            { header: 'Required', render: (field: CustomFieldDefinition) => (field.required ? 'Yes' : 'No') },
            {
              header: 'Options',
              render: (field: CustomFieldDefinition) => field.enumOptions?.join(', ') ?? '—',
            },
          ]}
          rows={fields.data ?? []}
          rowKey={(field) => field.id}
          loading={fields.isLoading}
          emptyMessage="This category has no custom fields yet."
          cardTitle={(field) => field.fieldName}
          rowActions={(field) => (
            <Button size="small" color="error" onClick={() => remove.mutate(field.id)}>
              Remove
            </Button>
          )}
        />

        <Stack spacing={2} sx={{ mt: 3 }}>
          <Typography variant="subtitle2">Add a field</Typography>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField
              label="Field name"
              value={draft.fieldName ?? ''}
              onChange={(event) => setDraft({ ...draft, fieldName: event.target.value })}
            />
            <TextField
              select
              label="Type"
              value={draft.fieldType ?? 'TEXT'}
              onChange={(event) =>
                setDraft({ ...draft, fieldType: event.target.value as CustomFieldDefinition['fieldType'] })
              }
              sx={{ minWidth: 160 }}
            >
              {['TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'ENUM'].map((type) => (
                <MenuItem key={type} value={type}>
                  {type}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Sort order"
              type="number"
              value={draft.sortOrder ?? 0}
              onChange={(event) => setDraft({ ...draft, sortOrder: Number(event.target.value) })}
              sx={{ maxWidth: 140 }}
            />
          </Stack>
          {draft.fieldType === 'ENUM' && (
            <Stack spacing={1}>
              <Typography variant="body2" color="text.secondary">
                Options — one per line
              </Typography>
              {enumOptions.map((option, index) => (
                <Stack key={index} direction="row" spacing={1} alignItems="center">
                  <TextField
                    label={`Option ${index + 1}`}
                    value={option}
                    onChange={(event) =>
                      setEnumOptions((current) =>
                        current.map((entry, i) => (i === index ? event.target.value : entry)),
                      )
                    }
                    onKeyDown={(event) => {
                      // Enter adds the next one, so a list can be typed without reaching for the mouse.
                      if (event.key === 'Enter') {
                        event.preventDefault();
                        setEnumOptions((current) => [...current, '']);
                      }
                    }}
                  />
                  <IconButton
                    aria-label={`Remove option ${index + 1}`}
                    disabled={enumOptions.length === 1}
                    onClick={() => setEnumOptions((current) => current.filter((_, i) => i !== index))}
                  >
                    <DeleteOutlineIcon />
                  </IconButton>
                </Stack>
              ))}
              <Box>
                <Button size="small" onClick={() => setEnumOptions((current) => [...current, ''])}>
                  Add option
                </Button>
              </Box>
            </Stack>
          )}
          <FormControlLabel
            control={
              <Checkbox
                checked={draft.required ?? false}
                onChange={(event) => setDraft({ ...draft, required: event.target.checked })}
              />
            }
            label="Required"
          />
          <Stack direction="row">
            <Button variant="outlined" onClick={() => add.mutate(draft)} disabled={!draft.fieldName}>
              Add field
            </Button>
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Done</Button>
      </DialogActions>
    </Dialog>
  );
}


/**
 * Which core columns this category uses. Ticking nothing means every field is
 * offered, which keeps an unconfigured category behaving exactly as it did
 * before this existed — and means clearing the list can never leave somebody
 * with an empty form.
 *
 * This is not permission. A field switched off here is off for everybody; who
 * may see a field is Field Visibility Rules, and that one is a security
 * boundary.
 */
function CoreFieldsDialog({ category, onClose }: { category: Category; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const config = useQuery({
    queryKey: ['core-fields', category.id],
    queryFn: () => api.get<CoreFieldConfig>(`/api/categories/${category.id}/core-fields`),
  });

  const chosen = selected ?? config.data?.applicable ?? [];

  const save = useMutation({
    mutationFn: () =>
      api.put(`/api/categories/${category.id}/core-fields`, { coreFieldNames: chosen }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['core-fields', category.id] });
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      onClose();
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not save.'),
  });

  function toggle(field: string, on: boolean) {
    setSelected(on ? [...chosen, field] : chosen.filter((f) => f !== field));
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{category.name} — fields used</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Tick the fields that mean something for a {category.name.toLowerCase()}. A vehicle has no
          firmware version; a spool of fibre has no hostname. Name, category, location, lifecycle
          state, and assignee are always present and are not listed.
        </Typography>

        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <FormGroup>
          {(config.data?.configurable ?? []).map((field) => (
            <FormControlLabel
              key={field}
              control={
                <Checkbox
                  checked={chosen.includes(field)}
                  onChange={(event) => toggle(field, event.target.checked)}
                />
              }
              label={config.data?.labels[field] ?? field}
            />
          ))}
        </FormGroup>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}
