import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Asset, AuditEvent, LifecycleState, Page } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { EntityTable } from '../components/EntityTable';
import { useAuth } from '../auth/AuthContext';

export function AssetDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { has } = useAuth();
  const [tab, setTab] = useState(0);
  const [transitionTarget, setTransitionTarget] = useState<LifecycleState | null>(null);
  const [transitionReason, setTransitionReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const asset = useQuery({
    queryKey: ['asset', id],
    queryFn: () => api.get<Asset>(`/api/assets/${id}`),
  });

  const transitions = useQuery({
    queryKey: ['asset-transitions', id],
    queryFn: () => api.get<LifecycleState[]>(`/api/assets/${id}/transitions`),
    enabled: has('asset:write'),
  });

  const audit = useQuery({
    queryKey: ['asset-audit', id],
    queryFn: () => api.get<Page<AuditEvent>>(`/api/assets/${id}/audit?size=100`),
    enabled: has('audit:view') && tab === 1,
  });

  const transition = useMutation({
    mutationFn: (payload: { toStateId: number; reason: string }) =>
      api.post<Asset>(`/api/assets/${id}/transitions`, payload),
    onSuccess: () => {
      setTransitionTarget(null);
      setTransitionReason('');
      setError(null);
      void queryClient.invalidateQueries({ queryKey: ['asset', id] });
      void queryClient.invalidateQueries({ queryKey: ['asset-transitions', id] });
      void queryClient.invalidateQueries({ queryKey: ['asset-audit', id] });
    },
    onError: (caught) => setError(caught instanceof ApiError ? caught.message : 'Transition failed.'),
  });

  const confirmInventory = useMutation({
    mutationFn: () => api.post<Asset>(`/api/assets/${id}/confirm-inventory`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['asset', id] });
      void queryClient.invalidateQueries({ queryKey: ['asset-audit', id] });
    },
  });

  if (asset.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }
  if (asset.isError || !asset.data) {
    return <Alert severity="error">That asset could not be loaded.</Alert>;
  }

  const data = asset.data;

  return (
    <>
      <PageHeader
        title={data.displayLabel}
        subtitle={
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.5 }}>
            <Chip size="small" label={data.categoryName} />
            <Chip size="small" variant="outlined" label={data.lifecycleStateName} />
            <Typography variant="body2" color="text.secondary">
              {data.locationName}
            </Typography>
          </Stack>
        }
        actions={
          <>
            {has('asset:write') && (
              <Button variant="outlined" onClick={() => navigate(`/assets/${data.id}/edit`)}>
                Edit
              </Button>
            )}
            {has('asset:write') && !data.serialized && (
              <Button variant="outlined" onClick={() => confirmInventory.mutate()}>
                Confirm still in inventory
              </Button>
            )}
          </>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Tabs value={tab} onChange={(_, next) => setTab(next)} sx={{ mb: 2 }} variant="scrollable" allowScrollButtonsMobile>
        <Tab label="Overview" />
        {has('audit:view') && <Tab label="Audit history" />}
        {has('asset:write') && <Tab label="Lifecycle" />}
      </Tabs>

      {tab === 0 && (
        <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
          <Grid container spacing={3}>
            <Grid item xs={12} md={6}>
              <Section title="Identity">
                <Field label="Name" value={data.name} />
                <Field label="Asset tag" value={data.assetTag} />
                <Field label="Serial number" value={data.serialNumber} />
                <Field label="Hostname" value={data.hostname} />
                <Field label="Management IP" value={data.managementIp} />
                <Field label="MAC addresses" value={data.macAddresses?.join(', ')} />
              </Section>

              <Section title="Hardware">
                <Field label="Manufacturer" value={data.manufacturer} />
                <Field label="Model" value={data.model} />
                <Field label="Firmware" value={data.firmwareVersion} />
                <Field label="Software" value={data.softwareVersion} />
                <Field label="Device role" value={data.deviceRole} />
                <Field label="Condition" value={data.condition} />
                {!data.serialized && <Field label="Quantity on hand" value={String(data.quantity)} />}
              </Section>
            </Grid>

            <Grid item xs={12} md={6}>
              {/*
                Restricted fields are absent from the payload, so they are absent
                here too — nothing renders a blank slot or a masked value.
              */}
              <Section title="Purchase & warranty">
                <Field label="Purchase date" value={data.purchaseDate} />
                {'purchasePrice' in data && (
                  <Field
                    label="Purchase price"
                    value={
                      data.purchasePrice == null
                        ? null
                        : data.purchasePrice.toLocaleString(undefined, { style: 'currency', currency: 'USD' })
                    }
                  />
                )}
                <Field label="Vendor" value={data.vendor} />
                {'invoiceNumber' in data && <Field label="Invoice number" value={data.invoiceNumber} />}
                {'purchaseLink' in data && <Field label="Purchase link" value={data.purchaseLink} />}
                <Field label="Warranty start" value={data.warrantyStart} />
                <Field label="Warranty expiration" value={data.warrantyExpiration} />
              </Section>

              <Section title="Custody">
                <Field label="Assignee type" value={data.assigneeType} />
                {'assigneeText' in data && <Field label="Assignee" value={data.assigneeText} />}
                {'assigneeUserId' in data && data.assigneeUserId != null && (
                  <Field label="Assigned user id" value={String(data.assigneeUserId)} />
                )}
                <Field label="Customer" value={data.customerName} />
                <Field label="Last verified" value={formatDate(data.lastVerifiedAt)} />
              </Section>

              {Object.keys(data.customFields).length > 0 && (
                <Section title={`${data.categoryName} fields`}>
                  {Object.entries(data.customFields).map(([key, value]) => (
                    <Field key={key} label={key} value={String(value)} />
                  ))}
                </Section>
              )}
            </Grid>

            {data.notes && (
              <Grid item xs={12}>
                <Divider sx={{ mb: 2 }} />
                <Typography variant="subtitle2" gutterBottom>
                  Notes
                </Typography>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
                  {data.notes}
                </Typography>
              </Grid>
            )}
          </Grid>
        </Paper>
      )}

      {tab === 1 && has('audit:view') && (
        <Paper variant="outlined">
          <EntityTable
            columns={[
              { header: 'When', render: (event: AuditEvent) => formatDate(event.occurredAt) },
              { header: 'Action', render: (event: AuditEvent) => event.action },
              { header: 'Field', render: (event: AuditEvent) => event.fieldName ?? '—' },
              { header: 'From', render: (event: AuditEvent) => event.previousValue ?? '—' },
              { header: 'To', render: (event: AuditEvent) => event.newValue ?? '—' },
              { header: 'By', secondary: true, render: (event: AuditEvent) => event.userId ?? 'system' },
            ]}
            rows={audit.data?.content ?? []}
            rowKey={(event) => event.id}
            loading={audit.isLoading}
            emptyMessage="No recorded changes yet."
            cardTitle={(event) => `${event.action} · ${formatDate(event.occurredAt)}`}
          />
        </Paper>
      )}

      {tab === 2 && has('asset:write') && (
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Typography variant="subtitle1" gutterBottom>
            Current state: {data.lifecycleStateName}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Only the transitions this category's lifecycle graph allows from here are offered. The graph is
            data an administrator edits — nothing about it is hardcoded.
          </Typography>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {(transitions.data ?? []).map((state) => (
              <Button key={state.id} variant="outlined" onClick={() => setTransitionTarget(state)}>
                Move to {state.name}
              </Button>
            ))}
            {transitions.data?.length === 0 && (
              <Typography variant="body2" color="text.secondary">
                This is a terminal state — no onward transitions are configured.
              </Typography>
            )}
          </Stack>
        </Paper>
      )}

      <Dialog open={Boolean(transitionTarget)} onClose={() => setTransitionTarget(null)} fullWidth maxWidth="sm">
        <DialogTitle>
          Move to {transitionTarget?.name}
        </DialogTitle>
        <DialogContent>
          <TextField
            label="Reason (optional)"
            value={transitionReason}
            onChange={(event) => setTransitionReason(event.target.value)}
            multiline
            minRows={2}
            sx={{ mt: 1 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTransitionTarget(null)}>Cancel</Button>
          <Button
            variant="contained"
            disabled={transition.isPending}
            onClick={() =>
              transitionTarget &&
              transition.mutate({ toStateId: transitionTarget.id, reason: transitionReason })
            }
          >
            Confirm
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Box sx={{ mb: 3 }}>
      <Typography variant="subtitle2" color="text.secondary" gutterBottom>
        {title}
      </Typography>
      <Stack spacing={0.5}>{children}</Stack>
    </Box>
  );
}

function Field({ label, value }: { label: string; value?: string | null }) {
  return (
    <Box sx={{ display: 'flex', gap: 2 }}>
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 150 }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
        {value == null || value === '' ? '—' : value}
      </Typography>
    </Box>
  );
}

function formatDate(value: string | null): string {
  if (!value) return '—';
  return new Date(value).toLocaleString();
}
