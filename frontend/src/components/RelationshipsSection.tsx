import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Asset, AssetRelationship, Page, RelationshipType } from '../api/types';
import { EntityTable } from './EntityTable';
import { useAuth } from '../auth/AuthContext';

/**
 * A colour per kind of link, chosen so the colour carries the same meaning it
 * does elsewhere in the app rather than being decoration.
 *
 * Both wordings of a link map to the same colour — "Installed In" on the SFP
 * and "Contains" on the switch are one fact, and it would read as two if the
 * chips disagreed.
 */
const RELATIONSHIP_COLOR: Record<
  string,
  'default' | 'primary' | 'info' | 'success' | 'warning' | 'error'
> = {
  // Physically part of something: the strongest form of "these belong together".
  'Installed In': 'primary',
  Contains: 'primary',
  'Part Of': 'primary',
  Comprises: 'primary',
  // In a rack or enclosure — where it lives, not what it is.
  'Mounted In': 'info',
  Houses: 'info',
  // A live signal path.
  'Connected To': 'success',
  // Power: the thing whose loss takes the other down with it.
  'Powered By': 'warning',
  Powers: 'warning',
  // Standby stock. Reassuring rather than urgent.
  'Spare For': 'default',
  'Has spare': 'default',
  // Something failed. Red, for the same reason Broken is red on a lifecycle chip.
  'Replaced By': 'error',
  Replaced: 'error',
};

/**
 * Links from one asset to another — an SFP installed in a switch, a spare held
 * against a particular router.
 *
 * A link is stored once and shown from both ends, so this list mixes links
 * entered here with links entered on the other asset. Both read forwards from
 * where you are standing: the server words the inverse, and this component
 * never has to know which end the row was created from.
 */
export function RelationshipsSection({ assetId }: { assetId: string }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { has } = useAuth();
  const canManage = has('relationship:manage');

  const [adding, setAdding] = useState(false);
  const [typeId, setTypeId] = useState('');
  const [target, setTarget] = useState<Asset | null>(null);
  const [search, setSearch] = useState('');
  const [error, setError] = useState<string | null>(null);

  const links = useQuery({
    queryKey: ['asset-relationships', assetId],
    queryFn: () => api.get<AssetRelationship[]>(`/api/assets/${assetId}/relationships`),
  });

  const types = useQuery({
    queryKey: ['relationship-types'],
    queryFn: () => api.get<RelationshipType[]>('/api/relationship-types'),
    enabled: canManage,
  });

  // Searching rather than listing every asset: the picker has to stay usable
  // once there are thousands, and the search endpoint already exists.
  const candidates = useQuery({
    queryKey: ['asset-search', search],
    queryFn: () => api.get<Page<Asset>>(`/api/assets?q=${encodeURIComponent(search)}&size=20`),
    enabled: adding && search.trim().length >= 2,
  });

  const create = useMutation({
    mutationFn: () =>
      api.post(`/api/assets/${assetId}/relationships`, {
        targetAssetId: target?.id,
        relationshipTypeId: Number(typeId),
      }),
    onSuccess: () => {
      close();
      void queryClient.invalidateQueries({ queryKey: ['asset-relationships', assetId] });
      void queryClient.invalidateQueries({ queryKey: ['asset-audit', assetId] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not link these assets.'),
  });

  const remove = useMutation({
    mutationFn: (linkId: number) => api.del(`/api/assets/${assetId}/relationships/${linkId}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['asset-relationships', assetId] });
      void queryClient.invalidateQueries({ queryKey: ['asset-audit', assetId] });
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not remove the link.'),
  });

  const close = () => {
    setAdding(false);
    setTypeId('');
    setTarget(null);
    setSearch('');
    setError(null);
  };

  return (
    <>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {canManage && (
        <Stack direction="row" justifyContent="flex-end" sx={{ mb: 2 }}>
          <Button variant="contained" onClick={() => setAdding(true)}>
            Link an asset
          </Button>
        </Stack>
      )}

      <Paper variant="outlined">
        <EntityTable
          columns={[
            {
              header: 'Relationship',
              render: (link: AssetRelationship) => (
                <Chip
                  size="small"
                  variant="outlined"
                  color={RELATIONSHIP_COLOR[link.typeName] ?? 'default'}
                  label={link.typeName}
                />
              ),
            },
            { header: 'Asset', render: (link: AssetRelationship) => link.otherAssetLabel },
            { header: 'Category', secondary: true, render: (link: AssetRelationship) => link.otherAssetCategory },
          ]}
          rows={links.data ?? []}
          rowKey={(link) => link.id}
          loading={links.isLoading}
          emptyMessage="Nothing linked yet. Use this to record an SFP in its host switch, a spare held for a specific device, or two devices cabled together."
          cardTitle={(link) => `${link.typeName} · ${link.otherAssetLabel}`}
          rowActions={(link) => (
            <>
              <Button
                size="small"
                onClick={() => navigate(`/assets/${link.otherAssetId}`)}
              >
                View
              </Button>
              {canManage && (
                <Button size="small" color="error" onClick={() => remove.mutate(link.id)}>
                  Unlink
                </Button>
              )}
            </>
          )}
        />
      </Paper>

      <Dialog open={adding} onClose={close} fullWidth maxWidth="sm">
        <DialogTitle>Link an asset</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              select
              label="Relationship"
              value={typeId}
              onChange={(event) => setTypeId(event.target.value)}
              helperText="Reads from this asset outwards — this asset is installed in, connected to, or a spare for the one you pick."
            >
              {(types.data ?? []).map((type) => (
                <MenuItem key={type.id} value={String(type.id)}>
                  {type.name}
                </MenuItem>
              ))}
            </TextField>

            <Autocomplete
              options={(candidates.data?.content ?? []).filter((a) => String(a.id) !== assetId)}
              getOptionLabel={(option) => `${option.displayLabel} · ${option.categoryName}`}
              value={target}
              onChange={(_, next) => setTarget(next)}
              onInputChange={(_, value) => setSearch(value)}
              loading={candidates.isFetching}
              filterOptions={(options) => options}
              noOptionsText={
                search.trim().length < 2 ? 'Type at least two characters' : 'No matching assets'
              }
              renderInput={(params) => (
                <TextField
                  {...params}
                  label="Asset"
                  helperText="Search by name, serial number, asset tag, or hostname."
                />
              )}
            />

            <Typography variant="caption" color="text.secondary">
              The link is stored once and appears on both assets, worded the other way round on
              theirs. There is no need to record it again from the other side.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!typeId || !target || create.isPending}
            onClick={() => create.mutate()}
          >
            Link
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
