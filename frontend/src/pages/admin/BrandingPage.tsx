import { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Grid,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Branding } from '../../api/types';
import { PageHeader } from '../../components/PageHeader';

/**
 * Where a real logo and palette get into a running instance. The MOP commits to
 * branding being a theme-level configuration change rather than a rebuild, and
 * this screen is the whole of that promise: upload here, and the sign-in screen,
 * the app bar, and the MUI palette all pick it up.
 */
export function BrandingPage() {
  const queryClient = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);

  const { data } = useQuery({ queryKey: ['branding'], queryFn: () => api.get<Branding>('/api/branding') });

  const [organizationName, setOrganizationName] = useState('');
  const [primaryColor, setPrimaryColor] = useState('');
  const [secondaryColor, setSecondaryColor] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (!data) return;
    setOrganizationName(data.organizationName ?? '');
    setPrimaryColor(data.primaryColor ?? '');
    setSecondaryColor(data.secondaryColor ?? '');
  }, [data]);

  function afterChange() {
    setError(null);
    setSaved(true);
    void queryClient.invalidateQueries({ queryKey: ['branding'] });
    setTimeout(() => setSaved(false), 2500);
  }

  function onError(caught: unknown) {
    setSaved(false);
    setError(caught instanceof ApiError ? caught.message : 'Something went wrong.');
  }

  const saveSettings = useMutation({
    mutationFn: () =>
      api.put<Branding>('/api/branding', {
        organizationName: organizationName || null,
        primaryColor: primaryColor || null,
        secondaryColor: secondaryColor || null,
      }),
    onSuccess: afterChange,
    onError,
  });

  const uploadLogo = useMutation({
    mutationFn: (file: File) => api.upload<Branding>('/api/branding/logo', file),
    onSuccess: afterChange,
    onError,
  });

  const removeLogo = useMutation({
    mutationFn: () => api.del('/api/branding/logo'),
    onSuccess: afterChange,
    onError,
  });

  const logoUrl = data?.hasLogo ? `/api/branding/logo?v=${encodeURIComponent(data.logoUpdatedAt ?? '')}` : null;

  return (
    <>
      <PageHeader
        title="Branding"
        help="Upload the organization's logo and set the palette the interface is themed from."
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {saved && (
        <Alert severity="success" sx={{ mb: 2 }}>
          Saved. The change applies across the application immediately.
        </Alert>
      )}

      <Grid container spacing={2}>
        <Grid item xs={12} md={6}>
          <Card>
            <CardContent>
              <Typography variant="subtitle1" gutterBottom>
                Logo
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                PNG, JPEG, SVG, or WebP, up to 2 MB. It appears on the sign-in screen and in the top bar.
                A wide, transparent-background image works best.
              </Typography>

              <Box
                sx={{
                  border: '1px dashed',
                  borderColor: 'divider',
                  borderRadius: 1,
                  p: 3,
                  mb: 2,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  minHeight: 120,
                  // White only when there is actually a logo to show, because
                  // most are dark ink on transparency and need it. With nothing
                  // uploaded the same plate is just a slab of white on a dark
                  // page saying "no logo uploaded yet".
                  ...(logoUrl && { bgcolor: 'common.white' }),
                }}
              >
                {logoUrl ? (
                  <Box
                    component="img"
                    src={logoUrl}
                    alt="Current logo"
                    sx={{ maxHeight: 80, maxWidth: '100%', objectFit: 'contain' }}
                  />
                ) : (
                  <Typography variant="body2" color="text.secondary">
                    No logo uploaded yet
                  </Typography>
                )}
              </Box>

              <input
                ref={fileInput}
                type="file"
                accept="image/png,image/jpeg,image/svg+xml,image/webp"
                style={{ display: 'none' }}
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) uploadLogo.mutate(file);
                  event.target.value = '';
                }}
              />
              <Stack direction="row" spacing={1}>
                <Button variant="contained" onClick={() => fileInput.current?.click()} disabled={uploadLogo.isPending}>
                  {uploadLogo.isPending ? 'Uploading…' : data?.hasLogo ? 'Replace logo' : 'Upload logo'}
                </Button>
                {data?.hasLogo && (
                  <Button color="error" onClick={() => removeLogo.mutate()} disabled={removeLogo.isPending}>
                    Remove
                  </Button>
                )}
              </Stack>
              {data?.logoFilename && (
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                  Current file: {data.logoFilename}
                </Typography>
              )}
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={6}>
          <Card>
            <CardContent>
              <Typography variant="subtitle1" gutterBottom>
                Name and colors
              </Typography>
              <Stack spacing={2} sx={{ mt: 2 }}>
                <TextField
                  label="Organization name"
                  value={organizationName}
                  onChange={(event) => setOrganizationName(event.target.value)}
                  placeholder="Mid-Hudson Fiber"
                  helperText="Shown on the sign-in screen when no logo is set."
                />
                <ColorField label="Primary color" value={primaryColor} onChange={setPrimaryColor} />
                <ColorField label="Secondary color" value={secondaryColor} onChange={setSecondaryColor} />
                <Box>
                  <Button
                    variant="contained"
                    onClick={() => saveSettings.mutate()}
                    disabled={saveSettings.isPending}
                  >
                    Save
                  </Button>
                </Box>
                <Typography variant="caption" color="text.secondary">
                  Leave a color blank to keep the neutral default. Colors are hex values such as #1B34C8.
                </Typography>
                {/* Somebody will set a colour, switch to dark, see nothing
                    change, and file it as a bug. Say so here rather than let
                    them find out. */}
                <Typography variant="caption" color="text.secondary">
                  These colors apply in light mode. Dark mode uses its own palette, because a color
                  picked to read well on white has no guarantee of contrast on near-black. Your logo
                  is used in both.
                </Typography>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </>
  );
}

function ColorField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <Stack direction="row" spacing={1} alignItems="flex-start">
      <TextField label={label} value={value} onChange={(event) => onChange(event.target.value)} placeholder="#1B34C8" />
      <Box
        component="input"
        type="color"
        value={/^#[0-9a-fA-F]{6}$/.test(value) ? value : '#1f2a44'}
        onChange={(event: React.ChangeEvent<HTMLInputElement>) => onChange(event.target.value.toUpperCase())}
        sx={{ width: 48, height: 40, p: 0, border: 'none', background: 'none', cursor: 'pointer' }}
        aria-label={`${label} picker`}
      />
    </Stack>
  );
}
