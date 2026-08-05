import { useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type { Category, CustomFieldDefinition, FieldVisibilityRule, Permission } from '../../api/types';
import { EntityTable } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';

/**
 * Field visibility rules, shown with the mechanism visible rather than hidden:
 * a core-field rule has to state explicitly whether it is global or scoped to
 * one category, because that distinction is the whole point of the V5 change.
 */
export function FieldVisibilityPage() {
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const rules = useQuery({
    queryKey: ['field-visibility-rules'],
    queryFn: () => api.get<FieldVisibilityRule[]>('/api/admin/field-visibility-rules'),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/admin/field-visibility-rules/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['field-visibility-rules'] }),
  });

  return (
    <>
      <PageHeader
        title="Field visibility rules"
        help="A field with no matching rule is visible to anyone with base read access. A field with one is absent entirely for anyone lacking the required permission."
        actions={
          <Button variant="contained" onClick={() => setCreating(true)}>
            New rule
          </Button>
        }
      />

      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'Entity', render: (rule: FieldVisibilityRule) => rule.entityType.replaceAll('_', ' ') },
            {
              header: 'Field',
              secondary: true,
              render: (rule: FieldVisibilityRule) => (
                <Typography variant="body2" fontFamily="monospace">
                  {rule.coreFieldName ?? rule.customFieldName}
                </Typography>
              ),
            },
            {
              header: 'Kind',
              render: (rule: FieldVisibilityRule) => (rule.coreFieldName ? 'Core column' : 'Custom field'),
            },
            {
              header: 'Scope',
              render: (rule: FieldVisibilityRule) =>
                rule.scope === 'GLOBAL' ? (
                  <Chip size="small" variant="outlined" label="Everywhere the field exists" />
                ) : (
                  <Chip size="small" color="primary" variant="outlined" label={rule.assetCategoryName ?? ''} />
                ),
            },
            {
              header: 'Requires',
              render: (rule: FieldVisibilityRule) => (
                <Typography variant="body2" fontFamily="monospace">
                  {rule.requiredPermissionKey}
                </Typography>
              ),
            },
          ]}
          rows={rules.data ?? []}
          rowKey={(rule) => rule.id}
          loading={rules.isLoading}
          cardTitle={(rule) => rule.coreFieldName ?? rule.customFieldName ?? ''}
          rowActions={(rule) => (
            <Button size="small" color="error" onClick={() => remove.mutate(rule.id)}>
              Remove
            </Button>
          )}
        />
      </Paper>

      {creating && (
        <CreateRuleDialog
          onClose={() => setCreating(false)}
          onError={setError}
          onCreated={() => {
            setCreating(false);
            void queryClient.invalidateQueries({ queryKey: ['field-visibility-rules'] });
          }}
        />
      )}
    </>
  );
}

