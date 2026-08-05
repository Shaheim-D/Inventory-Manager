import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
// Self-hosted rather than fetched from a font CDN: this application is
// installed on a customer's own VM, sometimes without egress, and a typeface
// that silently fails to load takes the whole typographic scale with it. The
// theme asked for Inter long before anything actually loaded it.
import '@fontsource-variable/inter';
import { api } from '../api/client';
import type { Branding } from '../api/types';
import { createAppTheme, type ThemeMode } from './createAppTheme';

/**
 * Supplies the branding an installation has uploaded, plus the viewer's choice
 * of light or dark, and builds the theme from the two.
 *
 * The look itself lives in `createAppTheme` — this file's job is only to decide
 * what to feed it. Uploading a logo and palette through Admin > Branding is a
 * theme-level change that every component picks up, which is exactly what
 * MOP §1.5 asks for.
 *
 * Light or dark rides along here rather than in a context of its own. It is
 * the same question — what does this look like — answered by a different
 * source, and every consumer that wants one generally wants the other.
 */

const MODE_STORAGE_KEY = 'inventory-manager.theme-mode';

interface BrandingValue {
  branding: Branding | undefined;
  organizationName: string;
  logoUrl: string | null;
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  toggleMode: () => void;
}

const BrandingContext = createContext<BrandingValue | undefined>(undefined);

/**
 * A per-device preference, not a per-account one.
 *
 * Somebody working from a bright warehouse floor and a dim office at night
 * wants different answers on the two machines, and storing it on the user
 * would force one answer everywhere. It also means the choice survives a
 * signed-out reload, so the sign-in screen is not the one screen that ignores
 * it. Falls back to whatever the operating system already says.
 */
function initialMode(): ThemeMode {
  try {
    const stored = window.localStorage.getItem(MODE_STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch {
    // Private browsing, or storage disabled by policy. Not worth failing over;
    // the preference simply does not persist.
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function BrandingProvider({ children }: { children: ReactNode }) {
  const { data } = useQuery({
    queryKey: ['branding'],
    queryFn: () => api.get<Branding>('/api/branding'),
    staleTime: 60_000,
  });

  const [mode, setModeState] = useState<ThemeMode>(initialMode);

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next);
    try {
      window.localStorage.setItem(MODE_STORAGE_KEY, next);
    } catch {
      // As above: an unwritable store costs persistence, not the feature.
    }
  }, []);

  const toggleMode = useCallback(
    () => setMode(mode === 'dark' ? 'light' : 'dark'),
    [mode, setMode],
  );

  // Follow the operating system until somebody states a preference. After that
  // the explicit choice wins, including across a system change.
  useEffect(() => {
    const query = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!query) return;
    const onChange = (event: MediaQueryListEvent) => {
      let stored: string | null = null;
      try {
        stored = window.localStorage.getItem(MODE_STORAGE_KEY);
      } catch {
        stored = null;
      }
      if (stored !== 'light' && stored !== 'dark') setModeState(event.matches ? 'dark' : 'light');
    };
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  const value = useMemo<BrandingValue>(
    () => ({
      branding: data,
      organizationName: data?.organizationName?.trim() || 'Inventory Manager',
      // The cache-busting stamp is the upload time, so a re-uploaded logo
      // appears immediately instead of waiting out the response's max-age.
      logoUrl: data?.hasLogo ? `/api/branding/logo?v=${encodeURIComponent(data.logoUpdatedAt ?? '')}` : null,
      mode,
      setMode,
      toggleMode,
    }),
    [data, mode, setMode, toggleMode],
  );

  // The uploaded colours are passed in either mode; `createAppTheme` is what
  // decides to ignore them in dark, so the rule lives with the palette rather
  // than being enforced twice.
  const theme = useMemo(
    () => createAppTheme(data?.primaryColor, data?.secondaryColor, mode),
    [data?.primaryColor, data?.secondaryColor, mode],
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
