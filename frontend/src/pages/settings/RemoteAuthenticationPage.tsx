import { useState } from 'react';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Chip,
  Stack,
  Typography,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import { PageHeader } from '../../components/PageHeader';
import { RadiusPanel } from './RadiusPanel';
import { LdapPanel } from './LdapPanel';

/**
 * Signing in with network credentials — RADIUS and LDAP on one screen.
 *
 * <p>Two panes rather than two nav items, because they are two answers to one
 * question and an administrator setting this up is choosing between them (or
 * running both). Folded shut by default: this is configuration somebody sets up
 * once, and a page that opens with eighty fields showing is a page nobody reads.
 *
 * <p>Each pane's summary carries whether it is actually on, so the state of both
 * is legible without opening either.
 */
export function RemoteAuthenticationPage() {
  const [open, setOpen] = useState<'radius' | 'ldap' | false>(false);

  // Only for the status chips in the summaries. Each pane loads its own
  // settings; these are cheap and cached by the same keys.
  const radius = useQuery({
    queryKey: ['radius-settings'],
    queryFn: () => api.get<{ enabled: boolean }>('/api/admin/radius-settings'),
  });
  const ldap = useQuery({
    queryKey: ['ldap-settings'],
    queryFn: () => api.get<{ enabled: boolean }>('/api/admin/ldap-settings'),
  });

  return (
    <>
      <PageHeader
        title="Remote Authentication"
        help={
          <>
            Lets people sign in with their existing network credentials instead of a password set
            here. Both methods can be on at once, and neither replaces local sign-in — a password
            set in this application always keeps working, which is what stops a directory outage
            from locking everybody out.
          </>
        }
      />

      <Alert severity="info" sx={{ mb: 2 }}>
        <strong>Which one?</strong> RADIUS checks a password against NPS. LDAP checks the password{' '}
        <em>and</em> reads the person's Active Directory groups, so their role can follow their
        group membership — RADIUS carries no group information, so it cannot do that. Running both
        is fine.
      </Alert>

      <Accordion
        expanded={open === 'radius'}
        onChange={(_, isOpen) => setOpen(isOpen ? 'radius' : false)}
        variant="outlined"
      >
        <AccordionSummary expandIcon={<ExpandMoreIcon />}>
          <Stack direction="row" alignItems="center" spacing={1.5} sx={{ flex: 1 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
              RADIUS
            </Typography>
            <Status on={radius.data?.enabled} />
            <Typography variant="body2" color="text.secondary">
              Sign-in against NPS, with a primary and secondary server
            </Typography>
          </Stack>
        </AccordionSummary>
        <AccordionDetails>
          <RadiusPanel />
        </AccordionDetails>
      </Accordion>

      <Accordion
        expanded={open === 'ldap'}
        onChange={(_, isOpen) => setOpen(isOpen ? 'ldap' : false)}
        variant="outlined"
      >
        <AccordionSummary expandIcon={<ExpandMoreIcon />}>
          <Stack direction="row" alignItems="center" spacing={1.5} sx={{ flex: 1 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
              LDAP / Active Directory
            </Typography>
            <Status on={ldap.data?.enabled} />
            <Typography variant="body2" color="text.secondary">
              Sign-in plus roles from Active Directory groups
            </Typography>
          </Stack>
        </AccordionSummary>
        <AccordionDetails>
          <LdapPanel />
        </AccordionDetails>
      </Accordion>
    </>
  );
}

function Status({ on }: { on: boolean | undefined }) {
  if (on === undefined) return null;
  return on
    ? <Chip size="small" color="success" label="On" />
    : <Chip size="small" variant="outlined" label="Off" />;
}
