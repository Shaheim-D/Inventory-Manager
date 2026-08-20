import { useState } from 'react';
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
  FormGroup,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Permission, Role, UserSummary } from '../../api/types';
import { EntityTable } from '../../components/EntityTable';
import { BulkDeleteBar } from '../../components/BulkDeleteBar';
import { PageHeader } from '../../components/PageHeader';
import { useAuth } from '../../auth/AuthContext';

interface UserDetail extends UserSummary {
  overrides: { id: number; permissionId: number; permissionKey: string; effect: 'GRANT' | 'DENY' }[];
  effectivePermissions: string[];
}

export function UsersPage() {
  const [selected, setSelected] = useState<Set<string | number>>(new Set());
  const queryClient = useQueryClient();
  const { has } = useAuth();
  const [creating, setCreating] = useState(false);
  const [detailId, setDetailId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const users = useQuery({ queryKey: ['users'], queryFn: () => api.get<UserSummary[]>('/api/admin/users') });
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api.get<Role[]>('/api/admin/roles') });

  const unlock = useMutation({
    mutationFn: (id: number) => api.post(`/api/admin/users/${id}/unlock`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['users'] }),
  });

  return (
    <>
      <PageHeader
        title="Users"
        help="Role assignment plus, separately, individual exceptions to that role."
        actions={
          <Button variant="contained" onClick={() => setCreating(true)}>
            New user
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
            { header: 'Username', render: (user: UserSummary) => user.username, secondary: true },
            { header: 'Email', render: (user: UserSummary) => user.email ?? '—' },
            { header: 'Sign-in', render: (user: UserSummary) => user.authProvider.replaceAll('_', ' ') },
            {
              header: 'Roles',
              render: (user: UserSummary) =>
                user.roles.length === 0 ? '—' : user.roles.map((role) => role.name).join(', '),
            },
            {
              header: 'Status',
              render: (user: UserSummary) => (
                <Stack direction="row" spacing={0.5}>
                  {!user.active && <Chip size="small" color="default" label="Inactive" />}
                  {user.locked && <Chip size="small" color="warning" label="Locked" />}
                  {user.active && !user.locked && <Chip size="small" color="success" label="Active" />}
                </Stack>
              ),
            },
            {
              header: 'Last sign-in',
              secondary: true,
              render: (user: UserSummary) =>
                user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : 'Never',
            },
          ]}
          rows={users.data ?? []}
          rowKey={(user) => user.id}
          selectable
          selectedIds={selected}
          onSelectionChange={setSelected}
          loading={users.isLoading}
          cardTitle={(user) => user.username}
          rowActions={(user) => (
            <>
              <Button size="small" onClick={() => setDetailId(user.id)}>
                Manage
              </Button>
              {user.locked && (
                <Button size="small" onClick={() => unlock.mutate(user.id)}>
                  Unlock
                </Button>
              )}
            </>
          )}
        />
      </Paper>

      <BulkDeleteBar
        endpoint="/api/admin/users/bulk-delete"
        selected={selected}
        onClear={() => setSelected(new Set())}
        invalidate={[['users'], ['recycle-bin', 'users']]}
        noun="user"
      />

      {creating && (
        <CreateUserDialog
          roles={roles.data ?? []}
          onClose={() => setCreating(false)}
          onError={setError}
          onCreated={() => {
            setCreating(false);
            void queryClient.invalidateQueries({ queryKey: ['users'] });
          }}
        />
      )}

      {detailId != null && (
        <UserDetailDialog
          userId={detailId}
          roles={roles.data ?? []}
          canManageOverrides={has('role:manage')}
          onClose={() => setDetailId(null)}
        />
      )}
    </>
  );
}

