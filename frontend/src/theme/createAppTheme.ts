import { alpha, createTheme, type Theme } from '@mui/material/styles';

/**
 * The design system, in one place.
 *
 * Everything visual that is not specific to a single screen lives here, so a
 * screen gets the current look by using ordinary MUI components rather than by
 * remembering a convention. That is deliberate: the alternative is forty files
 * each carrying their own `sx`, and a redesign that has to visit all of them.
 *
 * Two constraints shape everything below.
 *
 * **Light mode has to stay palette-agnostic.** Branding is uploadable — an
 * installation picks its own primary and secondary colours — so nothing may
 * assume the brand is dark, light, or any particular hue. Brand colour is used
 * for accent, emphasis and state; the frame around it is a fixed neutral scale
 * that reads the same whatever the organisation chooses.
 *
 * **Dark mode does not use the brand palette at all.** That is a decision, not
 * an omission. A colour chosen to look right on white has no guarantee of
 * contrast on near-black, and a brand navy on a dark ground is invisible — so
 * dark mode carries its own accent and the uploaded colours simply do not
 * apply. The uploaded *logo* still does: it is the organisation's mark, not
 * part of the palette.
 */

export type ThemeMode = 'light' | 'dark';

/** Neutral scale for light mode. Slightly cool, and picked for contrast. */
const NEUTRAL = {
  25: '#fcfcfd',
  50: '#f9fafb',
  100: '#f2f4f7',
  200: '#e4e7ec',
  300: '#d0d5dd',
  400: '#98a2b3',
  500: '#667085',
  600: '#475467',
  700: '#344054',
  800: '#1d2939',
  900: '#101828',
};

const DEFAULT_PRIMARY = '#1f2a44';
const DEFAULT_SECONDARY = '#4a5a7a';

/**
 * Dark mode's own accent, used everywhere light mode would use the brand.
 *
 * A mid-weight blue rather than anything more characterful, because it has to
 * carry selection, focus, progress and emphasis on a near-black ground without
 * ever being mistaken for a warning or a link.
 */
const DARK_PRIMARY = '#6ea8fe';
const DARK_SECONDARY = '#c9a227';

/**
 * Links keep their own colour rather than following the palette.
 *
 * MUI points links at `primary.main`, and this platform's default primary is a
 * near-black navy — which renders a link as bold text and nothing else. Blue is
 * what people have been taught to click for thirty years, and an installation
 * that brands itself dark green or maroon should not lose that signal.
 *
 * The dark variant is the same decision applied again: #1565c0 on near-black
 * fails contrast outright, so the blue lightens rather than being abandoned.
 */
const LINK = { light: '#1565c0', lightHover: '#0d47a1', dark: '#7cb2ff', darkHover: '#a8ceff' };

interface Tokens {
  /** Page background, and the surface cards sit on. */
  canvas: string;
  paper: string;
  /** One step up from `paper`, for table headers and inset panels. */
  raised: string;
  /** The translucent app bar fill, before the blur. */
  bar: string;
  border: string;
  textPrimary: string;
  textSecondary: string;
  textDisabled: string;
  /** Row hover in dense tables — barely there by design. */
  hover: string;
  scrollThumb: string;
  scrollThumbHover: string;
  tooltip: string;
  link: string;
  linkHover: string;
  /** Tonal alert fills, which cannot be derived from the palette alone. */
  alert: Record<'info' | 'success' | 'warning' | 'error', { bg: string; fg: string }>;
}

