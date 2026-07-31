import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { PageHeader } from '../components/PageHeader';

export function ChangePasswordPage() {
  const navigate = useNavigate();
  const { user, refresh } = useAuth();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const directoryAccount = user?.authProvider !== 'LOCAL';

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (newPassword !== confirmPassword) {
      setError('The two new passwords do not match.');
      return;
    }
    try {
      await api.post('/api/auth/change-password', { currentPassword, newPassword });
      await refresh();
      setDone(true);
      setTimeout(() => navigate('/'), 1200);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Could not change the password.');
    }
  }

  return (
    <>
      <PageHeader title="Change password" />
      <Card sx={{ maxWidth: 480 }}>
        <CardContent>
          {directoryAccount ? (
            <Alert severity="info">
              This account signs in through your directory, so its password is managed there rather than
              in Inventory Manager.
            </Alert>
          ) : (
            <Stack component="form" spacing={2} onSubmit={submit}>
              {user?.mustChangePassword && (
                <Alert severity="warning">
                  This password was issued by an administrator and needs to be changed.
                </Alert>
              )}
              {error && <Alert severity="error">{error}</Alert>}
              {done && <Alert severity="success">Password changed.</Alert>}

              <TextField
                label="Current password"
                type="password"
                autoComplete="current-password"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
                required
              />
              <TextField
                label="New password"
                type="password"
                autoComplete="new-password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                required
              />
              <TextField
                label="Confirm new password"
                type="password"
                autoComplete="new-password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                required
              />
              <Typography variant="caption" color="text.secondary">
At least 8 characters. Length is what matters — pick something you can remember.
              </Typography>
              <Button type="submit" variant="contained">
                Change password
              </Button>
            </Stack>
          )}
        </CardContent>
      </Card>
    </>
  );
}
