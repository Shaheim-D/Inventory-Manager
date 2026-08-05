import { alpha, createTheme, type Theme } from '@mui/material/styles';

/**
 * The design system, in one place.
 *
 * Everything visual that is not specific to a single screen lives here, so a
 * screen gets the current look by using ordinary MUI components rather than by
 * remembering a convention. That is deliberate: the alternative is forty files
 * each carrying their own `sx`, and a redesign that has to visit all of them.
 *
 * The one hard constraint is that this has to stay **palette-agnostic**.
 * Branding is uploadable — an installation picks its own primary and secondary
 * colours — so nothing here may assume the brand is dark, light, or any
 * particular hue. Brand colour is used for accent, emphasis and state; the
 * frame around it is a fixed neutral scale that reads the same whatever the
 * organisation chooses.
 */

/** Neutral scale. Slightly cool, and picked for contrast rather than by eye. */
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
 * Links are hyperlink blue rather than the brand colour, deliberately.
 *
 * MUI points links at `primary.main`, and this platform's default primary is a
 * near-black navy — which renders a link as bold text and nothing else. Blue is
 * what people have been taught to click for thirty years, and an installation
 * that brands itself dark green or maroon should not lose that signal. So links
 * keep their own colour and do not follow the palette.
 */
const LINK_BLUE = '#1565c0';

/**
 * A soft, close-range shadow scale.
 *
 * MUI's default shadows are the 2014 Material spec: dark, wide, and dramatic at
 * low elevations. Modern interface chrome wants the opposite — a shadow that
 * reads as "this surface is slightly above that one" rather than as a drop
 * shadow you notice. Two layers each: a tight contact shadow and a wider
 * ambient one.
 */
function softShadows(): Theme['shadows'] {
  const ring = '0 0 0 1px rgba(16, 24, 40, 0.04)';
  const scale = [
    'none',
    `${ring}, 0 1px 2px rgba(16, 24, 40, 0.06)`,
    `${ring}, 0 1px 3px rgba(16, 24, 40, 0.08), 0 1px 2px rgba(16, 24, 40, 0.04)`,
    `${ring}, 0 4px 8px -2px rgba(16, 24, 40, 0.08), 0 2px 4px -2px rgba(16, 24, 40, 0.04)`,
    `${ring}, 0 8px 16px -4px rgba(16, 24, 40, 0.10), 0 3px 6px -3px rgba(16, 24, 40, 0.05)`,
    `${ring}, 0 12px 20px -6px rgba(16, 24, 40, 0.12), 0 4px 8px -4px rgba(16, 24, 40, 0.06)`,
    `${ring}, 0 16px 28px -8px rgba(16, 24, 40, 0.14), 0 6px 10px -6px rgba(16, 24, 40, 0.06)`,
    `${ring}, 0 20px 36px -10px rgba(16, 24, 40, 0.16), 0 8px 12px -8px rgba(16, 24, 40, 0.07)`,
  ];
  // MUI requires exactly 25 entries; the top of the range is rarely used, so it
  // holds at the deepest defined step rather than inventing twelve more.
  while (scale.length < 25) scale.push(scale[scale.length - 1]);
  return scale as unknown as Theme['shadows'];
}