function tokensFor(mode: ThemeMode): Tokens {
  if (mode === 'light') {
    return {
      canvas: NEUTRAL[50],
      paper: '#ffffff',
      raised: NEUTRAL[50],
      bar: alpha('#ffffff', 0.86),
      border: NEUTRAL[200],
      textPrimary: NEUTRAL[900],
      textSecondary: NEUTRAL[600],
      textDisabled: NEUTRAL[400],
      hover: NEUTRAL[25],
      scrollThumb: NEUTRAL[300],
      scrollThumbHover: NEUTRAL[400],
      tooltip: NEUTRAL[800],
      link: LINK.light,
      linkHover: LINK.lightHover,
      alert: {
        info: { bg: '#eff8ff', fg: '#175cd3' },
        success: { bg: '#ecfdf3', fg: '#05603a' },
        warning: { bg: '#fffaeb', fg: '#b54708' },
        error: { bg: '#fef3f2', fg: '#b42318' },
      },
    };
  }

  // Not pure black: a true #000 canvas makes every shadow disappear and every
  // white glyph bloom. These are near-blacks with a trace of blue in them, so
  // the surfaces stay distinguishable from each other by lightness alone.
  return {
    canvas: '#0d1117',
    paper: '#161b22',
    raised: '#1c2129',
    bar: alpha('#161b22', 0.88),
    border: '#2a313c',
    textPrimary: '#e6edf3',
    textSecondary: '#9aa4b2',
    textDisabled: '#6b7681',
    hover: '#1c2129',
    scrollThumb: '#343b45',
    scrollThumbHover: '#454d59',
    tooltip: '#2a313c',
    link: LINK.dark,
    linkHover: LINK.darkHover,
    alert: {
      info: { bg: alpha('#6ea8fe', 0.14), fg: '#a8ceff' },
      success: { bg: alpha('#3fb950', 0.14), fg: '#7ee787' },
      warning: { bg: alpha('#d29922', 0.14), fg: '#e3b341' },
      error: { bg: alpha('#f85149', 0.14), fg: '#ff9a92' },
    },
  };
}

/**
 * A soft, close-range shadow scale.
 *
 * MUI's default shadows are the 2014 Material spec: dark, wide, and dramatic at
 * low elevations. Modern interface chrome wants the opposite — a shadow that
 * reads as "this surface is slightly above that one" rather than as a drop
 * shadow you notice. Two layers each: a tight contact shadow and a wider
 * ambient one.
 *
 * On a dark canvas a shadow has almost nothing to darken, so depth there comes
 * mostly from the hairline ring and only a little from the blur.
 */
function softShadows(mode: ThemeMode): Theme['shadows'] {
  const dark = mode === 'dark';
  const ring = dark ? '0 0 0 1px rgba(0, 0, 0, 0.5)' : '0 0 0 1px rgba(16, 24, 40, 0.04)';
  const rgb = dark ? '0, 0, 0' : '16, 24, 40';
  const k = dark ? 2.4 : 1;
  const at = (o: number) => `rgba(${rgb}, ${(o * k).toFixed(3)})`;

  const scale = [
    'none',
    `${ring}, 0 1px 2px ${at(0.06)}`,
    `${ring}, 0 1px 3px ${at(0.08)}, 0 1px 2px ${at(0.04)}`,
    `${ring}, 0 4px 8px -2px ${at(0.08)}, 0 2px 4px -2px ${at(0.04)}`,
    `${ring}, 0 8px 16px -4px ${at(0.1)}, 0 3px 6px -3px ${at(0.05)}`,
    `${ring}, 0 12px 20px -6px ${at(0.12)}, 0 4px 8px -4px ${at(0.06)}`,
    `${ring}, 0 16px 28px -8px ${at(0.14)}, 0 6px 10px -6px ${at(0.06)}`,
    `${ring}, 0 20px 36px -10px ${at(0.16)}, 0 8px 12px -8px ${at(0.07)}`,
  ];
  // MUI requires exactly 25 entries; the top of the range is rarely used, so it
  // holds at the deepest defined step rather than inventing twelve more.
  while (scale.length < 25) scale.push(scale[scale.length - 1]);
  return scale as unknown as Theme['shadows'];
}

