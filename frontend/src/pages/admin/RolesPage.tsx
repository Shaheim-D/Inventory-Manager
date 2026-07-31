import { useState } from 'react';
import {
  Alert,
  Box,
  Checkbox,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Permission, Role } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';

/**
 * Roles as rows, permission keys as columns. Roles are named bundles of
 * permissions and nothing more — no behavior anywhere in the platform keys on a
 * role's name, which is what makes editing this grid safe.
 */
export function RolesPage() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api.get<Role[]>('/api/admin/roles') });
  const permissions = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api.get<Permission[]>('/api/admin/permissions'),
  });

  const save = useMutation({
    mutationFn: (role: Role) =>
      api.put(`/api/admin/roles/${role.id}`, { name: role.name, permissionIds: role.permissionIds }),
    onSuccess: () => {
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['roles'] });
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Could not save the role.'),
  });

  function toggle(role: Role, permissionId: number, checked: boolean) {
    const permissionIds = checked
      ? [...role.permissionIds, permissionId]
      : role.permissionIds.filter((id) => id !== permissionId);
    save.mutate({ ...role, permissionIds });
  }

  return (
    <>
      <PageHeader
        title="Roles & permissions"
        subtitle="Every authorization decision in the platform resolves to one of these keys."
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined">
        <TableContainer sx={{ maxHeight: '70vh' }}>
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow>
                <TableCell sx={{ position: 'sticky', left: 0, bgcolor: 'background.paper', zIndex: 3, minWidth: 220 }}>
                  Permission
                </TableCell>
                {(roles.data ?? []).map((role) => (
                  <TableCell key={role.id} align="center" sx={{ whiteSpace: 'nowrap' }}>
                    {role.name}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {(permissions.data ?? []).map((permission) => (
                <TableRow key={permission.id} hover>
                  <TableCell sx={{ position: 'sticky', left: 0, bgcolor: 'background.paper', zIndex: 2 }}>
                    <Tooltip title={permission.description} placement="right">
                      <Box>
                        <Typography variant="body2" fontFamily="monospace">
                          {permission.permissionKey}
                        </Typography>
                      </Box>
                    </Tooltip>
                  </TableCell>
                  {(roles.data ?? []).map((role) => (
                    <TableCell key={role.id} align="center" padding="checkbox">
                      <Checkbox
                        size="small"
                        checked={role.permissionIds.includes(permission.id)}
                        onChange={(event) => toggle(role, permission.id, event.target.checked)}
                      />
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Paper>
    </>
  );
}
