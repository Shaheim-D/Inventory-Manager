import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Alert, Box, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material';
import { alpha } from '@mui/material/styles';
import InventoryIcon from '@mui/icons-material/Inventory2';
import { useAuth } from '../auth/AuthContext';
import { useBranding } from '../theme/BrandingProvider';
import { ApiError } from '../api/client';

export function LoginPage() {
  const { user, loading, signIn } = useAuth();
  const { organizationName, logoUrl } = useBranding();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!loading && user) {
    const from = (location.state as { from?: string } | null)?.from;
    return <Navigate to={from && from !== '/login' ? from : '/'} replace />;
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await signIn(username, password);
      navigate('/', { replace: true });
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Could not sign in. Try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box
      sx={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        p: 2,
        bgcolor: 'background.default',
        // A quiet tint of the organization's own colour, so the sign-in screen
        // belongs to the installation before anyone has signed in. Two very
        // soft radial washes rather than a gradient bar: it has to sit behind a
        // white card without competing with it, whatever colour is chosen.
        backgroundImage: (theme) => `
          radial-gradient(60rem 30rem at 15% -10%, ${alpha(theme.palette.primary.main, 0.1)}, transparent 60%),
          radial-gradient(45rem 28rem at 100% 110%, ${alpha(theme.palette.secondary.main, 0.09)}, transparent 60%)
        `,
      }}
    >
      <Box sx={{ width: '100%', maxWidth: 420 }}>
        <Stack spacing={1} alignItems="center" sx={{ mb: 3, textAlign: 'center' }}>
          {/* Branding is readable without a session precisely so this screen
              can carry the organization's own logo. */}
          {logoUrl ? (
            <Box
              component="img"
              src={logoUrl}
              alt={organizationName}
              sx={{ maxHeight: 56, maxWidth: '100%', objectFit: 'contain' }}
            />
          ) : (
            <Box
              sx={{
                width: 48,
                height: 48,
                borderRadius: 2.5,
                bgcolor: 'primary.main',
                color: 'primary.contrastText',
                display: 'grid',
                placeItems: 'center',
                boxShadow: 3,
              }}
            >
              <InventoryIcon />
            </Box>
          )}
          <Typography variant="h5" sx={{ mt: 1 }}>
            {organizationName}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {/* Saying the product name twice under a logo that already says it
                was the old behaviour. It only earns a line when it is telling
                you something the name above does not. */}
            {organizationName === 'Inventory Manager'
              ? 'Sign in to continue'
              : 'Inventory Manager — sign in to continue'}
          </Typography>
        </Stack>

        <Card sx={{ boxShadow: 4 }}>
          <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
            <Stack spacing={2.5}>
              {error && <Alert severity="error">{error}</Alert>}

              <Box component="form" onSubmit={submit}>
                <Stack spacing={2}>
                  <TextField
                    label="Username"
                    value={username}
                    onChange={(event) => setUsername(event.target.value)}
                    autoComplete="username"
                    autoFocus
                    required
                  />
                  <TextField
                    label="Password"
                    type="password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    autoComplete="current-password"
                    required
                  />
                  <Button
                    type="submit"
                    variant="contained"
                    size="large"
                    disabled={submitting}
                    sx={{ mt: 0.5 }}
                  >
                    {submitting ? 'Signing in…' : 'Sign in'}
                  </Button>
                </Stack>
              </Box>
            </Stack>
          </CardContent>
        </Card>
      </Box>
    </Box>
  );
}
