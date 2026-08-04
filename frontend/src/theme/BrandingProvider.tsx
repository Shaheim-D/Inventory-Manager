import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Branding } from '../api/types';

/**
 * Builds the MUI theme from whatever branding the deployment has uploaded.
 *
 * The defaults below are a real, finished neutral theme, not a placeholder —
 * an installation with no logo and no palette still looks deliberate. Uploading
 * a logo and colors through Admin > Branding is a theme-level change that every
 * component picks up, which is exactly what MOP §1.5 asks for.
 */
const DEFAULT_PRIMARY = '#1f2a44';
const DEFAULT_SECONDARY = '#4a5a7a';

/**
 * Links are hyperlink blue rather than the brand colour, deliberately.
 *
 * MUI points links at `primary.main`, and this platform's primary is a near
 * black navy — which renders a link as bold text and nothing else. Blue is what
 * people have been taught to click for thirty years, and an installation that
 * brands itself dark green or maroon should not lose that signal. So links keep
 * their own colour and do not follow the palette.
 */
const LINK_BLUE = '#1565c0';

interface BrandingValue {
  branding: Branding | undefined;
  organizationName: string;
  logoUrl: string | null;
}

const BrandingContext = createContext<BrandingValue | undefined>(undefined);

export function BrandingProvider({ children }: { children: ReactNode }) {
  const { data } = useQuery({
    queryKey: ['branding'],
    queryFn: () => api.get<Branding>('/api/branding'),
    staleTime: 60_000,
  });

  const value = useMemo<BrandingValue>(
    () => ({
      branding: data,
      organizationName: data?.organizationName?.trim() || 'Inventory Manager',
      // The cache-busting stamp is the upload time, so a re-uploaded logo
      // appears immediately instead of waiting out the response's max-age.
      logoUrl: data?.hasLogo ? `/api/branding/logo?v=${encodeURIComponent(data.logoUpdatedAt ?? '')}` : null,
    }),
    [data],
  );

  const theme = useMemo(
    () =>
      createTheme({
        palette: {
          primary: { main: data?.primaryColor || DEFAULT_PRIMARY },
          secondary: { main: data?.secondaryColor || DEFAULT_SECONDARY },
          background: { default: '#f5f6f8' },
        },
        shape: { borderRadius: 8 },
        typography: {
          fontFamily: '"Inter", "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
          h5: { fontWeight: 600 },
          h6: { fontWeight: 600 },
        },
        components: {
          MuiButton: {
            defaultProps: { disableElevation: true },
            // Touch-sized targets by default: review queues get worked through on
            // a phone while walking the warehouse (Frontend Design §8).
            styleOverrides: { root: { textTransform: 'none', minHeight: 40 } },
          },
          MuiTextField: { defaultProps: { size: 'small', fullWidth: true } },
          // Dropdowns open beneath the field they belong to. MUI's default is
          // to lay the menu over the input, centred on whichever option is
          // selected -- which hides the very field you are choosing for, and
          // reads as the page having broken rather than as a list opening.
          MuiSelect: {
            defaultProps: {
              MenuProps: {
                anchorOrigin: { vertical: 'bottom', horizontal: 'left' },
                transformOrigin: { vertical: 'top', horizontal: 'left' },
                // Otherwise the menu is laid out from the selected item and
                // still creeps upward over the field on a long list.
                slotProps: { paper: { sx: { maxHeight: 360 } } },
              },
            },
          },
          MuiCard: { defaultProps: { variant: 'outlined' } },
          MuiLink: {
            styleOverrides: {
              root: {
                color: LINK_BLUE,
                textDecorationColor: 'currentColor',
                fontWeight: 500,
                '&:hover': { color: '#0d47a1' },
              },
            },
          },
        },
      }),
    [data?.primaryColor, data?.secondaryColor],
  );

  return (
    <BrandingContext.Provider value={value}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    </BrandingContext.Provider>
  );
}

export function useBranding(): BrandingValue {
  const context = useContext(BrandingContext);
  if (!context) throw new Error('useBranding must be used inside BrandingProvider');
  return context;
}
