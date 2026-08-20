import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, Button, Chip, Grid, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Asset, Category, LifecycleState, Location, Page } from '../api/types';
import { EntityTable, type Column } from './EntityTable';
import { BulkDeleteBar } from './BulkDeleteBar';
import { locationOptions, locationOptionSx, locationPath } from './locationTree';
import { useAuth } from '../auth/AuthContext';
import { money } from '../format';

/**
 * Finding an asset: search, the four filters, and the table.
 *
 * Lifted out of what used to be its own page so the home screen can carry it
 * whole. The dashboard used to be a bar chart of categories and lifecycle
 * states -- a picture of things this list already filters by, whose every
 * answer was "go and look at the assets". So the assets came to it instead.
 *
 * Everything that decides what is shown lives in the URL where it already did,
 * so a filtered list is still a link somebody can paste, and arriving from a
 * barcode scan or an order's "Show items" still works.
 */
export function AssetBrowser() {
  const navigate = useNavigate();
  const { has } = useAuth();
  const canDelete = has('asset:delete');
  const [searchParams, setSearchParams] = useSearchParams();

  // Arriving from an order's "Show items". It lives in the URL rather than in
  // state so the link is shareable and the back button behaves, and it is
  // deliberately not one of the dropdowns — it is a scope the page was opened
  // in, not a filter someone chose from the toolbar.
  const purchaseOrderId = searchParams.get('purchaseOrderId');

  // Seeded from the URL so a search is a shareable link, and so arriving here
  // from somewhere that already knows what to look for -- a barcode scan whose
  // tag matched nothing, say -- lands with the box filled in and run.
  const initialQuery = searchParams.get('q') ?? '';

  // Cleared whenever the filters move, because a selection that survives a
  // filter change is a selection of rows nobody can see any more -- and the
  // next click deletes them.
  const [selected, setSelected] = useState<Set<string | number>>(new Set());

  const [q, setQ] = useState(initialQuery);
  const [searchTerm, setSearchTerm] = useState(initialQuery);
  const [categoryId, setCategoryId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [lifecycleStateId, setLifecycleStateId] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sort, setSort] = useState('id');
  const [direction, setDirection] = useState<'asc' | 'desc'>('desc');

  // Arriving at /assets?q=… while already standing on /assets re-renders this
  // component rather than remounting it, so the initial state above never runs
  // again. Without this, scanning an unknown tag from the asset list and
  // choosing Search would change the URL and nothing else.
  //
  // Keyed on the string rather than the URLSearchParams object: that object is
  // a fresh instance on every render, so depending on it would reset the search
  // box on every keystroke.
  useEffect(() => {
    setQ(initialQuery);
    setSearchTerm(initialQuery);
    setPage(0);
  }, [initialQuery]);

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
  if (purchaseOrderId) params.set('purchaseOrderId', purchaseOrderId);

  const assets = useQuery({
    queryKey: ['assets', params.toString()],
    queryFn: () => api.get<Page<Asset>>(`/api/assets?${params.toString()}`),
  });

  const rows = assets.data?.content ?? [];

  // Cost columns are offered only when the server actually sent the field. The
  // UI reacts to what arrived; it never re-derives the restriction itself.
  const costVisible = rows.length > 0 && 'purchasePrice' in rows[0];

  const columns: Column<Asset>[] = [
    // secondary: the card heading below is already this value, and repeating
    // it as the first row of every card was pure noise on a phone.
    { key: 'name', header: 'Name', render: (asset) => asset.displayLabel, secondary: true },
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
    {
      // The path, not the leaf: a column of "Rack 4" three times over says
      // nothing about where the three things actually are.
      header: 'Location',
      render: (asset) => locationPath(locations.data, asset.locationId) || asset.locationName,
    },
    {
      header: 'Lifecycle',
      render: (asset) => <Chip size="small" label={asset.lifecycleStateName} variant="outlined" />,
    },
    { key: 'serialNumber', header: 'Serial Number', render: (asset) => asset.serialNumber ?? '—' },
    ...(costVisible
      ? [
          {
            header: 'Retail Price',
            align: 'right' as const,
            render: (asset: Asset) => money(asset.purchasePrice),
          },
        ]
      : []),
    {
      header: 'Quantity',
      align: 'right',
      secondary: true,
      // A serialized asset is one physical unit and the column says 1. A dash
      // read as "not known", which was wrong -- it is known, and it is one.
      render: (asset) => asset.quantity,
    },
  ];

  return (
    <>
      {purchaseOrderId && (
        <Alert
          severity="info"
          sx={{ mb: 2 }}
          action={
            <Stack direction="row" spacing={1}>
              <Button
                size="small"
                onClick={() => navigate(`/purchase-orders/order/${purchaseOrderId}`)}
              >
                Back to order
              </Button>
              <Button
                size="small"
                onClick={() => {
                  const next = new URLSearchParams(searchParams);
                  next.delete('purchaseOrderId');
                  setSearchParams(next);
                }}
              >
                Show all assets
              </Button>
            </Stack>
          }
        >
          Showing only what purchase order #{purchaseOrderId} delivered. Open one to fill in its
          serial number, asset tag and name.
        </Alert>
      )}

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {assets.data ? `${assets.data.totalElements.toLocaleString()} matching assets` : 'Loading…'}
      </Typography>

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
              SelectProps={{
                renderValue: (value) =>
                  value ? locationPath(locations.data, Number(value)) : 'All locations',
              }}
            >
              <MenuItem value="">All locations</MenuItem>
              {locationOptions(locations.data).map((option) => (
                <MenuItem
                  key={option.location.id}
                  value={String(option.location.id)}
                  sx={locationOptionSx(option.depth)}
                >
                  {option.location.name}
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

      {canDelete && (
        <BulkDeleteBar
          endpoint="/api/assets/bulk-delete"
          selected={selected}
          onClear={() => setSelected(new Set())}
          // The dashboard's "Assets tracked" figure is a separate query, and a
          // count that still says 5 after three went to the bin reads as a
          // failed delete.
          invalidate={[['assets'], ['dashboard'], ['recycle-bin', 'assets']]}
          noun="asset"
        />
      )}

      <Paper variant="outlined">
        <EntityTable
          columns={columns}
          rows={rows}
          rowKey={(asset) => asset.id}
          selectable={canDelete}
          selectedIds={selected}
          onSelectionChange={setSelected}
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
