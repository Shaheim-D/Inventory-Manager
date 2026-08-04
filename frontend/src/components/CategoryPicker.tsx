import { useState } from 'react';
import {
  Alert,
  Autocomplete,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  TextField,
  Typography,
  createFilterOptions,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Category } from '../api/types';
import { useAuth } from '../auth/AuthContext';

interface Option {
  key: string;
  id: number | null;
  label: string;
  /** Set on the synthetic "Add …" row, carrying what was typed. */
  create?: string;
}

const filter = createFilterOptions<Option>();

/**
 * Picking a category, with the option of making one on the spot.
 *
 * <p>The category a device belongs to is often the reason somebody is on this
 * screen at all — they have just started stocking a kind of hardware nothing
 * else covers. Sending them to Categories & Fields to create it and back again
 * loses whatever they had half-typed, so typing a name that does not exist
 * offers to create it here instead.
 *
 * <p>It asks one question when it does: whether each unit is tracked separately
 * or the category is bulk stock. That is not a detail worth defaulting
 * silently — it decides whether receiving ten of something makes ten asset rows
 * or one row of ten, and it is the difference between an SFP module and a
 * router. Everything else a category can carry has a sensible default and is
 * editable afterwards; that one is worth a click.
 *
 * <p>Creating is offered only to somebody who holds `category:manage`. This
 * screen is readable by anyone who can read assets, and the button that would
 * fail server-side is better absent than present.
 */
export function CategoryPicker({
  value,
  onChange,
  label = 'Category',
  helperText,
  anyLabel = 'Any category',
}: {
  value: number | null;
  onChange: (categoryId: number | null) => void;
  label?: string;
  helperText?: string;
  /** What the "no particular category" row is called. */
  anyLabel?: string;
}) {
  const { has } = useAuth();
  const canCreate = has('category:manage');

  // The same key every screen uses, so this shares one request with whatever
  // is already on the page rather than fetching the list twice.
  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
  }).data ?? [];
  // A category created here is usable before the list refetches, so the field
  // does not go blank for the moment in between.
  const [created, setCreated] = useState<Category[]>([]);
  const [drafting, setDrafting] = useState<string | null>(null);

  const known = [...categories, ...created.filter((c) => !categories.some((k) => k.id === c.id))];

  const options: Option[] = [
    { key: 'any', id: null, label: anyLabel },
    ...known.map((category) => ({
      key: String(category.id),
      id: category.id,
      label: category.name,
    })),
  ];

  const selected = options.find((option) => option.id === value) ?? options[0];

  return (
    <>
      <Autocomplete
        options={options}
        value={selected}
        onChange={(_event, option) => {
          if (option && option.create) setDrafting(option.create);
          else onChange(option?.id ?? null);
        }}
        getOptionLabel={(option) => option.label}
        isOptionEqualToValue={(option, current) => option.key === current.key}
        filterOptions={(available, params) => {
          const filtered = filter(available, params);
          const typed = params.inputValue.trim();
          const exists = known.some((category) => category.name.toLowerCase() === typed.toLowerCase());
          if (canCreate && typed && !exists) {
            filtered.push({ key: 'create', id: null, label: `Add “${typed}”`, create: typed });
          }
          return filtered;
        }}
        renderOption={(props, option) => (
          <li {...props} key={option.key}>
            {option.create ? <strong>{option.label}</strong> : option.label}
          </li>
        )}
        renderInput={(params) => (
          <TextField
            {...params}
            label={label}
            helperText={
              helperText ??
              (canCreate
                ? 'Type a name that does not exist yet to create the category here.'
                : undefined)
            }
          />
        )}
        // Freshly typed text is not itself a value -- only the "Add …" row is,
        // so a half-typed name can never be saved as a category by accident.
        selectOnFocus
        handleHomeEndKeys
        clearOnBlur
      />

      {drafting !== null && (
        <NewCategoryDialog
          initialName={drafting}
          onClose={() => setDrafting(null)}
          onCreated={(category) => {
            setCreated((current) => [...current, category]);
            onChange(category.id);
            setDrafting(null);
          }}
        />
      )}
    </>
  );
}

function NewCategoryDialog({
  initialName,
  onClose,
  onCreated,
}: {
  initialName: string;
  onClose: () => void;
  onCreated: (category: Category) => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState('');
  const [serialized, setSerialized] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: () =>
      api.post<Category>('/api/categories', {
        name: name.trim(),
        description: description.trim() || null,
        serialized,
      }),
    onSuccess: (category) => {
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      onCreated(category);
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not create that category.'),
  });

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>New category</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField
            label="Name"
            required
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
          <TextField
            label="Description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
          <FormControlLabel
            control={
              <Checkbox checked={serialized} onChange={(event) => setSerialized(event.target.checked)} />
            }
            label="Serialized — each unit received becomes its own asset row"
          />
          <Typography variant="caption" color="text.secondary">
            It arrives with the standard lifecycle and a starting field set. Fields, warranty
            thresholds and the verification interval are under Settings → Categories &amp; Fields
            whenever you want them.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!name.trim() || create.isPending}
          onClick={() => create.mutate()}
        >
          Create
        </Button>
      </DialogActions>
    </Dialog>
  );
}
