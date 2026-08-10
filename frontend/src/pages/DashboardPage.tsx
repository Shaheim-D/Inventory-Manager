import { useState } from 'react';
import { Box, Button, Card, CardContent, Grid, Stack, Typography } from '@mui/material';
import { alpha } from '@mui/material/styles';
import InventoryIcon from '@mui/icons-material/Inventory2';
import ShieldIcon from '@mui/icons-material/GppMaybe';
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { PageHeader } from '../components/PageHeader';
import { AssetBrowser } from '../components/AssetBrowser';
import { ImportDialog } from '../components/ImportDialog';
import { useAuth } from '../auth/AuthContext';

interface DashboardSummary {
  totalAssets?: number;
  warrantyExpiringSoon?: number;
  activePurchaseOrders?: number;
}

/**
 * The home page: three figures, then the assets.
 *
 * These used to be two screens. The dashboard's breakdowns -- assets by
 * category, assets by lifecycle state, orders by status -- were a picture of
 * things the asset list already filters by, so the answer to every question
 * they raised was "go and look at the assets". Now they are the same screen,
 * and the figures at the top are the three that are not answerable by looking:
 * how much there is, what is about to run out of warranty, and what has been
 * agreed to but not arrived.
 *
 * Each figure is permission-gated server-side, so one the viewer may not see is
 * simply not in the response and not rendered. The asset browser below is gated
 * separately -- somebody with asset:read but no dashboard:view gets the assets
 * without the figures, which is the whole page for them.
 */
export function DashboardPage() {
  const navigate = useNavigate();
  const { has } = useAuth();
  const [importing, setImporting] = useState(false);

  const { data } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => api.get<DashboardSummary>('/api/dashboard'),
    // Not fetched at all without the permission, rather than fetched and 403'd.
    enabled: has('dashboard:view'),
  });

  const summary = data ?? {};

  return (
    <>
      <PageHeader
        title="Dashboard"
        help={
          <>
            Everything owned, in one place. The figures are the three questions the list
            below cannot answer by looking: how much there is, what is about to run out of
            warranty, and what has been approved but not yet arrived. Search and the filters
            work on the whole inventory, and a filtered view is a link you can paste to
            somebody.
          </>
        }
        actions={
          <Stack direction="row" spacing={1}>
            {/* Importing is loading assets, so it belongs where the assets are
                rather than behind a module of its own. */}
            {has('import:run') && (
              <Button variant="outlined" onClick={() => setImporting(true)}>
                Import
              </Button>
            )}
            {has('asset:write') && (
              <Button variant="contained" onClick={() => navigate('/assets/new')}>
                New asset
              </Button>
            )}
          </Stack>
        }
      />

      <ImportDialog open={importing} onClose={() => setImporting(false)} />

      <Grid container spacing={2} sx={{ mb: 3 }}>
        {summary.totalAssets !== undefined && (
          <Stat
            label="Assets tracked"
            value={summary.totalAssets}
            icon={<InventoryIcon />}
            tone="primary"
          />
        )}
        {summary.warrantyExpiringSoon !== undefined && (
          <Stat
            label="Warranties expiring within 90 days"
            value={summary.warrantyExpiringSoon}
            icon={<ShieldIcon />}
            // The only figure here that is a prompt to do something rather than
            // a fact, so it is the only one that carries a warning tone -- and
            // only while there is actually something expiring.
            tone={summary.warrantyExpiringSoon > 0 ? 'warning' : 'primary'}
          />
        )}
        {summary.activePurchaseOrders !== undefined && (
          <Stat
            label="Active purchase orders"
            value={summary.activePurchaseOrders}
            to="/purchase-orders"
            icon={<ShoppingCartIcon />}
            tone="primary"
          />
        )}
      </Grid>

      {/* The figures are one permission, the inventory is another. A Purchaser
          holds asset:read without dashboard:view, and this is their whole page. */}
      {has('asset:read') && <AssetBrowser />}
    </>
  );
}

function Stat({
  label,
  value,
  to,
  icon,
  tone,
}: {
  label: string;
  value: number;
  to?: string;
  icon: ReactNode;
  tone: 'primary' | 'warning';
}) {
  return (
    <Grid item xs={12} sm={6} md={4}>
      <Card
        {...(to
          ? {
              component: RouterLink,
              to,
              sx: {
                textDecoration: 'none',
                display: 'block',
                transition: (theme) => theme.transitions.create(['box-shadow', 'border-color']),
                '&:hover': { boxShadow: 3, borderColor: 'transparent' },
              },
            }
          : {})}
      >
        <CardContent>
          <Stack direction="row" spacing={2} alignItems="center">
            <Box
              sx={{
                width: 44,
                height: 44,
                borderRadius: 2,
                flexShrink: 0,
                display: 'grid',
                placeItems: 'center',
                color: `${tone}.main`,
                bgcolor: (theme) => alpha(theme.palette[tone].main, 0.1),
              }}
            >
              {icon}
            </Box>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="h4" sx={{ lineHeight: 1.1 }}>
                {value.toLocaleString()}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {label}
              </Typography>
            </Box>
          </Stack>
        </CardContent>
      </Card>
    </Grid>
  );
}
