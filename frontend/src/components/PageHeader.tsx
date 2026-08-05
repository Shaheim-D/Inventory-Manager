import { useState, type ReactNode } from 'react';
import { Box, ClickAwayListener, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import HelpOutlineIcon from '@mui/icons-material/HelpOutline';

/**
 * The top of every screen.
 *
 * One component rather than a convention, so the title size, the help affordance
 * and the gap before the content are the same everywhere without anyone having
 * to remember what they were.
 *
 * There are two different things a screen might want to say under its title,
 * and they are deliberately separate props:
 *
 * `help` is the standing explanation of what the screen is for. It goes behind
 * the question mark rather than on the page, because it is written for the
 * first visit and then read a hundred more times by people who already know.
 * A paragraph that never changes stops being read and starts being furniture —
 * it pushes the actual work down the page every single time.
 *
 * `subtitle` is what is true *right now*: a count, a status, the name of the
 * record being looked at. That has to stay visible. Hiding "16 matching assets"
 * behind a question mark would be hiding the answer, not the instructions.
 */
export function PageHeader({
  title,
  subtitle,
  help,
  actions,
}: {
  title: string;
  subtitle?: ReactNode;
  help?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <Stack
      direction={{ xs: 'column', sm: 'row' }}
      justifyContent="space-between"
      alignItems={{ xs: 'stretch', sm: 'flex-start' }}
      spacing={2}
      sx={{ mb: 3 }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Stack direction="row" alignItems="center" spacing={0.5}>
          <Typography variant="h5">{title}</Typography>
          {help && <PageHelp text={help} />}
        </Stack>
        {subtitle && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: '68ch' }}>
            {subtitle}
          </Typography>
        )}
      </Box>
      {actions && (
        <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
          {actions}
        </Stack>
      )}
    </Stack>
  );
}

/**
 * Opens on hover, and stays open when clicked.
 *
 * Both, because they are different intentions. Hovering is "remind me"; a
 * pointer passing over should show the text and take it away again without
 * anyone committing to anything. Clicking is "let me actually read this" —
 * and on a touch screen it is the only gesture available at all, since there
 * is no hover on a phone. So a click pins it open, and it stays pinned when
 * the pointer leaves until it is clicked again or something else is.
 */
function PageHelp({ text }: { text: ReactNode }) {
  const [pinned, setPinned] = useState(false);
  const [hovered, setHovered] = useState(false);

  return (
    <ClickAwayListener onClickAway={() => setPinned(false)}>
      <Tooltip
        open={pinned || hovered}
        title={text}
        placement="bottom-start"
        arrow
        // Fully controlled: MUI's own listeners would fight the pinning above,
        // and its touch handling wants a long press where a tap is expected.
        disableHoverListener
        disableFocusListener
        disableTouchListener
        slotProps={{
          tooltip: {
            sx: {
              maxWidth: 340,
              px: 1.5,
              py: 1.25,
              fontSize: '0.8125rem',
              fontWeight: 400,
              lineHeight: 1.55,
            },
          },
        }}
      >
        <IconButton
          size="small"
          aria-label={pinned ? 'Hide page description' : 'What is this page for?'}
          onClick={() => setPinned((open) => !open)}
          onMouseEnter={() => setHovered(true)}
          onMouseLeave={() => setHovered(false)}
          onFocus={() => setHovered(true)}
          onBlur={() => setHovered(false)}
          onKeyDown={(event) => {
            if (event.key === 'Escape') setPinned(false);
          }}
          sx={{
            color: pinned ? 'primary.main' : 'text.disabled',
            '&:hover': { color: pinned ? 'primary.main' : 'text.secondary' },
          }}
        >
          <HelpOutlineIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </ClickAwayListener>
  );
}
