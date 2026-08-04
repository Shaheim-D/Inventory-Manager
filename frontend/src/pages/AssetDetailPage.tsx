import { useState, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
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
  Link,
  MenuItem,
  Paper,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../api/client';
import type { Asset, AuditEvent, Page, TransitionOptions } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { EntityTable } from '../components/EntityTable';
import { RelationshipsSection } from '../components/RelationshipsSection';
import { AttachmentsTab } from '../components/AttachmentsTab';
import { useAuth } from '../auth/AuthContext';

export function AssetDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { has } = useAuth();
  // Keyed rather than indexed. Tabs are permission-gated, so a numeric index
  // silently means a different tab for a viewer who cannot see one of them --
  // hiding Audit History used to shift Lifecycle to 1 while the render still
  // checked for 2, leaving that viewer a tab that showed nothing.
  const [tab, setTab] = useState('overview');
  const [transitionTarget, setTransitionTarget] = useState<{ id: number; name: string } | null>(null);
  const [transitionReason, setTransitionReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  const asset = useQuery({
    queryKey: ['asset', id],
    queryFn: () => api.get<Asset>(`/api/assets/${id}`),
  });

  const transitions = useQuery({
    queryKey: ['asset-transitions', id],
    queryFn: () => api.get<TransitionOptions>(`/api/assets/${id}/transitions`),
    enabled: has('asset:write'),
  });

  const audit = useQuery({
    queryKey: ['asset-audit', id],
    queryFn: () => api.get<Page<AuditEvent>>(`/api/assets/${id}/audit?size=100`),
    enabled: has('audit:view') && tab === 'audit',
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

  // Both come from the server with the asset, so the detail page and the form
  // agree about what this category uses without either re-deriving it.
  const applicable = new Set(data.applicableCoreFields);
  const uses = (field: string) => applicable.has(field);
  const label = (field: string) => data.coreFieldLabels[field] ?? field;

  return (
    <>
      <PageHeader
        title={data.displayLabel}
        subtitle={
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.5 }} flexWrap="wrap" useFlexGap>
            <Chip size="small" label={data.categoryName} />
            {data.subcategories.map((sub) => (
              <Chip key={sub.id} size="small" variant="outlined" label={sub.name} />
            ))}
            <Chip size="small" color={lifecycleColor(data.lifecycleStateName)} label={data.lifecycleStateName} />
            <Typography variant="body2" color="text.secondary">
              <strong>Location:</strong> {data.locationName}
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
        <Tab value="overview" label="Overview" />
        <Tab value="attachments" label="Attachments" />
        {has('audit:view') && <Tab value="audit" label="Audit history" />}
        {has('asset:write') && <Tab value="lifecycle" label="Lifecycle" />}
      </Tabs>

      {tab === 'attachments' && (
        <AttachmentsTab
          basePath={`/api/assets/${id}/attachments`}
          queryKey={['asset-attachments', id]}
          invalidateKeys={[['asset-audit', id]]}
        />
      )}

      {tab === 'overview' && (
        <Paper variant="outlined" sx={{ p: { xs: 2, sm: 3 } }}>
          <Grid container spacing={3}>
            <Grid item xs={12} md={6}>
              {/* Only what this kind of thing actually uses. A vehicle has no
                  hostname, so showing an empty Hostname row would be noise. */}
              <Section title="Identity">
                <Field label="Name" value={data.name} />
                {uses('asset_tag') && <Field label={label('asset_tag')} value={data.assetTag} />}
                {uses('serial_number') && <Field label={label('serial_number')} value={data.serialNumber} />}
                {uses('hostname') && <Field label={label('hostname')} value={data.hostname} />}
                {uses('management_ip') && <Field label={label('management_ip')} value={data.managementIp} />}
                {uses('mac_addresses') && (
                  <Field label={label('mac_addresses')} value={data.macAddresses?.join(', ')} />
                )}
              </Section>

              <Section title="Hardware">
                {uses('manufacturer') && <Field label={label('manufacturer')} value={data.manufacturer} />}
                {uses('model') && <Field label={label('model')} value={data.model} />}
                {uses('firmware_version') && <Field label={label('firmware_version')} value={data.firmwareVersion} />}
                {uses('software_version') && <Field label={label('software_version')} value={data.softwareVersion} />}
                {uses('device_role') && <Field label={label('device_role')} value={data.deviceRole} />}
                {uses('condition') && <Field label={label('condition')} value={data.condition} />}
                {/* Shown for serialized assets too, where it is always 1. */}
                <Field label="Quantity on hand" value={String(data.quantity)} />
              </Section>
            </Grid>

            <Grid item xs={12} md={6}>
              {/*
                Restricted fields are absent from the payload, so they are absent
                here too — nothing renders a blank slot or a masked value.
              */}
              <Section title="Purchase & warranty">
                {uses('purchase_date') && <Field label={label('purchase_date')} value={data.purchaseDate} />}
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
                {uses('vendor') && <Field label={label('vendor')} value={data.vendor} />}
                {'invoiceNumber' in data && (
                  <Field
                    label={label('invoice_number')}
                    // A link only when this asset actually came from an order.
                    // The same field can be typed in by hand on an asset nobody
                    // bought through here, and a link to nowhere is worse than
                    // plain text.
                    value={
                      data.invoiceNumber && data.purchaseOrderId != null ? (
                        <Link
                          component={RouterLink}
                          to={`/purchase-orders/order/${data.purchaseOrderId}`}
                        >
                          {data.invoiceNumber}
                        </Link>
                      ) : (
                        data.invoiceNumber
                      )
                    }
                  />
                )}
                {'purchaseLink' in data && (
                  <Field
                    label={label('purchase_link')}
                    // A URL sitting as plain text is a URL somebody has to
                    // select and copy. Only http(s) is linked — the column is
                    // free text and could hold anything.
                    value={
                      /^https?:\/\//i.test(data.purchaseLink ?? '') ? (
                        <Link href={data.purchaseLink!} target="_blank" rel="noopener noreferrer">
                          {data.purchaseLink}
                        </Link>
                      ) : (
                        data.purchaseLink
                      )
                    }
                  />
                )}
                {uses('warranty_start') && <Field label={label('warranty_start')} value={data.warrantyStart} />}
                {uses('warranty_start') && data.warrantyTermMonths != null && (
                  <Field label="Warranty term" value={formatTerm(data.warrantyTermMonths)} />
                )}
                {uses('warranty_start') && (
                  <Field label="Warranty expires" value={data.warrantyExpiration} />
                )}
              </Section>

              <Section title="Custody">
                <Field label="Assignment" value={ASSIGNMENT_LABELS[data.assigneeType]} />
                {/* One resolved name, whether the assignee is a user account, a
                    named employee, or a customer. Previously a user assignment
                    showed nothing here, because the name lived behind an id. */}
                {'assigneeDisplay' in data && data.assigneeType !== 'NONE' && (
                  <Field
                    label={data.assigneeType === 'CUSTOMER' ? 'Customer' : 'Assigned to'}
                    value={data.assigneeDisplay}
                  />
                )}
                {uses('customer_name') && <Field label={label('customer_name')} value={data.customerName} />}
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

      {tab === 'overview' && (
        <Box sx={{ mt: 3 }}>
          <Typography variant="subtitle1" gutterBottom>
            Relationships
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            What this is physically part of, connected to, powered by, or held as a spare for. A
            link entered on either asset shows on both.
          </Typography>
          <RelationshipsSection assetId={id!} />
        </Box>
      )}

      {tab === 'audit' && has('audit:view') && (
        <Paper variant="outlined">
          <EntityTable
            columns={[
              { header: 'When', render: (event: AuditEvent) => formatDate(event.occurredAt) },
              { header: 'Action', render: (event: AuditEvent) => event.action },
              { header: 'Field', render: (event: AuditEvent) => event.fieldName ?? '—' },
              { header: 'From', render: (event: AuditEvent) => event.previousValue ?? '—' },
              { header: 'To', render: (event: AuditEvent) => event.newValue ?? '—' },
              { header: 'By', render: (event: AuditEvent) => event.username },
            ]}
            rows={audit.data?.content ?? []}
            rowKey={(event) => event.id}
            loading={audit.isLoading}
            emptyMessage="No recorded changes yet."
            cardTitle={(event) => `${event.action} · ${formatDate(event.occurredAt)}`}
          />
        </Paper>
      )}

      {tab === 'lifecycle' && has('asset:write') && (
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Typography variant="subtitle1" gutterBottom>
            Current state: {data.lifecycleStateName}
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            The category's lifecycle graph describes the usual path, and those steps are offered first.
            Any other state can still be chosen — equipment does skip steps, and recording what actually
            happened beats recording what should have. A skip is noted in the audit trail.
          </Typography>

          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 3 }}>
            {(transitions.data?.suggested ?? []).map((state) => (
              <Button key={state.id} variant="contained" onClick={() => setTransitionTarget(state)}>
                Move to {state.name}
              </Button>
            ))}
            {transitions.data?.suggested.length === 0 && (
              <Typography variant="body2" color="text.secondary">
                The graph has no onward step from here — use the dropdown below.
              </Typography>
            )}
          </Stack>

          <TextField
            select
            label="Move to any state"
            value=""
            onChange={(event) => {
              const chosen = transitions.data?.all.find((s) => s.id === Number(event.target.value));
              if (chosen) setTransitionTarget(chosen);
            }}
            sx={{ maxWidth: 360 }}
          >
            {(transitions.data?.all ?? []).map((state) => (
              <MenuItem key={state.id} value={state.id}>
                {state.name}
              </MenuItem>
            ))}
          </TextField>
        </Paper>
      )}

      <Dialog open={Boolean(transitionTarget)} onClose={() => setTransitionTarget(null)} fullWidth maxWidth="sm">
        <DialogTitle>
          Move to {transitionTarget?.name}
        </DialogTitle>
        <DialogContent>
          <TextField
            label="Notes (optional)"
            placeholder="Why it moved, who authorised it, anything worth knowing later"
            value={transitionReason}
            onChange={(event) => setTransitionReason(event.target.value)}
            multiline
            minRows={3}
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

function Field({ label, value }: { label: string; value?: ReactNode }) {
  const empty = value == null || value === '';
  return (
    <Box sx={{ display: 'flex', gap: 2 }}>
      <Typography variant="body2" color="text.secondary" sx={{ minWidth: 150 }}>
        {label}
      </Typography>
      <Typography variant="body2" component="div" sx={{ wordBreak: 'break-word' }}>
        {empty ? '—' : value}
      </Typography>
    </Box>
  );
}

/**
 * Reads the state at a glance: green while it is doing its job, amber while it
 * is on the way there or out of service, red at the end of its life.
 */
function lifecycleColor(
  state: string,
): 'default' | 'primary' | 'success' | 'warning' | 'error' | 'info' {
  switch (state) {
    case 'Ordered':
    case 'Received':
      return 'info';
    case 'Available':
      return 'success';
    case 'Reserved':
      return 'primary';
    case 'Installed':
    case 'Active':
      return 'success';
    case 'Repair':
    case 'Returned':
      return 'warning';
    case 'Retired':
    case 'Disposed':
      return 'error';
    default:
      return 'default';
  }
}

function formatDate(value: string | null): string {
  if (!value) return '—';
  return new Date(value).toLocaleString();
}


const ASSIGNMENT_LABELS: Record<string, string> = {
  NONE: 'Unassigned',
  USER: 'Assigned to an employee',
  EMPLOYEE: 'Assigned to an employee',
  CUSTOMER: 'Assigned to a customer',
};

function formatTerm(months: number): string {
  if (months % 12 === 0) {
    const years = months / 12;
    return `${years} year${years === 1 ? '' : 's'}`;
  }
  return `${months} month${months === 1 ? '' : 's'}`;
}