function CreateUserDialog({
  roles,
  onClose,
  onCreated,
  onError,
}: {
  roles: Role[];
  onClose: () => void;
  onCreated: () => void;
  onError: (message: string) => void;
}) {
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [roleIds, setRoleIds] = useState<number[]>([]);

  const create = useMutation({
    mutationFn: () =>
      api.post('/api/admin/users', { username, email: email || null, password, roleIds }),
    onSuccess: onCreated,
    onError: (caught) => onError(caught instanceof ApiError ? caught.message : 'Could not create the user.'),
  });

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>New user</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Username" required value={username} onChange={(e) => setUsername(e.target.value)} />
          <TextField label="Email" value={email} onChange={(e) => setEmail(e.target.value)} />
          {/* No sign-in method picker. A password set here always works, and if
              RADIUS is configured the same person's network password works too --
              the two are additive, so there is nothing to choose between. A RADIUS
              user nobody has typed in appears by itself on first sign-in. */}
          <TextField
            label="Temporary password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            helperText="At least 8 characters. The user is prompted to change it at first sign-in."
          />
          <Typography variant="subtitle2">Roles</Typography>
          <FormGroup>
            {roles.map((role) => (
              <FormControlLabel
                key={role.id}
                control={
                  <Checkbox
                    checked={roleIds.includes(role.id)}
                    onChange={(event) =>
                      setRoleIds((current) =>
                        event.target.checked ? [...current, role.id] : current.filter((id) => id !== role.id),
                      )
                    }
                  />
                }
                label={role.name}
              />
            ))}
          </FormGroup>
          <Typography variant="caption" color="text.secondary">
            With no role selected the account gets Unassigned — zero permissions until you grant some.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => create.mutate()} disabled={!username || create.isPending}>
          Create
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function UserDetailDialog({
  userId,
  roles,
  canManageOverrides,
  onClose,
}: {
  userId: number;
  roles: Role[];
  canManageOverrides: boolean;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const user = useQuery({
    queryKey: ['user', userId],
    queryFn: () => api.get<UserDetail>(`/api/admin/users/${userId}`),
  });
  const permissions = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api.get<Permission[]>('/api/admin/permissions'),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['user', userId] });
    void queryClient.invalidateQueries({ queryKey: ['users'] });
  };

  const updateRoles = useMutation({
    mutationFn: (roleIds: number[]) =>
      api.put(`/api/admin/users/${userId}`, { email: user.data?.email ?? null, roleIds }),
    onSuccess: invalidate,
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not update roles.'),
  });

  const setOverride = useMutation({
    mutationFn: (payload: { permissionId: number; effect: 'GRANT' | 'DENY' }) =>
      api.post(`/api/admin/users/${userId}/overrides`, payload),
    onSuccess: invalidate,
  });

  const clearOverride = useMutation({
    mutationFn: (overrideId: number) => api.del(`/api/admin/users/${userId}/overrides/${overrideId}`),
    onSuccess: invalidate,
  });

  const assignedRoleIds = new Set((user.data?.roles ?? []).map((role) => role.id));

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{user.data?.username ?? 'User'}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        <Typography variant="subtitle2" gutterBottom>
          Roles
        </Typography>
        <FormGroup row>
          {roles.map((role) => (
            <FormControlLabel
              key={role.id}
              control={
                <Checkbox
                  checked={assignedRoleIds.has(role.id)}
                  onChange={(event) => {
                    const next = new Set(assignedRoleIds);
                    if (event.target.checked) next.add(role.id);
                    else next.delete(role.id);
                    updateRoles.mutate([...next]);
                  }}
                />
              }
              label={role.name}
            />
          ))}
        </FormGroup>

        {canManageOverrides && (
          <>
            <Typography variant="subtitle2" sx={{ mt: 3 }} gutterBottom>
              Individual exceptions
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              These sit outside role membership entirely. A DENY always beats whatever the role grants.
            </Typography>

            <Stack spacing={1} sx={{ mb: 2 }}>
              {(user.data?.overrides ?? []).map((override) => (
                <Stack key={override.id} direction="row" spacing={1} alignItems="center">
                  <Chip
                    size="small"
                    color={override.effect === 'DENY' ? 'error' : 'success'}
                    label={`${override.effect} ${override.permissionKey}`}
                  />
                  <Button size="small" onClick={() => clearOverride.mutate(override.id)}>
                    Clear
                  </Button>
                </Stack>
              ))}
              {user.data?.overrides.length === 0 && (
                <Typography variant="body2" color="text.secondary">
                  No exceptions — this account gets exactly what its roles grant.
                </Typography>
              )}
            </Stack>

            <AddOverride
              permissions={permissions.data ?? []}
              onAdd={(permissionId, effect) => setOverride.mutate({ permissionId, effect })}
            />
          </>
        )}

        <Typography variant="subtitle2" sx={{ mt: 3 }} gutterBottom>
          Effective permissions
        </Typography>
        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
          {(user.data?.effectivePermissions ?? []).map((key) => (
            <Chip key={key} size="small" variant="outlined" label={key} />
          ))}
          {user.data?.effectivePermissions.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              None. This account can sign in but do nothing.
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Done</Button>
      </DialogActions>
    </Dialog>
  );
}

function AddOverride({
  permissions,
  onAdd,
}: {
  permissions: Permission[];
  onAdd: (permissionId: number, effect: 'GRANT' | 'DENY') => void;
}) {
  const [permissionId, setPermissionId] = useState('');
  const [effect, setEffect] = useState<'GRANT' | 'DENY'>('GRANT');

  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
      <TextField
        select
        label="Permission"
        value={permissionId}
        onChange={(event) => setPermissionId(event.target.value)}
        sx={{ minWidth: 260 }}
      >
        {permissions.map((permission) => (
          <MenuItem key={permission.id} value={String(permission.id)}>
            {permission.permissionKey}
          </MenuItem>
        ))}
      </TextField>
      <TextField
        select
        label="Effect"
        value={effect}
        onChange={(event) => setEffect(event.target.value as 'GRANT' | 'DENY')}
        sx={{ minWidth: 140 }}
      >
        <MenuItem value="GRANT">Grant</MenuItem>
        <MenuItem value="DENY">Deny</MenuItem>
      </TextField>
      <Button variant="outlined" disabled={!permissionId} onClick={() => onAdd(Number(permissionId), effect)}>
        Add exception
      </Button>
    </Stack>
  );
}
