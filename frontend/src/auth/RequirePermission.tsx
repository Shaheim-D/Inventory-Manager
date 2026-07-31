import { Alert, Box, CircularProgress } from '@mui/material';
import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './AuthContext';

/**
 * Route guards key on permission strings, never role names. This is defense in
 * depth for navigation only — the API enforces the same rules independently, so
 * a hand-typed URL gets a 403 from the server regardless of what happens here.
 */
export function RequirePermission({
  permissions,
  children,
}: {
  permissions?: string[];
  children: ReactNode;
}) {
  const { user, loading, hasAny } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }

  if (permissions && permissions.length > 0 && !hasAny(...permissions)) {
    return (
      <Alert severity="warning">
        You do not have permission to view this page. If you think you should, ask an administrator to
        review your role assignment.
      </Alert>
    );
  }

  return <>{children}</>;
}