function CreateRuleDialog({
  onClose,
  onCreated,
  onError,
}: {
  onClose: () => void;
  onCreated: () => void;
  onError: (message: string) => void;
}) {
  const [entityType, setEntityType] = useState<'ASSET' | 'PURCHASE_ORDER_LINE_ITEM'>('ASSET');
  const [kind, setKind] = useState<'CORE' | 'CUSTOM'>('CORE');
  const [coreFieldName, setCoreFieldName] = useState('');
  const [scopeCategoryId, setScopeCategoryId] = useState('');
  const [customCategoryId, setCustomCategoryId] = useState('');
  const [customFieldDefinitionId, setCustomFieldDefinitionId] = useState('');
  const [requiredPermissionId, setRequiredPermissionId] = useState('');
  const onAsset = entityType === 'ASSET';

  const permissions = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api.get<Permission[]>('/api/admin/permissions'),
  });
  const categories = useQuery({ queryKey: ['categories'], queryFn: () => api.get<Category[]>('/api/categories') });
  const coreFields = useQuery({
    queryKey: ['gateable-core-fields', entityType],
    queryFn: () =>
      api.get<string[]>(
        `/api/admin/field-visibility-rules/gateable-core-fields?entityType=${entityType}`,
      ),
  });
  const customFields = useQuery({
    queryKey: ['custom-fields-admin', customCategoryId],
    queryFn: () =>
      api.get<CustomFieldDefinition[]>(`/api/categories/${customCategoryId}/custom-fields?forAdministration=true`),
    enabled: kind === 'CUSTOM' && Boolean(customCategoryId),
  });

  const create = useMutation({
    mutationFn: () =>
      api.post('/api/admin/field-visibility-rules', {
        entityType,
        coreFieldName: kind === 'CORE' ? coreFieldName : null,
        customFieldDefinitionId: kind === 'CUSTOM' ? Number(customFieldDefinitionId) : null,
        requiredPermissionId: Number(requiredPermissionId),
        // Categories scope assets; a line item's price is gated globally.
        assetCategoryId:
          onAsset && kind === 'CORE' && scopeCategoryId ? Number(scopeCategoryId) : null,
      }),
    onSuccess: onCreated,
    onError: (caught) => onError(caught instanceof ApiError ? caught.message : 'Could not create the rule.'),
  });

  const ready =
    requiredPermissionId &&
    (kind === 'CORE' ? Boolean(coreFieldName) : Boolean(customFieldDefinitionId));

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>New field visibility rule</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {/* Purchase order lines are gated by the same mechanism as assets but
              by different code, so the field list depends on this. Without the
              choice, the seeded rule hiding unit prices could be deleted here
              and never recreated. */}
          <TextField
            select
            label="Where the field lives"
            value={entityType}
            onChange={(event) => {
              setEntityType(event.target.value as 'ASSET' | 'PURCHASE_ORDER_LINE_ITEM');
              setKind('CORE');
              setCoreFieldName('');
              setScopeCategoryId('');
            }}
          >
            <MenuItem value="ASSET">On an asset</MenuItem>
            <MenuItem value="PURCHASE_ORDER_LINE_ITEM">On a purchase order line</MenuItem>
          </TextField>

          {onAsset && (
            <TextField
              select
              label="What is being gated"
              value={kind}
              onChange={(event) => setKind(event.target.value as 'CORE' | 'CUSTOM')}
            >
              <MenuItem value="CORE">A core asset column</MenuItem>
              <MenuItem value="CUSTOM">A category's custom field</MenuItem>
            </TextField>
          )}

          {kind === 'CORE' ? (
            <>
              <TextField
                select
                label="Core field"
                value={coreFieldName}
                onChange={(event) => setCoreFieldName(event.target.value)}
              >
                {(coreFields.data ?? []).map((field) => (
                  <MenuItem key={field} value={field}>
                    {field}
                  </MenuItem>
                ))}
              </TextField>
              {onAsset && (
              <TextField
                select
                label="Scope"
                value={scopeCategoryId}
                onChange={(event) => setScopeCategoryId(event.target.value)}
                helperText="A core column exists on every asset, so a rule must say whether it is gated everywhere or only for one category."
              >
                <MenuItem value="">Global — everywhere this field exists</MenuItem>
                {(categories.data ?? []).map((category) => (
                  <MenuItem key={category.id} value={String(category.id)}>
                    {category.name} only
                  </MenuItem>
                ))}
              </TextField>
              )}
            </>
          ) : (
            <>
              <TextField
                select
                label="Category"
                value={customCategoryId}
                onChange={(event) => {
                  setCustomCategoryId(event.target.value);
                  setCustomFieldDefinitionId('');
                }}
              >
                {(categories.data ?? []).map((category) => (
                  <MenuItem key={category.id} value={String(category.id)}>
                    {category.name}
                  </MenuItem>
                ))}
              </TextField>
              <TextField
                select
                label="Custom field"
                value={customFieldDefinitionId}
                onChange={(event) => setCustomFieldDefinitionId(event.target.value)}
                disabled={!customCategoryId}
                helperText="Custom fields belong to one category already, so no separate scope is needed."
              >
                {(customFields.data ?? []).map((field) => (
                  <MenuItem key={field.id} value={String(field.id)}>
                    {field.fieldName}
                  </MenuItem>
                ))}
              </TextField>
            </>
          )}

          <TextField
            select
            label="Required permission"
            value={requiredPermissionId}
            onChange={(event) => setRequiredPermissionId(event.target.value)}
          >
            {(permissions.data ?? []).map((permission) => (
              <MenuItem key={permission.id} value={String(permission.id)}>
                {permission.permissionKey}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!ready || create.isPending} onClick={() => create.mutate()}>
          Create rule
        </Button>
      </DialogActions>
    </Dialog>
  );
}