export function createAppTheme(primaryColor?: string | null, secondaryColor?: string | null): Theme {
  const primary = primaryColor || DEFAULT_PRIMARY;
  const secondary = secondaryColor || DEFAULT_SECONDARY;

  return createTheme({
    palette: {
      primary: { main: primary },
      secondary: { main: secondary },
      background: { default: NEUTRAL[50], paper: '#ffffff' },
      text: { primary: NEUTRAL[900], secondary: NEUTRAL[600], disabled: NEUTRAL[400] },
      divider: NEUTRAL[200],
      grey: NEUTRAL,
      success: { main: '#12805c' },
      warning: { main: '#b54708' },
      error: { main: '#b42318' },
      info: { main: '#175cd3' },
    },

    shape: { borderRadius: 10 },
    shadows: softShadows(),

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
            backgroundColor: NEUTRAL[300],
            borderRadius: 8,
            border: '2px solid transparent',
            backgroundClip: 'content-box',
          },
          '*::-webkit-scrollbar-thumb:hover': { backgroundColor: NEUTRAL[400] },
          '*::-webkit-scrollbar-track': { backgroundColor: 'transparent' },
          body: { WebkitFontSmoothing: 'antialiased', MozOsxFontSmoothing: 'grayscale' },
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
          containedPrimary: {
            boxShadow: 'none',
            '&:hover': { boxShadow: 'none' },
          },
          outlined: { borderColor: NEUTRAL[300] },
        },
      },

      MuiIconButton: { styleOverrides: { root: { borderRadius: 8 } } },

      MuiTextField: { defaultProps: { size: 'small', fullWidth: true } },

      MuiOutlinedInput: {
        styleOverrides: {
          root: {
            backgroundColor: '#ffffff',
            '& .MuiOutlinedInput-notchedOutline': { borderColor: NEUTRAL[300] },
            '&:hover .MuiOutlinedInput-notchedOutline': { borderColor: NEUTRAL[400] },
            // A focus ring rather than a thicker border, so the field does not
            // change size by a pixel when it takes focus.
            '&.Mui-focused': {
              '& .MuiOutlinedInput-notchedOutline': { borderWidth: 1 },
              boxShadow: `0 0 0 3px ${alpha(primary, 0.14)}`,
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
            '&.Mui-selected': { backgroundColor: alpha(primary, 0.1) },
            '&.Mui-selected:hover': { backgroundColor: alpha(primary, 0.14) },
          },
        },
      },

      MuiPaper: { styleOverrides: { rounded: { borderRadius: 12 } } },

      // Outlined stays the default so nothing that asks for it explicitly
      // changes shape -- but an outlined surface now carries a hairline border
      // and a whisper of shadow rather than a flat 1px grey box.
      MuiCard: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: {
          root: {
            borderRadius: 12,
            borderColor: NEUTRAL[200],
            backgroundImage: 'none',
          },
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
            backgroundColor: alpha('#ffffff', 0.86),
            backdropFilter: 'blur(8px)',
            color: NEUTRAL[900],
            boxShadow: 'none',
            borderBottom: `1px solid ${NEUTRAL[200]}`,
          },
        },
      },

      MuiDrawer: {
        styleOverrides: {
          paper: { backgroundColor: '#ffffff', borderRight: `1px solid ${NEUTRAL[200]}` },
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
              backgroundColor: alpha(primary, 0.1),
              '&:hover': { backgroundColor: alpha(primary, 0.14) },
            },
          },
        },
      },
      MuiListItemIcon: { styleOverrides: { root: { color: NEUTRAL[500] } } },

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
                    backgroundColor: alpha(theme.palette[tone].main, 0.12),
                    color: theme.palette[tone].main,
                    '& .MuiChip-deleteIcon': { color: alpha(theme.palette[tone].main, 0.6) },
                  },
                ],
              ),
            ),
          }),
          sizeSmall: { height: 23 },
          colorDefault: { backgroundColor: NEUTRAL[100], color: NEUTRAL[700] },
          outlined: { borderColor: NEUTRAL[300] },
        },
      },

      MuiTableHead: {
        styleOverrides: {
          root: {
            '& .MuiTableCell-head': {
              backgroundColor: NEUTRAL[50],
              color: NEUTRAL[600],
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
          root: { borderColor: NEUTRAL[200] },
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
            '&:hover .MuiTableCell-root': { backgroundColor: NEUTRAL[25] },
            '&:last-child .MuiTableCell-root': { borderBottom: 'none' },
          },
        },
      },

      MuiTabs: {
        styleOverrides: {
          root: { minHeight: 44, borderBottom: `1px solid ${NEUTRAL[200]}` },
          indicator: { height: 2.5, borderRadius: 2 },
        },
      },
      MuiTab: {
        styleOverrides: {
          root: {
            textTransform: 'none',
            fontWeight: 600,
            minHeight: 44,
            color: NEUTRAL[600],
            '&.Mui-selected': { color: NEUTRAL[900] },
          },
        },
      },

      MuiDialog: { styleOverrides: { paper: { borderRadius: 14 } } },
      MuiDialogTitle: { styleOverrides: { root: { fontWeight: 650, letterSpacing: '-0.012em' } } },

      MuiAlert: {
        styleOverrides: {
          root: { borderRadius: 10 },
          standardInfo: { backgroundColor: '#eff8ff', color: '#175cd3' },
          standardSuccess: { backgroundColor: '#ecfdf3', color: '#05603a' },
          standardWarning: { backgroundColor: '#fffaeb', color: '#b54708' },
          standardError: { backgroundColor: '#fef3f2', color: '#b42318' },
        },
      },

      MuiTooltip: {
        styleOverrides: {
          tooltip: {
            backgroundColor: NEUTRAL[800],
            fontSize: '0.75rem',
            fontWeight: 500,
            borderRadius: 6,
            padding: '6px 10px',
          },
          arrow: { color: NEUTRAL[800] },
        },
      },

      MuiLinearProgress: {
        styleOverrides: {
          root: { height: 6, borderRadius: 3, backgroundColor: NEUTRAL[200] },
          bar: { borderRadius: 3 },
        },
      },

      MuiDivider: { styleOverrides: { root: { borderColor: NEUTRAL[200] } } },

      MuiLink: {
        styleOverrides: {
          root: {
            color: LINK_BLUE,
            textDecorationColor: alpha(LINK_BLUE, 0.4),
            fontWeight: 500,
            '&:hover': { color: '#0d47a1', textDecorationColor: 'currentColor' },
          },
        },
      },

      MuiToggleButton: {
        styleOverrides: {
          root: {
            textTransform: 'none',
            fontWeight: 600,
            borderColor: NEUTRAL[300],
            '&.Mui-selected': { backgroundColor: alpha(primary, 0.1), color: primary },
          },
        },
      },
    },
  });
}

export { NEUTRAL };
