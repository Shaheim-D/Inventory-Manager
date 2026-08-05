import {
  Autocomplete,
  Box,
  Grid,
  MenuItem,
  TextField,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { Category, LifecycleState, Location } from '../../api/types';
import { locationOptions, locationOptionSx } from '../../components/locationTree';

const PO_STATUSES = [
  'DRAFT', 'SUBMITTED', 'APPROVED', 'ORDERED',
  'PARTIALLY_RECEIVED', 'RECEIVED', 'REJECTED', 'CANCELLED',
];

/**
 * The filter controls a report asks for, and only those.
 *
 * <p>Which ones to show is the server's answer, not this component's: each
 * report declares the filters it understands, so adding a report never means
 * editing this file. A control nobody's report uses is a control nobody sees.
 */
export function ReportFilters({
  accepts,
  value,
  onChange,
}: {
  accepts: string[];
  value: Record<string, unknown>;
  onChange: (filters: Record<string, unknown>) => void;
}) {
  const set = (key: string, next: unknown) => {
    const filters = { ...value };
    if (next == null || next === '' || (Array.isArray(next) && next.length === 0)) delete filters[key];
    else filters[key] = next;
    onChange(filters);
  };

  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
    enabled: accepts.includes('categoryIds'),
  });
  const locations = useQuery({
    queryKey: ['locations'],
    queryFn: () => api.get<Location[]>('/api/locations'),
    enabled: accepts.includes('locationIds'),
  });
  const states = useQuery({
    queryKey: ['lifecycle-states'],
    queryFn: () => api.get<LifecycleState[]>('/api/reference/lifecycle-states'),
    enabled: accepts.includes('lifecycleStateIds'),
  });

  const selectedIds = (key: string) => (Array.isArray(value[key]) ? (value[key] as number[]) : []);

  return (
    <Grid container spacing={2}>
      {accepts.includes('categoryIds') && (
        <Grid item xs={12} md={6}>
          <Autocomplete
            multiple
            options={categories.data ?? []}
            getOptionLabel={(option) => option.name}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            value={(categories.data ?? []).filter((c) => selectedIds('categoryIds').includes(c.id))}
            onChange={(_event, next) => set('categoryIds', next.map((c) => c.id))}
            renderInput={(params) => (
              <TextField {...params} label="Categories" placeholder="Every category" />
            )}
          />
        </Grid>
      )}

      {accepts.includes('locationIds') && (
        <Grid item xs={12} md={6}>
          <Autocomplete
            multiple
            options={locationOptions(locations.data).map((option) => option.location)}
            getOptionLabel={(option) => option.name}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            value={(locations.data ?? []).filter((l) => selectedIds('locationIds').includes(l.id))}
            onChange={(_event, next) => set('locationIds', next.map((l) => l.id))}
            renderOption={(props, option) => {
              const { key, ...rest } = props as typeof props & { key: string };
              // Indented and shaded by depth, the same way every other location
              // list in the application reads.
              const depth = locationOptions(locations.data)
                .find((entry) => entry.location.id === option.id)?.depth ?? 0;
              return (
                <li key={key} {...rest}>
                  <Box component="span" sx={{ ...locationOptionSx(depth), py: 0.25, width: '100%' }}>
                    {option.name}
                  </Box>
                </li>
              );
            }}
            renderInput={(params) => (
              <TextField
                {...params}
                label="Locations"
                placeholder="Everywhere"
                helperText="A site includes everything racked inside it."
              />
            )}
          />
        </Grid>
      )}

      {accepts.includes('lifecycleStateIds') && (
        <Grid item xs={12} md={6}>
          <Autocomplete
            multiple
            options={states.data ?? []}
            getOptionLabel={(option) => option.name}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            value={(states.data ?? []).filter((s) => selectedIds('lifecycleStateIds').includes(s.id))}
            onChange={(_event, next) => set('lifecycleStateIds', next.map((s) => s.id))}
            renderInput={(params) => (
              <TextField {...params} label="Lifecycle states" placeholder="Any state" />
            )}
          />
        </Grid>
      )}

      {accepts.includes('warrantyWithinDays') && (
        <Grid item xs={12} md={3}>
          <TextField
            select
            label="Expiring within"
            value={value.warrantyWithinDays ?? 90}
            onChange={(event) => set('warrantyWithinDays', Number(event.target.value))}
          >
            {[30, 60, 90, 180, 365].map((days) => (
              <MenuItem key={days} value={days}>{days} days</MenuItem>
            ))}
          </TextField>
        </Grid>
      )}

      {accepts.includes('overdueByDays') && (
        <Grid item xs={12} md={3}>
          <TextField
            label="Overdue by at least (days)"
            value={value.overdueByDays ?? ''}
            onChange={(event) => set('overdueByDays', event.target.value.replace(/[^0-9]/g, ''))}
            helperText="Blank for anything past its interval."
          />
        </Grid>
      )}

      {accepts.includes('status') && (
        <Grid item xs={12} md={3}>
          <TextField
            select
            label="Status"
            value={value.status ?? ''}
            onChange={(event) => set('status', event.target.value)}
          >
            <MenuItem value="">Any status</MenuItem>
            {PO_STATUSES.map((status) => (
              <MenuItem key={status} value={status}>
                {status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, ' ')}
              </MenuItem>
            ))}
          </TextField>
        </Grid>
      )}

      {accepts.includes('vendor') && (
        <Grid item xs={12} md={3}>
          <TextField
            label="Vendor contains"
            value={value.vendor ?? ''}
            onChange={(event) => set('vendor', event.target.value)}
          />
        </Grid>
      )}

      {accepts.includes('createdFrom') && (
        <Grid item xs={6} md={3}>
          <TextField
            label="Raised from"
            type="date"
            InputLabelProps={{ shrink: true }}
            value={value.createdFrom ?? ''}
            onChange={(event) => set('createdFrom', event.target.value)}
          />
        </Grid>
      )}
      {accepts.includes('createdTo') && (
        <Grid item xs={6} md={3}>
          <TextField
            label="Raised to"
            type="date"
            InputLabelProps={{ shrink: true }}
            value={value.createdTo ?? ''}
            onChange={(event) => set('createdTo', event.target.value)}
          />
        </Grid>
      )}

      {accepts.includes('purchasedFrom') && (
        <Grid item xs={6} md={3}>
          <TextField
            label="Purchased from"
            type="date"
            InputLabelProps={{ shrink: true }}
            value={value.purchasedFrom ?? ''}
            onChange={(event) => set('purchasedFrom', event.target.value)}
          />
        </Grid>
      )}
      {accepts.includes('purchasedTo') && (
        <Grid item xs={6} md={3}>
          <TextField
            label="Purchased to"
            type="date"
            InputLabelProps={{ shrink: true }}
            value={value.purchasedTo ?? ''}
            onChange={(event) => set('purchasedTo', event.target.value)}
          />
        </Grid>
      )}
    </Grid>
  );
}
