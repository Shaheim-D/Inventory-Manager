import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Chip, Grid, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Asset, Category, LifecycleState, Location, Page } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { EntityTable, type Column } from '../components/EntityTable';
import { locationOptions, locationOptionSx, locationPath } from '../components/locationTree';
import { useAuth } from '../auth/AuthContext';

/**
 * What is running out: warranties, support contracts, or both.
 *
 * <p>One screen for both because the question somebody arrives with is "what
 * do I need to renew", not "show me a warranty report" — an asset whose
 * warranty and support both lapse in March is one thing to deal with. The kind
 * filter is there for when the question narrows, which it does as soon as
 * somebody is actually raising a purchase order with one vendor.
 *
 * <p>Days remaining is computed here rather than returned by the server: it is
 * a function of today and a date the row already carries, and a number that
 * goes stale in an open tab is worse than one that does not exist.
 */

const WINDOWS = [30, 60, 90, 180, 365];

type Kind = 'EITHER' | 'WARRANTY' | 'SUPPORT';

export function ExpiringCoverPage() {
  const navigate = useNavigate();
  const { has } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();

  // Deep-linkable, because the dashboard card points here and somebody who
  // narrows it then sends the link expects the other person to see the same.
  const kind = ((searchParams.get('kind') as Kind) || 'EITHER') as Kind;
  const withinDays = Number(searchParams.get('withinDays') ?? 90);
  const categoryId = searchParams.get('categoryId') ?? '';
  const locationId = searchParams.get('locationId') ?? '';
  const lifecycleStateId = searchParams.get('lifecycleStateId') ?? '';

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(searchParams);
    if (value) next.set(key, value);
    else next.delete(key);
    setSearchParams(next, { replace: true });
    setPage(0);
  }

  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
  });
  const locations = useQuery({
    queryKey: ['locations'],
    queryFn: () => api.get<Location[]>('/api/locations'),
    enabled: has('location:read'),
  });
  const states = useQuery({
    queryKey: ['lifecycle-states'],
    queryFn: () => api.get<LifecycleState[]>('/api/reference/lifecycle-states'),
  });

  const params = new URLSearchParams({
    expiring: kind,
    withinDays: String(withinDays),
    page: String(page),
    size: String(size),
    sort: 'id',
    direction: 'desc',
  });
  if (categoryId) params.set('categoryId', categoryId);
  if (locationId) params.set('locationId', locationId);
  if (lifecycleStateId) params.set('lifecycleStateId', lifecycleStateId);

  const assets = useQuery({
    queryKey: ['assets', 'expiring', params.toString()],
    queryFn: () => api.get<Page<Asset>>(`/api/assets?${params}`),
  });

  const today = useMemo(() => new Date(new Date().toDateString()), []);

  function daysUntil(date: string | null) {
    if (!date) return null;
    const then = new Date(date + 'T00:00:00');
    return Math.round((then.getTime() - today.getTime()) / 86_400_000);
  }

  /** A date with how long is left, because the date alone makes you count. */
  function expiry(date: string | null, show: boolean) {
    if (!show || !date) return '—';
    const left = daysUntil(date);
    return (
      <Stack direction="row" spacing={1} alignItems="center">
        <span>{date}</span>
        {left !== null && (
          <Chip
            size="small"
            label={left <= 0 ? 'today' : `${left}d`}
            // Thirty days is the point at which a renewal stops being a
            // calendar entry and starts being a phone call.
            color={left <= 30 ? 'warning' : 'default'}
            variant={left <= 30 ? 'filled' : 'outlined'}
          />
        )}
      </Stack>
    );
  }

  const columns: Column<Asset>[] = [
    { key: 'name', header: 'Name', render: (asset) => asset.displayLabel ?? asset.name },
    { key: 'category', header: 'Category', render: (asset) => asset.categoryName, secondary: true },
    {
      key: 'location',
      header: 'Location',
      render: (asset) => locationPath(locations.data, asset.locationId) || asset.locationName,
      secondary: true,
    },
    {
      header: 'Warranty expires',
      // Hidden when the view is narrowed to support, because a column that is
      // all dashes is a column nobody asked for.
      render: (asset) => expiry(asset.warrantyExpiration, kind !== 'SUPPORT'),
    },
    {
      header: 'Support expires',
      render: (asset) => expiry(asset.supportExpiration, kind !== 'WARRANTY'),
    },
    {
      header: 'Support level',
      render: (asset) => asset.supportLevelName ?? '—',
      secondary: true,
    },
    {
      header: 'Renew with',
      // The support provider when there is one, else the vendor it was bought
      // from -- which is who you would ring about a warranty.
      render: (asset) => asset.supportProvider ?? asset.vendor ?? '—',
      secondary: true,
    },
  ];

  return (
    <>
      <PageHeader
        title="Expiring cover"
        subtitle={`Warranties and support contracts running out within ${withinDays} days`}
        help="Everything whose cover lapses inside the window, newest first. Narrow it to one kind when you are raising a renewal with a single vendor. Anything that has already lapsed is not here — this is what is about to go, not what has gone."
      />

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} sm={6} md={3}>
            <TextField
              select
              label="Cover"
              value={kind}
              onChange={(event) => setFilter('kind', event.target.value)}
            >
              <MenuItem value="EITHER">Warranty or support</MenuItem>
              <MenuItem value="WARRANTY">Warranty only</MenuItem>
              <MenuItem value="SUPPORT">Support only</MenuItem>
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6} md={2}>
            <TextField
              select
              label="Expiring within"
              value={withinDays}
              onChange={(event) => setFilter('withinDays', event.target.value)}
            >
              {WINDOWS.map((days) => (
                <MenuItem key={days} value={days}>
                  {days} days
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6} md={3}>
            <TextField
              select
              label="Category"
              value={categoryId}
              onChange={(event) => setFilter('categoryId', event.target.value)}
            >
              <MenuItem value="">All categories</MenuItem>
              {(categories.data ?? []).map((category) => (
                <MenuItem key={category.id} value={String(category.id)}>
                  {category.name}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6} md={2}>
            <TextField
              select
              label="Location"
              value={locationId}
              disabled={!has('location:read')}
              onChange={(event) => setFilter('locationId', event.target.value)}
            >
              <MenuItem value="">All locations</MenuItem>
              {locationOptions(locations.data).map((option) => (
                <MenuItem key={option.location.id} value={String(option.location.id)}
                          sx={locationOptionSx(option.depth)}>
                  {option.location.name}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={6} md={2}>
            <TextField
              select
              label="Lifecycle state"
              value={lifecycleStateId}
              onChange={(event) => setFilter('lifecycleStateId', event.target.value)}
            >
              <MenuItem value="">All states</MenuItem>
              {(states.data ?? []).map((state) => (
                <MenuItem key={state.id} value={String(state.id)}>
                  {state.name}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
        </Grid>
      </Paper>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {assets.data ? `${assets.data.totalElements} expiring` : 'Loading…'}
      </Typography>

      <EntityTable
        columns={columns}
        rows={assets.data?.content ?? []}
        rowKey={(asset) => asset.id}
        loading={assets.isLoading}
        emptyMessage="Nothing is running out inside this window. Widen it, or clear the filters."
        onRowClick={(asset) => navigate(`/assets/${asset.id}`)}
        page={assets.data?.page ?? 0}
        size={assets.data?.size ?? size}
        totalElements={assets.data?.totalElements ?? 0}
        onPageChange={setPage}
        onSizeChange={(next) => {
          setSize(next);
          setPage(0);
        }}
      />
    </>
  );
}
