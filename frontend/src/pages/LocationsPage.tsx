import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Chip,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Location, LocationTypeOption, ReferenceEnums } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { locationOptions } from '../components/locationTree';
import { useAuth } from '../auth/AuthContext';

/** Reads better than the stored key, which is all the API deals in. */
const OWNERSHIP_LABELS: Record<string, string> = {
  COMPANY_OWNED: 'Company Owned',
  CUSTOMER_PREMISE: 'Customer Premise',
  VENDOR: 'Vendor',
  OTHER: 'Other',
};

/**
 * Locations are a real hierarchy, so this is a tree rather than a flat list with
 * a parent column bolted on.
 */
export function LocationsPage() {
  const { has } = useAuth();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Partial<Location> | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [searchParams, setSearchParams] = useSearchParams();
  const [error, setError] = useState<string | null>(null);

  const locations = useQuery({ queryKey: ['locations'], queryFn: () => api.get<Location[]>('/api/locations') });
  const enums = useQuery({ queryKey: ['enums'], queryFn: () => api.get<ReferenceEnums>('/api/reference/enums') });
  // Location types are a table now, so new ones can be added without a release.
  const locationTypes = useQuery({
    queryKey: ['location-types'],
    queryFn: () => api.get<LocationTypeOption[]>('/api/locations/types'),
  });
  const [newTypeName, setNewTypeName] = useState('');

  const addType = useMutation({
    mutationFn: (name: string) => api.post<LocationTypeOption>('/api/locations/types', { name }),
    onSuccess: (created) => {
      setNewTypeName('');
      setEditing((current) => (current ? { ...current, locationTypeId: created.id } : current));
      void queryClient.invalidateQueries({ queryKey: ['location-types'] });
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not add the type.'),
  });

  // The nav's plus shortcut lands here with ?new=1; open the dialog once the
  // types have loaded so the form has a sensible default selected.
  useEffect(() => {
    if (searchParams.get('new') !== '1' || !locationTypes.data?.length) return;
    setEditing({
      locationTypeId: locationTypes.data[0].id,
      ownershipType: 'COMPANY_OWNED',
      active: true,
    });
    setSearchParams({}, { replace: true });
  }, [searchParams, locationTypes.data, setSearchParams]);

  const childrenOf = useMemo(() => {
    const map = new Map<number | null, Location[]>();
    for (const location of locations.data ?? []) {
      const key = location.parentLocationId;
      map.set(key, [...(map.get(key) ?? []), location]);
    }
    return map;
  }, [locations.data]);

  const save = useMutation({
    mutationFn: (payload: Partial<Location>) =>
      payload.id
        ? api.put<Location>(`/api/locations/${payload.id}`, payload)
        : api.post<Location>('/api/locations', payload),
    onSuccess: () => {
      setEditing(null);
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['locations'] });
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not save.'),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/locations/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['locations'] }),
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not remove.'),
  });

  /**
   * Choosing a parent for a new location copies down what a sub-location almost
   * always shares with it: the type, who owns it, and where it physically is. A
   * rack in a warehouse is at the warehouse's address, and re-typing that for
   * every rack is how addresses end up inconsistent.
   *
   * Only for a location being created. Re-parenting an existing one leaves its
   * own details alone -- silently overwriting a recorded address because
   * somebody corrected the hierarchy would be a surprising way to lose data.
   */
  function chooseParent(raw: string) {
    const parentId = raw === '' ? null : Number(raw);
    const parent = parentId == null ? null : locations.data?.find((entry) => entry.id === parentId);

    if (!parent || editing?.id) {
      setEditing({ ...editing, parentLocationId: parentId });
      return;
    }
    setEditing({
      ...editing,
      parentLocationId: parentId,
      locationTypeId: parent.locationTypeId,
      ownershipType: parent.ownershipType,
      ownershipOtherDescription: parent.ownershipOtherDescription,
      addressLine1: parent.addressLine1,
      city: parent.city,
      state: parent.state,
      zip: parent.zip,
    });
  }

  function toggle(id: number) {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function renderBranch(parentId: number | null, depth: number) {
    const children = childrenOf.get(parentId) ?? [];
    return children.map((location) => {
      const grandchildren = childrenOf.get(location.id) ?? [];
      const isOpen = expanded.has(location.id);
      return (
        <Box key={location.id}>
          <ListItem
            divider
            sx={{
              pl: 2 + depth * 3,
              // Nesting is shown by indentation alone, which left siblings
              // running together. A rule under each row separates them, and a
              // tint by depth keeps a child visibly subordinate to its parent
              // rather than merely shifted right.
              bgcolor: depth > 0 ? 'action.hover' : undefined,
              borderLeft: depth > 0 ? '3px solid' : undefined,
              borderLeftColor: depth > 0 ? 'divider' : undefined,
            }}
            secondaryAction={
              has('location:write') ? (
                <Stack direction="row" spacing={1}>
                  <Button size="small" onClick={() => setEditing(location)}>
                    Edit
                  </Button>
                  <Button size="small" color="error" onClick={() => remove.mutate(location.id)}>
                    Remove
                  </Button>
                </Stack>
              ) : undefined
            }
          >
            <IconButton
              size="small"
              sx={{ mr: 1, visibility: grandchildren.length ? 'visible' : 'hidden' }}
              onClick={() => toggle(location.id)}
            >
              {isOpen ? <ExpandMoreIcon /> : <ChevronRightIcon />}
            </IconButton>
            <ListItemText
              primary={
                <Stack direction="row" spacing={1} alignItems="center">
                  <span>{location.name}</span>
                  <Chip size="small" variant="outlined" label={location.locationTypeName} />
                  {!location.active && <Chip size="small" color="warning" label="Inactive" />}
                </Stack>
              }
              secondary={[location.addressLine1, location.city, location.state, location.zip]
                .filter(Boolean)
                .join(', ')}
            />
          </ListItem>
          <Collapse in={isOpen} unmountOnExit>
            {renderBranch(location.id, depth + 1)}
          </Collapse>
        </Box>
      );
    });
  }

  return (
    <>
      <PageHeader
        title="Locations"
        subtitle="Sites, warehouses, towers, and anything else that holds an asset."
        actions={
          has('location:write') ? (
            <Button
              variant="contained"
              onClick={() =>
                setEditing({
                  locationTypeId: locationTypes.data?.[0]?.id,
                  ownershipType: 'COMPANY_OWNED',
                  active: true,
                })
              }
            >
              New location
            </Button>
          ) : undefined
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined">
        {locations.data?.length === 0 ? (
          <Typography sx={{ p: 4, textAlign: 'center' }} color="text.secondary">
            No locations yet.
          </Typography>
        ) : (
          <List dense>{renderBranch(null, 0)}</List>
        )}
      </Paper>

      <Dialog open={Boolean(editing)} onClose={() => setEditing(null)} fullWidth maxWidth="sm">
        <DialogTitle>{editing?.id ? 'Edit location' : 'New location'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              label="Name"
              required
              value={editing?.name ?? ''}
              onChange={(event) => setEditing({ ...editing, name: event.target.value })}
            />
            <TextField
              select
              label="Parent location"
              value={editing?.parentLocationId ?? ''}
              onChange={(event) => chooseParent(event.target.value)}
              helperText={
                editing?.id
                  ? undefined
                  : 'A new sub-location starts with its parent\u2019s type, ownership and address. Change anything below that differs.'
              }
            >
              <MenuItem value="">No parent (top level)</MenuItem>
              {locationOptions(locations.data)
                .filter((option) => option.location.id !== editing?.id)
                .map((option) => (
                  <MenuItem
                    key={option.location.id}
                    value={option.location.id}
                    sx={{
                      pl: 2 + option.depth * 2,
                      color: option.depth > 0 ? 'text.secondary' : 'text.primary',
                    }}
                  >
                    {option.depth > 0 && (
                      <Box component="span" sx={{ mr: 1, opacity: 0.6 }}>
                        –
                      </Box>
                    )}
                    {option.location.name}
                  </MenuItem>
                ))}
            </TextField>
            <TextField
              select
              label="Location type"
              value={editing?.locationTypeId ?? ''}
              onChange={(event) => setEditing({ ...editing, locationTypeId: Number(event.target.value) })}
            >
              {(locationTypes.data ?? [])
                .filter((type) => type.active || type.id === editing?.locationTypeId)
                .map((type) => (
                  <MenuItem key={type.id} value={type.id}>
                    {type.name}
                  </MenuItem>
                ))}
            </TextField>

            {/* Adding a type is a row, so it belongs right here rather than on a
                separate admin screen someone has to go find. */}
            <Stack direction="row" spacing={1} alignItems="flex-start">
              <TextField
                size="small"
                label="Add a location type"
                placeholder="e.g. Splice Trailer"
                value={newTypeName}
                onChange={(event) => setNewTypeName(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && newTypeName.trim()) {
                    event.preventDefault();
                    addType.mutate(newTypeName.trim());
                  }
                }}
              />
              <Button
                sx={{ flexShrink: 0 }}
                disabled={!newTypeName.trim() || addType.isPending}
                onClick={() => addType.mutate(newTypeName.trim())}
              >
                Add
              </Button>
            </Stack>

            <TextField
              select
              label="Ownership"
              value={editing?.ownershipType ?? ''}
              onChange={(event) => setEditing({ ...editing, ownershipType: event.target.value })}
            >
              {(enums.data?.ownershipTypes ?? []).map((type) => (
                <MenuItem key={type} value={type}>
                  {OWNERSHIP_LABELS[type] ?? type.replaceAll('_', ' ')}
                </MenuItem>
              ))}
            </TextField>
            {editing?.ownershipType === 'OTHER' && (
              <TextField
                label="What does Other mean here?"
                required
                value={editing?.ownershipOtherDescription ?? ''}
                onChange={(event) =>
                  setEditing({ ...editing, ownershipOtherDescription: event.target.value })
                }
                helperText="e.g. Leased from the tower owner, shared municipal site"
              />
            )}
            <TextField
              label="Address"
              value={editing?.addressLine1 ?? ''}
              onChange={(event) => setEditing({ ...editing, addressLine1: event.target.value })}
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="City"
                value={editing?.city ?? ''}
                onChange={(event) => setEditing({ ...editing, city: event.target.value })}
              />
              <TextField
                label="State"
                value={editing?.state ?? ''}
                onChange={(event) => setEditing({ ...editing, state: event.target.value })}
              />
              <TextField
                label="ZIP"
                value={editing?.zip ?? ''}
                onChange={(event) => setEditing({ ...editing, zip: event.target.value })}
              />
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditing(null)}>Cancel</Button>
          <Button variant="contained" onClick={() => editing && save.mutate(editing)} disabled={save.isPending}>
            Save
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