export function createAppTheme(
  primaryColor?: string | null,
  secondaryColor?: string | null,
  mode: ThemeMode = 'light',
): Theme {
  const dark = mode === 'dark';
  const t = tokensFor(mode);

  // The whole of the branding rule, in two lines: in dark mode the uploaded
  // colours are not consulted at all.
  const primary = dark ? DARK_PRIMARY : primaryColor || DEFAULT_PRIMARY;
  const secondary = dark ? DARK_SECONDARY : secondaryColor || DEFAULT_SECONDARY;

  return createTheme({
    palette: {
      mode,
      primary: { main: primary },
      secondary: { main: secondary },
      background: { default: t.canvas, paper: t.paper },
      text: { primary: t.textPrimary, secondary: t.textSecondary, disabled: t.textDisabled },
      divider: t.border,
      grey: NEUTRAL,
      // Signal colours lighten in dark mode for the same reason links do.
      success: { main: dark ? '#3fb950' : '#12805c' },
      warning: { main: dark ? '#d29922' : '#b54708' },
      error: { main: dark ? '#f85149' : '#b42318' },
      info: { main: dark ? '#6ea8fe' : '#175cd3' },
    },

    shape: { borderRadius: 10 },
    shadows: softShadows(mode),

    typography: {
      fontFamily: '"Inter Variable", "Inter", "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif',
      // Headings tighten as they grow. Large text set at default tracking is
      // the single most reliable way to make an interface look untended.
      h4: { fontWeight: 700, letterSpacing: '-0.02em' },
      h5: { fontWeight: 700, letterSpacing: '-0.018em' },
      h6: { fontWeight: 650, letterSpacing: '-0.012em' },
      subtitle1: { fontWeight: 600, letterSpacing: '-0.006em' },
      subtitle2: { fontWeight: 600 },
      body2: { lineHeight: 1.55 },
      button: { fontWeight: 600, letterSpacing: 0 },
      // Used for the small uppercase group labels in the nav and on detail
      // panels. Defined here so they are consistent rather than re-derived.
      overline: {
        fontWeight: 700,
        fontSize: '0.6875rem',
        letterSpacing: '0.07em',
        lineHeight: 1.6,
      },
    },

    components: {
      MuiCssBaseline: {
        styleOverrides: {
          // Scrollbars are part of the chrome on every dense screen in this
          // app, and the platform default is a wide grey slab.
          '*::-webkit-scrollbar': { width: 10, height: 10 },
          '*::-webkit-scrollbar-thumb': {
            backgroundColor: t.scrollThumb,
            borderRadius: 8,
            border: '2px solid transparent',
            backgroundClip: 'content-box',
          },
          '*::-webkit-scrollbar-thumb:hover': { backgroundColor: t.scrollThumbHover },
          '*::-webkit-scrollbar-track': { backgroundColor: 'transparent' },
          body: { WebkitFontSmoothing: 'antialiased', MozOsxFontSmoothing: 'grayscale' },
          // Tells the browser to render its own widgets -- date pickers, native
          // selects, form controls -- to match. Without it a date field stays a
          // white box with black text in the middle of a dark form.
          ':root': { colorScheme: mode },
        },
      },

      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          // Touch-sized targets by default: review queues get worked through on
          // a phone while walking the warehouse (Frontend Design §8).
          root: { textTransform: 'none', minHeight: 40, borderRadius: 8, paddingInline: 14 },
          sizeSmall: { minHeight: 32, paddingInline: 10 },
          sizeLarge: { minHeight: 46 },
          containedPrimary: { boxShadow: 'none', '&:hover': { boxShadow: 'none' } },
          outlined: { borderColor: t.border },
        },
      },

      MuiIconButton: { styleOverrides: { root: { borderRadius: 8 } } },

      MuiTextField: { defaultProps: { size: 'small', fullWidth: true } },

      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            backgroundColor: t.paper,
            '& .MuiOutlinedInput-notchedOutline': { borderColor: t.border },
            '&:hover .MuiOutlinedInput-notchedOutline': {
              borderColor: dark ? '#3d454f' : NEUTRAL[400],
            },
            // A focus ring rather than a thicker border, so the field does not
            // change size by a pixel when it takes focus.
            '&.Mui-focused': {
              '& .MuiOutlinedInput-notchedOutline': { borderWidth: 1 },
              boxShadow: `0 0 0 3px ${alpha(primary, dark ? 0.28 : 0.14)}`,
            },
          },
        },
      },

      // Dropdowns open beneath the field they belong to. MUI's default is to
      // lay the menu over the input, centred on whichever option is selected --
      // which hides the very field you are choosing for, and reads as the page
      // having broken rather than as a list opening.
      MuiSelect: {
        defaultProps: {
          MenuProps: {
            anchorOrigin: { vertical: 'bottom', horizontal: 'left' },
            transformOrigin: { vertical: 'top', horizontal: 'left' },
            // Otherwise the menu is laid out from the selected item and still
            // creeps upward over the field on a long list.
            slotProps: { paper: { sx: { maxHeight: 360, mt: 0.5 } } },
          },
        },
      },

      MuiMenu: {
        styleOverrides: {
          paper: { borderRadius: 10, marginTop: 4 },
          list: { padding: 6 },
        },
      },
      MuiMenuItem: {
        styleOverrides: {
          root: {
            borderRadius: 6,
            minHeight: 38,
            '&.Mui-selected': { backgroundColor: alpha(primary, dark ? 0.2 : 0.1) },
            '&.Mui-selected:hover': { backgroundColor: alpha(primary, dark ? 0.26 : 0.14) },
          },
        },
      },

      MuiPaper: {
        styleOverrides: {
          rounded: { borderRadius: 12 },
          // MUI lightens dark-mode surfaces by elevation with a background
          // image. The tokens above already set each surface deliberately, and
          // letting both run means a dialog and a card at different elevations
          // drift to different greys for no reason anyone chose.
          root: { backgroundImage: 'none' },
        },
      },

      // Outlined stays the default so nothing that asks for it explicitly
      // changes shape -- but an outlined surface now carries a hairline border
      // and a whisper of shadow rather than a flat 1px grey box.
      MuiCard: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: {
          root: { borderRadius: 12, borderColor: t.border, backgroundImage: 'none' },
        },
      },
      MuiCardContent: { styleOverrides: { root: { '&:last-child': { paddingBottom: 20 } } } },

      MuiAppBar: {
        styleOverrides: {
          root: {
            // A light bar with a hairline under it, not a slab of brand colour.
            // The brand still leads on this screen -- through the logo, the
            // buttons and every accent -- but a full-width dark header is the
            // single strongest "built in 2015" signal an interface can send,
            // and it fights every uploaded logo that is not on a dark ground.
            backgroundColor: t.bar,
            backdropFilter: 'blur(8px)',
            color: t.textPrimary,
            boxShadow: 'none',
            borderBottom: `1px solid ${t.border}`,
          },
        },
      },

      MuiDrawer: {
        styleOverrides: {
          paper: { backgroundColor: t.paper, borderRight: `1px solid ${t.border}` },
        },
      },

      // The selected nav row is a tinted pill rather than a full-bleed grey
      // band, which is what makes a sidebar read as current rather than as a
      // table with one row highlighted.
      MuiListItemButton: {
        styleOverrides: {
          root: {
            borderRadius: 8,
            '&.Mui-selected': {
              backgroundColor: alpha(primary, dark ? 0.2 : 0.1),
              '&:hover': { backgroundColor: alpha(primary, dark ? 0.26 : 0.14) },
            },
          },
        },
      },
      MuiListItemIcon: { styleOverrides: { root: { color: t.textSecondary } } },

      MuiChip: {
        styleOverrides: {
          // Tonal rather than saturated. A status chip lives inside a dense
          // table row, and a solid block of signal colour there reads as an
          // alert rather than as an attribute of the row -- the eye goes to the
          // brightest thing on screen, which on an asset list should be the
          // asset, not the fact that it is "Available" like the other fifteen.
          //
          // Done here, in one place, rather than at 80-odd call sites: every
          // `<Chip color="success">` already written picks this up, and one
          // written tomorrow does too.
          root: ({ theme }) => ({
            fontWeight: 600,
            borderRadius: 7,
            ...Object.fromEntries(
              (['primary', 'secondary', 'success', 'warning', 'error', 'info'] as const).map(
                (tone) => [
                  `&.MuiChip-filled.MuiChip-color${tone[0].toUpperCase()}${tone.slice(1)}`,
                  {
                    backgroundColor: alpha(theme.palette[tone].main, dark ? 0.2 : 0.12),
                    color: theme.palette[tone].main,
                    '& .MuiChip-deleteIcon': { color: alpha(theme.palette[tone].main, 0.6) },
                  },
                ],
              ),
            ),
          }),
          sizeSmall: { height: 23 },
          colorDefault: {
            backgroundColor: dark ? '#262c35' : NEUTRAL[100],
            color: dark ? t.textSecondary : NEUTRAL[700],
          },
          outlined: { borderColor: t.border },
        },
      },

      MuiTableHead: {
        styleOverrides: {
          root: {
            '& .MuiTableCell-head': {
              backgroundColor: t.raised,
              color: t.textSecondary,
              fontWeight: 600,
              fontSize: '0.75rem',
              letterSpacing: '0.02em',
              textTransform: 'uppercase',
              whiteSpace: 'nowrap',
            },
          },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: { borderColor: t.border },
          // Taller rows than MUI's `small`, but no wider: Inter sets a little
          // wider than the system fallback the app was really rendering in
          // before, and paying for the extra height in horizontal padding as
          // well started wrapping serial numbers onto two lines.
          sizeSmall: { padding: '12px 12px' },
        },
      },
      MuiTableRow: {
        styleOverrides: {
          root: {
            '&:hover .MuiTableCell-root': { backgroundColor: t.hover },
            '&:last-child .MuiTableCell-root': { borderBottom: 'none' },
          },
        },
      },

      MuiTabs: {
        styleOverrides: {
          root: { minHeight: 44, borderBottom: `1px solid ${t.border}` },
          indicator: { height: 2.5, borderRadius: 2 },
        },
      },
      MuiTab: {
        styleOverrides: {
          root: {
            textTransform: 'none',
            fontWeight: 600,
            minHeight: 44,
            color: t.textSecondary,
            '&.Mui-selected': { color: t.textPrimary },
          },
        },
      },

      MuiDialog: { styleOverrides: { paper: { borderRadius: 14 } } },
      MuiDialogTitle: { styleOverrides: { root: { fontWeight: 650, letterSpacing: '-0.012em' } } },

      MuiAlert: {
        styleOverrides: {
          root: { borderRadius: 10 },
          standardInfo: { backgroundColor: t.alert.info.bg, color: t.alert.info.fg },
          standardSuccess: { backgroundColor: t.alert.success.bg, color: t.alert.success.fg },
          standardWarning: { backgroundColor: t.alert.warning.bg, color: t.alert.warning.fg },
          standardError: { backgroundColor: t.alert.error.bg, color: t.alert.error.fg },
        },
      },

      MuiTooltip: {
        styleOverrides: {
          tooltip: {
            backgroundColor: t.tooltip,
            fontSize: '0.75rem',
            fontWeight: 500,
            borderRadius: 6,
            padding: '6px 10px',
          },
          arrow: { color: t.tooltip },
        },
      },

      MuiLinearProgress: {
        styleOverrides: {
          root: { height: 6, borderRadius: 3, backgroundColor: dark ? '#262c35' : NEUTRAL[200] },
          bar: { borderRadius: 3 },
        },
      },

      MuiDivider: { styleOverrides: { root: { borderColor: t.border } } },

      MuiLink: {
        styleOverrides: {
          root: {
            color: t.link,
            textDecorationColor: alpha(t.link, 0.4),
            fontWeight: 500,
            '&:hover': { color: t.linkHover, textDecorationColor: 'currentColor' },
          },
        },
      },

      MuiToggleButton: {
        styleOverrides: {
          root: {
            textTransform: 'none',
            fontWeight: 600,
            borderColor: t.border,
            '&.Mui-selected': {
              backgroundColor: alpha(primary, dark ? 0.2 : 0.1),
              color: primary,
            },
          },
        },
      },
    },
  });
}

export { NEUTRAL };
