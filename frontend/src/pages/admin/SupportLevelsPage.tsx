import { useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { SupportLevel } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';
import { EntityTable } from '../../components/EntityTable';
import { useAuth } from '../../auth/AuthContext';

/**
 * The support tiers cover can be recorded against.
 *
 * <p>A vocabulary rather than free text, for the reason location types are one:
 * Dell's tiers and Cisco's are different lists, so neither belongs in code,
 * while free text means "ProSupport Plus", "Prosupport+" and "PS Plus" all end
 * up in the column and no report can group them. Adding the next tier is a row
 * somebody adds here.
 */
export function SupportLevelsPage() {
  const { has } = useAuth();
  const canManage = has('category:manage');
  const queryClient = useQueryClient();

  const [editing, setEditing] = useState<SupportLevel | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const levels = useQuery({
    queryKey: ['support-levels', 'all'],
    // Retired tiers included: the screen has to show one in order to offer
    // putting it back.
    queryFn: () => api.get<SupportLevel[]>('/api/support-levels?includeInactive=true'),
  });

  const save = useMutation({
    mutationFn: (level: Partial<SupportLevel> & { id?: number }) =>
      level.id
        ? api.put<SupportLevel>(`/api/support-levels/${level.id}`, level)
        : api.post<SupportLevel>('/api/support-levels', level),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['support-levels'] });
      setEditing(null);
      setCreating(false);
      setError(null);
    },
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not save that level.'),
  });

  const retire = useMutation({
    mutationFn: (id: number) => api.del(`/api/support-levels/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['support-levels'] }),
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not retire that level.'),
  });

  const open = creating || editing !== null;
  const subject = editing ?? ({} as Partial<SupportLevel>);
  const [draft, setDraft] = useState<Partial<SupportLevel>>({});
  const current = { ...subject, ...draft };

  function begin(level: SupportLevel | null) {
    setError(null);
    setDraft({});
    if (level) setEditing(level);
    else setCreating(true);
  }

  function close() {
    setEditing(null);
    setCreating(false);
    setDraft({});
    setError(null);
  }

  return (
    <>
      <PageHeader
        title="Support levels"
        help="The service tiers your providers sell, so an asset's cover can say which one it is on. A tier an asset already references is retired rather than deleted — it still describes the contract that asset was bought under."
        actions={
          canManage && (
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => begin(null)}>
              New level
            </Button>
          )
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <EntityTable
        rows={levels.data ?? []}
        loading={levels.isLoading}
        rowKey={(row) => row.id}
        emptyMessage="No support levels yet. Add the tiers your providers sell."
        columns={[
          { key: 'name', header: 'Level', render: (row) => row.name },
          { key: 'provider', header: 'Provider', render: (row) => row.provider ?? '—' },
          { key: 'description', header: 'What it covers', render: (row) => row.description ?? '—' },
          {
            key: 'active',
            header: 'Status',
            render: (row) => (row.active ? 'Offered' : 'Retired'),
          },
        ]}
        rowActions={
          canManage
            ? (row) => (
                <Stack direction="row" spacing={1}>
                  <Button size="small" onClick={() => begin(row)}>
                    Edit
                  </Button>
                  {row.active && (
                    <Button size="small" color="error" onClick={() => retire.mutate(row.id)}>
                      Retire
                    </Button>
                  )}
                </Stack>
              )
            : undefined
        }
      />

      <Dialog open={open} onClose={close} maxWidth="sm" fullWidth>
        <DialogTitle>{editing ? 'Edit support level' : 'New support level'}</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0 }}>
            <Grid item xs={12} sm={7}>
              <TextField
                label="Level"
                required
                value={current.name ?? ''}
                onChange={(event) => setDraft({ ...draft, name: event.target.value })}
                helperText="As the provider names it, e.g. ProSupport Plus."
              />
            </Grid>
            <Grid item xs={12} sm={5}>
              <TextField
                label="Provider"
                value={current.provider ?? ''}
                onChange={(event) => setDraft({ ...draft, provider: event.target.value })}
                helperText="Dell, Cisco…"
              />
            </Grid>
            <Grid item xs={12}>
              <TextField
                label="What it covers"
                value={current.description ?? ''}
                multiline
                minRows={2}
                onChange={(event) => setDraft({ ...draft, description: event.target.value })}
                helperText="Response time and hours, so whoever picks it knows what they are choosing."
              />
            </Grid>
            {editing && !editing.active && (
              <Grid item xs={12}>
                <Typography variant="body2" color="text.secondary">
                  This level is retired and is not offered on new assets. Saving it will offer it
                  again.
                </Typography>
              </Grid>
            )}
          </Grid>
        </DialogContent>
        <DialogActions>
          <Button onClick={close}>Cancel</Button>
          <Button
            variant="contained"
            disabled={!current.name || save.isPending}
            onClick={() =>
              save.mutate({
                id: editing?.id,
                name: current.name,
                provider: current.provider,
                description: current.description,
                active: true,
              })
            }
          >
            {save.isPending ? 'Saving…' : 'Save'}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
