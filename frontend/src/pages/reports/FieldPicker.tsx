import { useMemo } from 'react';
import {
  Box,
  Button,
  Checkbox,
  Chip,
  FormControlLabel,
  LinearProgress,
  Stack,
  Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import type { ReportEntity, ReportFieldOption } from './reportTypes';

/**
 * Which columns the report should have.
 *
 * <p>The list comes from the server and is not filtered here. That matters: the
 * fields somebody may not see are never sent, so this component could not offer
 * one by mistake even if it tried. A picker that received everything and hid
 * some of it would put a security rule in the browser, where it does not belong.
 *
 * <p>Order is kept as chosen — a report's columns come out in the order somebody
 * ticked them, which is what a person building a vendor list expects.
 */
export function FieldPicker({
  entity,
  categoryIds,
  selected,
  onChange,
}: {
  entity: ReportEntity;
  categoryIds: number[];
  selected: string[];
  onChange: (fields: string[]) => void;
}) {
  const query = categoryIds.length > 0 ? `&categoryIds=${categoryIds.join(',')}` : '';
  const fields = useQuery({
    queryKey: ['report-fields', entity, categoryIds.join(',')],
    queryFn: () => api.get<ReportFieldOption[]>(`/api/reports/fields?entity=${entity}${query}`),
  });

  const groups = useMemo(() => {
    const byGroup = new Map<string, ReportFieldOption[]>();
    for (const field of fields.data ?? []) {
      byGroup.set(field.group, [...(byGroup.get(field.group) ?? []), field]);
    }
    return [...byGroup.entries()];
  }, [fields.data]);

  const toggle = (key: string) =>
    onChange(selected.includes(key) ? selected.filter((k) => k !== key) : [...selected, key]);

  if (fields.isLoading) return <LinearProgress />;

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Typography variant="body2" color="text.secondary">
          {selected.length === 0
            ? 'Pick at least one column.'
            : `${selected.length} column${selected.length === 1 ? '' : 's'}, in the order ticked.`}
        </Typography>
        <Box sx={{ flexGrow: 1 }} />
        {selected.length > 0 && <Button size="small" onClick={() => onChange([])}>Clear</Button>}
      </Stack>

      {selected.length > 0 && (
        <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
          {selected.map((key, index) => {
            const field = (fields.data ?? []).find((option) => option.key === key);
            return (
              <Chip
                key={key}
                size="small"
                label={`${index + 1}. ${field?.label ?? key}`}
                onDelete={() => toggle(key)}
              />
            );
          })}
        </Stack>
      )}

      {groups.map(([group, options]) => (
        <Box key={group}>
          <Typography variant="subtitle2" color="text.secondary" sx={{ mb: 0.5 }}>
            {group}
          </Typography>
          <Box
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: '1fr 1fr 1fr' },
            }}
          >
            {options.map((option) => (
              <FormControlLabel
                key={option.key}
                control={
                  <Checkbox
                    size="small"
                    checked={selected.includes(option.key)}
                    onChange={() => toggle(option.key)}
                  />
                }
                label={<Typography variant="body2">{option.label}</Typography>}
              />
            ))}
          </Box>
        </Box>
      ))}
    </Stack>
  );
}
