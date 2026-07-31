import { useMemo, useState } from 'react';
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
import type { Location } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { useAuth } from '../auth/AuthContext';

interface EnumOptions {
  locationTypes: string[];
  ownershipTypes: string[];
}

/**
 * Locations are a real hierarchy, so this is a tree rather than a flat list with
 * a parent column bolted on.
 */
export function LocationsPage() {
  const { has } = useAuth();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Partial<Location> | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const locations = useQuery({ queryKey: ['locations'], queryFn: () => api.get<Location[]>('/api/locations') });
  const enums = useQuery({ queryKey: ['enums'], queryFn: () => api.get<EnumOptions>('/api/reference/enums') });

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
            sx={{ pl: 2 + depth * 3 }}
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
                  <Chip size="small" variant="outlined" label={location.locationType.replaceAll('_', ' ')} />
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
            <Button variant="contained" onClick={() => setEditing({ locationType: 'WAREHOUSE', ownershipType: 'ISP_OWNED', active: true })}>
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
              onChange={(event) =>
                setEditing({
                  ...editing,
                  parentLocationId: event.target.value === '' ? null : Number(event.target.value),
                })
              }
            >
              <MenuItem value="">No parent (top level)</MenuItem>
              {(locations.data ?? [])
                .filter((entry) => entry.id !== editing?.id)
                .map((entry) => (
                  <MenuItem key={entry.id} value={entry.id}>
                    {entry.name}
                  </MenuItem>
                ))}
            </TextField>
            <TextField
              select
              label="Location type"
              value={editing?.locationType ?? ''}
              onChange={(event) => setEditing({ ...editing, locationType: event.target.value })}
            >
              {(enums.data?.locationTypes ?? []).map((type) => (
                <MenuItem key={type} value={type}>
                  {type.replaceAll('_', ' ')}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              select
              label="Ownership"
              value={editing?.ownershipType ?? ''}
              onChange={(event) => setEditing({ ...editing, ownershipType: event.target.value })}
            >
              {(enums.data?.ownershipTypes ?? []).map((type) => (
                <MenuItem key={type} value={type}>
                  {type.replaceAll('_', ' ')}
                </MenuItem>
              ))}
            </TextField>
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
