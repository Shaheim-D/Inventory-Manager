import { Box, Card, CardContent, Grid, LinearProgress, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { api } from '../api/client';
import { PageHeader } from '../components/PageHeader';

interface Bucket {
  label: string;
  count: number;
}

interface DashboardSummary {
  totalAssets?: number;
  assetsByCategory?: Bucket[];
  assetsByLifecycleState?: Bucket[];
  staleAssetCount?: number;
  warrantyExpiringSoon?: number;
  recentAuditCount?: number;
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
          <Stat label="Assets tracked" value={summary.totalAssets} to="/assets" />
        )}
        {summary.warrantyExpiringSoon !== undefined && (
          <Stat label="Warranties expiring within 90 days" value={summary.warrantyExpiringSoon} />
        )}
        {summary.staleAssetCount !== undefined && (
          <Stat
            label="Bulk items due for verification"
            value={summary.staleAssetCount}
            to="/verification"
          />
        )}
        {summary.recentAuditCount !== undefined && (
          <Stat label="Recorded changes this week" value={summary.recentAuditCount} to="/audit" />
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

function Stat({ label, value, to }: { label: string; value: number; to?: string }) {
  return (
    <Grid item xs={12} sm={6} md={3}>
      <Card
        {...(to ? { component: RouterLink, to, sx: { textDecoration: 'none', display: 'block' } } : {})}
      >
        <CardContent>
          <Typography variant="h4">{value.toLocaleString()}</Typography>
          <Typography variant="body2" color="text.secondary">
            {label}
          </Typography>
        </CardContent>
      </Card>
    </Grid>
  );
}

function Breakdown({ title, buckets }: { title: string; buckets: Bucket[] }) {
  const max = Math.max(1, ...buckets.map((bucket) => bucket.count));
  return (
    <Grid item xs={12} md={6}>
      <Card>
        <CardContent>
          <Typography variant="subtitle1" gutterBottom>
            {title}
          </Typography>
          {buckets.length === 0 && (
            <Typography variant="body2" color="text.secondary">
              Nothing recorded yet.
            </Typography>
          )}
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            {buckets.map((bucket) => (
              <Box key={bucket.label}>
                <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                  <Typography variant="body2">{bucket.label}</Typography>
                  <Typography variant="body2" color="text.secondary">
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
