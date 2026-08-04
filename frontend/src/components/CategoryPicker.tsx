import { useMemo, useState } from 'react';
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
  id: number;
  label: string;
  /** Set on the synthetic "Add …" row, carrying what was typed. */
  create?: string;
}

/** The synthetic row's id. Negative so it can never collide with a real one. */
const CREATE_ID = -1;

const filter = createFilterOptions<Option>();

/**
 * Picking a category, with the option of making one on the spot.
 *
 * <p>The category something belongs to is often the reason somebody is on the
 * screen at all — they have just started stocking a kind of hardware nothing
 * else covers. Sending them to Categories & Fields to create it and back again
 * loses whatever they had half-typed, so typing a name that does not exist
 * offers to create it here instead.
 *
 * <p>Creating asks one question: whether each unit is tracked separately or the
 * category is bulk stock. That is not a detail worth defaulting silently — it
 * decides whether receiving ten of something makes ten asset rows or one row of
 * ten, and it is the difference between an SFP module and a router. Everything
 * else has a sensible default and is editable afterwards.
 *
 * <p>"No category" is a genuinely empty field with a placeholder, never a
 * selected option reading "Any category". A placeholder can be typed over and
 * the clear button empties it; an option that means nothing cannot be deleted,
 * which is the difference between a field that behaves and one that argues.
 *
 * <p>Creating is offered only to somebody who holds `category:manage` — some of
 * these screens are readable far more widely, and a control that would be
 * refused server-side is better absent than present.
 */
export function CategoryPicker({
  value,
  onChange,
  label = 'Category',
  helperText,
  required = false,
  emptyLabel = 'Any category',
  disabled = false,
}: {
  value: number | null;
  onChange: (categoryId: number | null) => void;
  label?: string;
  helperText?: string;
  /** Refuses to look empty: the label gets its asterisk and nothing is pre-picked. */
  required?: boolean;
  /** The placeholder shown when nothing is picked. */
  emptyLabel?: string;
  disabled?: boolean;
}) {
  const { has } = useAuth();
  const canCreate = has('category:manage');

  // The same key every screen uses, so this shares one request with whatever
  // is already on the page rather than fetching the list twice. Left possibly
  // undefined rather than defaulted to [] here: a fresh empty array every
  // render would defeat the memoisation below, which is load-bearing.
  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
  }).data;

  // A category created here is usable before the list refetches, so the field
  // does not go blank for the moment in between.
  const [created, setCreated] = useState<Category[]>([]);
  const [drafting, setDrafting] = useState<string | null>(null);

  const known = useMemo(() => {
    const listed = categories ?? [];
    return [...listed, ...created.filter((extra) => !listed.some((c) => c.id === extra.id))];
  }, [categories, created]);

  // Memoised so the option objects keep their identity between renders. Without
  // that, every keystroke hands Autocomplete a value it considers new, and it
  // answers by resetting the text you are in the middle of typing.
  const options = useMemo<Option[]>(
    () => known.map((category) => ({ id: category.id, label: category.name })),
    [known],
  );

  const selected = useMemo(
    () => options.find((option) => option.id === value) ?? null,
    [options, value],
  );

  return (
    <>
      <Autocomplete
        options={options}
        value={selected}
        disabled={disabled}
        onChange={(_event, option) => {
          if (option?.create) setDrafting(option.create);
          else onChange(option?.id ?? null);
        }}
        getOptionLabel={(option) => option.label}
        isOptionEqualToValue={(option, current) => option.id === current.id}
        filterOptions={(available, params) => {
          const filtered = filter(available, params);
          const typed = params.inputValue.trim();
          const exists = known.some((category) => category.name.toLowerCase() === typed.toLowerCase());
          if (canCreate && typed && !exists) {
            filtered.push({ id: CREATE_ID, label: `Add “${typed}”`, create: typed });
          }
          return filtered;
        }}
        renderOption={(props, option) => {
          const { key, ...rest } = props as typeof props & { key: string };
          return (
            <li key={key} {...rest}>
              {option.create ? <strong>{option.label}</strong> : option.label}
            </li>
          );
        }}
        renderInput={(params) => (
          <TextField
            {...params}
            label={label}
            required={required}
            // The placeholder, not a fake option: an empty field says "nothing
            // chosen" and can be typed straight into.
            placeholder={selected ? undefined : emptyLabel}
            helperText={
              helperText ??
              (canCreate ? 'Type a name that does not exist yet to create the category here.' : undefined)
            }
          />
        )}
        handleHomeEndKeys
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
