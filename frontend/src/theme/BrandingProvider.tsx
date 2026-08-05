import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
// Self-hosted rather than fetched from a font CDN: this application is
// installed on a customer's own VM, sometimes without egress, and a typeface
// that silently fails to load takes the whole typographic scale with it. The
// theme asked for Inter long before anything actually loaded it.
import '@fontsource-variable/inter';
import { api } from '../api/client';
import type { Branding } from '../api/types';
import { createAppTheme } from './createAppTheme';

/**
 * Supplies the branding an installation has uploaded, and builds the theme from
 * it.
 *
 * The look itself lives in `createAppTheme` — this file's job is only to feed
 * it the organization's colours. Uploading a logo and palette through
 * Admin > Branding is a theme-level change that every component picks up, which
 * is exactly what MOP §1.5 asks for.
 */

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
    () => createAppTheme(data?.primaryColor, data?.secondaryColor),
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
