import { Box, Card, CardContent, Grid, LinearProgress, Stack, Typography } from '@mui/material';
import { alpha } from '@mui/material/styles';
import InventoryIcon from '@mui/icons-material/Inventory2';
import ShieldIcon from '@mui/icons-material/GppMaybe';
import HistoryIcon from '@mui/icons-material/History';
import { useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { api } from '../api/client';
import type { PurchaseOrderStatus } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { statusLabel } from './purchase-orders/shared';

interface Bucket {
  label: string;
  count: number;
}

interface DashboardSummary {
  totalAssets?: number;
  assetsByCategory?: Bucket[];
  assetsByLifecycleState?: Bucket[];
  warrantyExpiringSoon?: number;
  recentAuditCount?: number;
  purchaseOrdersByStatus?: Bucket[];
}

/**
 * Widgets are permission-gated server-side, so a widget the viewer may not see
 * is simply not in the response and not rendered — the same rule fields follow.
 */
export function DashboardPage() {
  const { data, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => api.get<DashboardSummary>('/api/dashboard'),
  });

  if (isLoading) return <LinearProgress />;
  const summary = data ?? {};

  return (
    <>
      <PageHeader title="Dashboard" />
      <Grid container spacing={2}>
        {summary.totalAssets !== undefined && (
          <Stat
            label="Assets tracked"
            value={summary.totalAssets}
            to="/assets"
            icon={<InventoryIcon />}
            tone="primary"
          />
        )}
        {summary.warrantyExpiringSoon !== undefined && (
          <Stat
            label="Warranties expiring within 90 days"
            value={summary.warrantyExpiringSoon}
            icon={<ShieldIcon />}
            // The only number here that is a prompt to do something rather than
            // a fact, so it is the only one that carries a warning tone -- and
            // only while there is actually something expiring.
            tone={summary.warrantyExpiringSoon > 0 ? 'warning' : 'primary'}
          />
        )}
        {summary.recentAuditCount !== undefined && (
          <Stat
            label="Recorded changes this week"
            value={summary.recentAuditCount}
            to="/audit"
            icon={<HistoryIcon />}
            tone="primary"
          />
        )}

        {summary.purchaseOrdersByStatus && summary.purchaseOrdersByStatus.length > 0 && (
          <Breakdown
            title="Purchase orders by status"
            // The server sends the stored enum; the human wording lives in one
            // place so the dashboard and the order screens never disagree.
            buckets={summary.purchaseOrdersByStatus.map((bucket) => ({
              ...bucket,
              label: statusLabel(bucket.label as PurchaseOrderStatus),
            }))}
          />
        )}
        {summary.assetsByCategory && (
          <Breakdown title="Assets by category" buckets={summary.assetsByCategory} />
        )}
        {summary.assetsByLifecycleState && (
          <Breakdown title="Assets by lifecycle state" buckets={summary.assetsByLifecycleState} />
        )}
      </Grid>
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

function Breakdown({ title, buckets }: { title: string; buckets: Bucket[] }) {
  const max = Math.max(1, ...buckets.map((bucket) => bucket.count));
  const total = buckets.reduce((sum, bucket) => sum + bucket.count, 0);
  return (
    <Grid item xs={12} md={6}>
      <Card sx={{ height: '100%' }}>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="baseline" sx={{ mb: 2 }}>
            <Typography variant="subtitle1">{title}</Typography>
            {total > 0 && (
              <Typography variant="caption" color="text.secondary">
                {total.toLocaleString()} total
              </Typography>
            )}
          </Stack>
          {buckets.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              Nothing recorded yet.
            </Typography>
          )}
          <Stack spacing={1.75}>
            {buckets.map((bucket) => (
              <Box key={bucket.label}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', gap: 2, mb: 0.75 }}>
                  <Typography variant="body2" noWrap sx={{ minWidth: 0 }}>
                    {bucket.label}
                  </Typography>
                  <Typography variant="body2" fontWeight={600}>
                    {bucket.count.toLocaleString()}
                  </Typography>
                </Box>
                <LinearProgress variant="determinate" value={(bucket.count / max) * 100} />
              </Box>
            ))}
          </Stack>
        </CardContent>
      </Card>
    </Grid>
  );
}
