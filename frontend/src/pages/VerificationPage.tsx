import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Box, Button, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Asset, Category, Page } from '../api/types';
import { EntityTable } from '../components/EntityTable';
import { PageHeader } from '../components/PageHeader';

/**
 * The inventory verification queue. Unlike a staging table, there is nothing
 * held in limbo here — the asset row already is the truth, and the only question
 * is whether a human has recently attested to it. So the queue is a computed
 * filter over assets whose category has a verification interval.
 *
 * Resolution never happens automatically: confirm, correct the quantity, or
 * dispose it through the normal lifecycle transition. Nothing here edits stock
 * on its own.
 */
export function VerificationPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [categoryId, setCategoryId] = useState('');

  const categories = useQuery({ queryKey: ['categories'], queryFn: () => api.get<Category[]>('/api/categories') });

  const trackedCategories = useMemo(
    () => (categories.data ?? []).filter((category) => category.verificationIntervalDays != null),
    [categories.data],
  );

  const params = new URLSearchParams({ size: '200', sort: 'lastVerifiedAt', direction: 'asc' });
  if (categoryId) params.set('categoryId', categoryId);

  const assets = useQuery({
    queryKey: ['verification-assets', params.toString()],
    queryFn: () => api.get<Page<Asset>>(`/api/assets?${params.toString()}`),
  });

  const confirm = useMutation({
    mutationFn: (id: number) => api.post(`/api/assets/${id}/confirm-inventory`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['verification-assets'] }),
  });

  const intervalByCategory = new Map(
    trackedCategories.map((category) => [category.id, category.verificationIntervalDays!]),
  );

  const overdue = (assets.data?.content ?? []).filter((asset) => {
    const interval = intervalByCategory.get(asset.categoryId);
    if (interval == null || !asset.lastVerifiedAt) return false;
    if (['Disposed', 'Retired'].includes(asset.lifecycleStateName)) return false;
    const days = (Date.now() - new Date(asset.lastVerifiedAt).getTime()) / 86_400_000;
    return days > interval;
  });

  return (
    <>
      <PageHeader
        title="Inventory verification"
        subtitle="Bulk stock whose recorded quantity has not been confirmed within its category's interval."
      />

      {trackedCategories.length === 0 && (
        <Alert severity="info" sx={{ mb: 2 }}>
          No category currently has a verification interval set. An administrator can turn one on per
          category under Categories &amp; Custom Fields.
        </Alert>
      )}

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <TextField
          select
          label="Category"
          value={categoryId}
          onChange={(event) => setCategoryId(event.target.value)}
          sx={{ maxWidth: 320 }}
        >
          <MenuItem value="">All tracked categories</MenuItem>
          {trackedCategories.map((category) => (
            <MenuItem key={category.id} value={String(category.id)}>
              {category.name}
            </MenuItem>
          ))}
        </TextField>
      </Paper>

      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'Asset', render: (asset: Asset) => asset.displayLabel },
            { header: 'Category', render: (asset: Asset) => asset.categoryName },
            { header: 'Location', render: (asset: Asset) => asset.locationName },
            { header: 'Quantity', align: 'right', render: (asset: Asset) => asset.quantity },
            {
              header: 'Last verified',
              align: 'right',
              render: (asset: Asset) => {
                const days = asset.lastVerifiedAt
                  ? Math.floor((Date.now() - new Date(asset.lastVerifiedAt).getTime()) / 86_400_000)
                  : null;
                const interval = intervalByCategory.get(asset.categoryId);
                if (days == null) return '—';
                // The interval belongs against the row it applies to, where it
                // explains why this asset is here, not in the filter dropdown.
                return (
                  <Box sx={{ textAlign: 'right' }}>
                    <Typography variant="body2">{days} days ago</Typography>
                    {interval != null && (
                      <Typography variant="caption" color="error.main">
                        {days - interval} days overdue · checked every {interval}
                      </Typography>
                    )}
                  </Box>
                );
              },
            },
          ]}
          rows={overdue}
          rowKey={(asset) => asset.id}
          loading={assets.isLoading}
          emptyMessage="Nothing is overdue for verification."
          cardTitle={(asset) => asset.displayLabel}
          rowActions={(asset) => (
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              <Button size="small" variant="outlined" onClick={() => confirm.mutate(asset.id)}>
                Still in inventory
              </Button>
              <Button size="small" onClick={() => navigate(`/assets/${asset.id}/edit`)}>
                Update quantity
              </Button>
              <Button size="small" onClick={() => navigate(`/assets/${asset.id}`)}>
                Mark as gone
              </Button>
            </Stack>
          )}
        />
      </Paper>
    </>
  );
}
