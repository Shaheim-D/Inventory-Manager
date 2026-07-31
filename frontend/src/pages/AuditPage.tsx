import { useState } from 'react';
import { MenuItem, Paper, Stack, TextField } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { AuditEvent, Page } from '../api/types';
import { EntityTable } from '../components/EntityTable';
import { PageHeader } from '../components/PageHeader';

const ENTITY_TYPES = ['ASSET', 'LOCATION', 'ASSET_CATEGORY', 'APP_USER', 'ROLE', 'BRANDING'];
const ACTIONS = ['CREATE', 'UPDATE', 'DELETE', 'LIFECYCLE_TRANSITION'];

export function AuditPage() {
  const [entityType, setEntityType] = useState('');
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (entityType) params.set('entityType', entityType);
  if (action) params.set('action', action);

  const events = useQuery({
    queryKey: ['audit', params.toString()],
    queryFn: () => api.get<Page<AuditEvent>>(`/api/audit?${params.toString()}`),
  });

  return (
    <>
      <PageHeader
        title="Audit history"
        subtitle="Every recorded state change, newest first. Rows are append-only and outlive the records they describe."
      />

      <Paper variant="outlined" sx={{ p: 2, mb: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField
            select
            label="Entity type"
            value={entityType}
            onChange={(event) => {
              setEntityType(event.target.value);
              setPage(0);
            }}
            sx={{ maxWidth: { sm: 260 } }}
          >
            <MenuItem value="">All types</MenuItem>
            {ENTITY_TYPES.map((type) => (
              <MenuItem key={type} value={type}>
                {type.replaceAll('_', ' ')}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Action"
            value={action}
            onChange={(event) => {
              setAction(event.target.value);
              setPage(0);
            }}
            sx={{ maxWidth: { sm: 260 } }}
          >
            <MenuItem value="">All actions</MenuItem>
            {ACTIONS.map((entry) => (
              <MenuItem key={entry} value={entry}>
                {entry.replaceAll('_', ' ')}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </Paper>

      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'When', render: (event: AuditEvent) => new Date(event.occurredAt).toLocaleString() },
            { header: 'Entity', render: (event: AuditEvent) => `${event.entityType} #${event.entityId}` },
            { header: 'Action', render: (event: AuditEvent) => event.action.replaceAll('_', ' ') },
            { header: 'Field', render: (event: AuditEvent) => event.fieldName ?? '—' },
            { header: 'From', render: (event: AuditEvent) => truncate(event.previousValue) },
            { header: 'To', render: (event: AuditEvent) => truncate(event.newValue) },
            { header: 'By', render: (event: AuditEvent) => event.username },
          ]}
          rows={events.data?.content ?? []}
          rowKey={(event) => event.id}
          loading={events.isLoading}
          emptyMessage="No matching audit events."
          cardTitle={(event) => `${event.entityType} #${event.entityId} · ${event.action}`}
          page={page}
          size={size}
          totalElements={events.data?.totalElements}
          onPageChange={setPage}
          onSizeChange={(next) => {
            setSize(next);
            setPage(0);
          }}
        />
      </Paper>
    </>
  );
}

function truncate(value: string | null): string {
  if (!value) return '—';
  return value.length > 60 ? `${value.slice(0, 60)}…` : value;
}
