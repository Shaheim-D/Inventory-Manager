import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Chip, Grid, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Asset, Category, LifecycleState, Location, Page } from '../api/types';
import { EntityTable, type Column } from '../components/EntityTable';
import { PageHeader } from '../components/PageHeader';
import { useAuth } from '../auth/AuthContext';

export function AssetListPage() {
  const navigate = useNavigate();
  const { has } = useAuth();

  const [q, setQ] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [lifecycleStateId, setLifecycleStateId] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sort, setSort] = useState('id');
  const [direction, setDirection] = useState<'asc' | 'desc'>('desc');

  // Enter and the button do the same thing; neither should be the only way in.
  function runSearch() {
    setSearchTerm(q);
    setPage(0);
  }

  const categories = useQuery({ queryKey: ['categories'], queryFn: () => api.get<Category[]>('/api/categories') });
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
    page: String(page),
    size: String(size),
    sort,
    direction,
  });
  if (searchTerm) params.set('q', searchTerm);
  if (categoryId) params.set('categoryId', categoryId);
  if (locationId) params.set('locationId', locationId);
  if (lifecycleStateId) params.set('lifecycleStateId', lifecycleStateId);

  const assets = useQuery({
    queryKey: ['assets', params.toString()],
    queryFn: () => api.get<Page<Asset>>(`/api/assets?${params.toString()}`),
  });

  const rows = assets.data?.content ?? [];

  // Cost columns are offered only when the server actually sent the field. The
  // UI reacts to what arrived; it never re-derives the restriction itself.
  const costVisible = rows.length > 0 && 'purchasePrice' in rows[0];

  const columns: Column<Asset>[] = [
    { key: 'name', header: 'Name', render: (asset) => asset.displayLabel },
    { key: 'assetTag', header: 'Asset Tag', render: (asset) => asset.assetTag ?? '—' },
    {
      header: 'Category',
      // The primary reads as the heading and the sub-categories sit under it, so
      // the column says what the thing is before it says where else it is filed.
      render: (asset) => (
        <Stack spacing={0.25}>
          <span>{asset.categoryName}</span>
          {asset.subcategories?.length > 0 && (
            <Typography variant="caption" color="text.secondary">
              {asset.subcategories.map((entry) => entry.name).join(', ')}
            </Typography>
          )}
        </Stack>
      ),
    },
    { header: 'Location', render: (asset) => asset.locationName },
    {
      header: 'Lifecycle',
      render: (asset) => <Chip size="small" label={asset.lifecycleStateName} variant="outlined" />,
    },
    { key: 'serialNumber', header: 'Serial Number', render: (asset) => asset.serialNumber ?? '—' },
    ...(costVisible
      ? [
          {
            header: 'Purchase Price',
            align: 'right' as const,
            render: (asset: Asset) =>
              asset.purchasePrice == null
                ? '—'
                : asset.purchasePrice.toLocaleString(undefined, { style: 'currency', currency: 'USD' }),
          },
        ]
      : []),
    {
      header: 'Quantity',
      align: 'right',
      secondary: true,
      render: (asset) => (asset.serialized ? '—' : asset.quantity),
    },
  ];

  return (
    <>
      <PageHeader
        title="Assets"
        subtitle={
          assets.data ? `${assets.data.totalElements.toLocaleString()} matching assets` : 'Loading…'
        }
        actions={
          has('asset:write') ? (
            <Button variant="contained" onClick={() => navigate('/assets/new')}>
              New asset
            </Button>
          ) : undefined
        }
      />

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={4}>
            <Stack direction="row" spacing={1}>
              <TextField
                label="Search"
                placeholder="Serial, tag, hostname, notes…"
                value={q}
                onChange={(event) => setQ(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') runSearch();
                }}
              />
              <Button variant="outlined" onClick={runSearch} sx={{ flexShrink: 0 }}>
                Search
              </Button>
            </Stack>
          </Grid>
          <Grid item xs={12} sm={4} md={2.5}>
            <TextField
              select
              label="Category"
              value={categoryId}
              onChange={(event) => {
                setCategoryId(event.target.value);
                setPage(0);
              }}
            >
              <MenuItem value="">All categories</MenuItem>
              {(categories.data ?? []).map((category) => (
                <MenuItem key={category.id} value={String(category.id)}>
                  {category.name}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={4} md={2.5}>
            <TextField
              select
              label="Location"
              value={locationId}
              onChange={(event) => {
                setLocationId(event.target.value);
                setPage(0);
              }}
              disabled={!has('location:read')}
            >
              <MenuItem value="">All locations</MenuItem>
              {(locations.data ?? []).map((location) => (
                <MenuItem key={location.id} value={String(location.id)}>
                  {location.name}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
          <Grid item xs={12} sm={4} md={3}>
            <TextField
              select
              label="Lifecycle state"
              value={lifecycleStateId}
              onChange={(event) => {
                setLifecycleStateId(event.target.value);
                setPage(0);
              }}
            >
              <MenuItem value="">Any state</MenuItem>
              {(states.data ?? []).map((state) => (
                <MenuItem key={state.id} value={String(state.id)}>
                  {state.name}
                </MenuItem>
              ))}
            </TextField>
          </Grid>
        </Grid>
      </Paper>

      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={rows}
          rowKey={(asset) => asset.id}
          loading={assets.isLoading}
          emptyMessage="No assets match these filters."
          onRowClick={(asset) => navigate(`/assets/${asset.id}`)}
          cardTitle={(asset) => asset.displayLabel}
          page={page}
          size={size}
          totalElements={assets.data?.totalElements}
          onPageChange={setPage}
          onSizeChange={(next) => {
            setSize(next);
            setPage(0);
          }}
          sort={sort}
          direction={direction}
          onSortChange={(nextSort, nextDirection) => {
            setSort(nextSort);
            setDirection(nextDirection);
          }}
        />
      </Paper>
    </>
  );
}
