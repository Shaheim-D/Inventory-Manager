import { useState } from 'react';
import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  IconButton,
  ListSubheader,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';
import type {
  Category,
  NotificationFrequency,
  NotificationRuleView,
  NotificationTrigger,
  NotificationVocabulary,
  Role,
} from '../../api/types';
import { EntityTable } from '../../components/EntityTable';
import { PageHeader } from '../../components/PageHeader';
import {
  TRIGGER_GROUPS,
  frequencyLabel,
  triggerHasCategory,
  triggerLabel,
} from '../../components/notificationLabels';

interface TargetDraft {
  key: string;
  targetType: 'ROLE' | 'EMAIL';
  roleId: string;
  emailAddress: string;
}

const newTarget = (): TargetDraft => ({
  key: Math.random().toString(36).slice(2),
  targetType: 'ROLE',
  roleId: '',
  emailAddress: '',
});

/**
 * What the system notifies about, and who it tells (Phase 9 §4.11).
 *
 * <p>A role target is resolved to its members at send time, never snapshotted —
 * so the answer to "who gets warranty alerts" is "whoever is an Asset Manager
 * today", and nobody maintains a list. A fixed email address is offered
 * alongside it because a shared inbox or an external accountant is not a role,
 * which was the Phase 2 stakeholder decision.
 *
 * <p>Trigger and frequency are separate choices. What raises a notification and
 * how often you want to hear about it are unrelated questions: a warranty sweep
 * runs nightly but somebody may only want a weekly summary of it, and asset
 * creation happens all day but a daily digest of it is perfectly reasonable.
 * The in-app notice is never held back either way — frequency governs the email.
 */
export function NotificationRulesPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<NotificationRuleView | null>(null);
  const [creating, setCreating] = useState(false);
  const [banner, setBanner] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  const rules = useQuery({
    queryKey: ['notification-rules'],
    queryFn: () => api.get<NotificationRuleView[]>('/api/admin/notification-rules'),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api.del(`/api/admin/notification-rules/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['notification-rules'] }),
  });

  // Both sweeps can be run on demand, because a scheduled job nobody can
  // trigger is a scheduled job nobody can check. De-duplication makes running
  // either one twice harmless.
  const runSweep = useMutation({
    mutationFn: (path: 'run-warranty-check' | 'run-staleness-check') =>
      api.post<{ raised: number; message: string }>(`/api/admin/notification-rules/${path}`),
    onSuccess: (result) => {
      setBanner({ kind: 'success', text: result.message });
      void queryClient.invalidateQueries({ queryKey: ['notifications-unread'] });
    },
    onError: (caught) =>
      setBanner({
        kind: 'error',
        text: caught instanceof ApiError ? caught.message : 'Could not run the check.',
      }),
  });

  return (
    <>
      <PageHeader
        title="Notification rules"
        subtitle="What raises a notification, who is told, and how often the email goes out. Role targets are resolved when it sends, so nobody maintains a recipient list."
        actions={
          <Stack direction="row" spacing={1}>
            <Button onClick={() => runSweep.mutate('run-warranty-check')} disabled={runSweep.isPending}>
              Run warranty check now
            </Button>
            <Button onClick={() => runSweep.mutate('run-staleness-check')} disabled={runSweep.isPending}>
              Run verification check now
            </Button>
            <Button variant="contained" onClick={() => setCreating(true)}>
              New rule
            </Button>
          </Stack>
        }
      />

      {banner && (
        <Alert severity={banner.kind} sx={{ mb: 2 }} onClose={() => setBanner(null)}>
          {banner.text}
        </Alert>
      )}

      <Paper variant="outlined">
        <EntityTable
          columns={[
            { header: 'Rule', render: (rule: NotificationRuleView) => rule.name },
            {
              header: 'Raised by',
              render: (rule: NotificationRuleView) => triggerLabel(rule.triggerType),
            },
            {
              header: 'Email frequency',
              render: (rule: NotificationRuleView) => (
                <Chip
                  size="small"
                  variant="outlined"
                  color={rule.frequency === 'IMMEDIATE' ? 'primary' : 'default'}
                  label={frequencyLabel(rule.frequency)}
                />
              ),
            },
            {
              header: 'Category',
              render: (rule: NotificationRuleView) => rule.assetCategoryName ?? 'Every category',
            },
            {
              header: 'Recipients',
              render: (rule: NotificationRuleView) => (
                <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                  {rule.targets.map((target) => (
                    <Chip
                      key={target.id ?? target.emailAddress}
                      size="small"
                      variant="outlined"
                      color={target.targetType === 'ROLE' ? 'primary' : 'default'}
                      label={
                        target.targetType === 'ROLE'
                          ? `Everyone in ${target.roleName}`
                          : target.emailAddress
                      }
                    />
                  ))}
                </Stack>
              ),
            },
            {
              header: 'Active',
              render: (rule: NotificationRuleView) =>
                rule.active ? (
                  <Chip size="small" color="success" variant="outlined" label="On" />
                ) : (
                  <Chip size="small" variant="outlined" label="Off" />
                ),
            },
          ]}
          rows={rules.data ?? []}
          rowKey={(rule) => rule.id}
          loading={rules.isLoading}
          emptyMessage="No notification rules. Nothing will be raised until one exists."
          rowActions={(rule) => (
            <>
              <Button size="small" onClick={() => setEditing(rule)}>
                Edit
              </Button>
              <Button size="small" color="error" onClick={() => remove.mutate(rule.id)}>
                Delete
              </Button>
            </>
          )}
        />
      </Paper>

      {(creating || editing) && (
        <RuleDialog
          rule={editing}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSaved={() => {
            setCreating(false);
            setEditing(null);
            void queryClient.invalidateQueries({ queryKey: ['notification-rules'] });
          }}
        />
      )}
    </>
  );
}

function RuleDialog({
  rule,
  onClose,
  onSaved,
}: {
  rule: NotificationRuleView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(rule?.name ?? '');
  const [triggerType, setTriggerType] = useState<NotificationTrigger>(
    rule?.triggerType ?? 'PURCHASE_ORDER_SUBMITTED',
  );
  const [frequency, setFrequency] = useState<NotificationFrequency>(rule?.frequency ?? 'IMMEDIATE');
  const [categoryId, setCategoryId] = useState(
    rule?.assetCategoryId == null ? '' : String(rule.assetCategoryId),
  );
  const [active, setActive] = useState(rule?.active ?? true);
  const [targets, setTargets] = useState<TargetDraft[]>(
    rule && rule.targets.length > 0
      ? rule.targets.map((target) => ({
          key: String(target.id),
          targetType: target.targetType,
          roleId: target.roleId == null ? '' : String(target.roleId),
          emailAddress: target.emailAddress ?? '',
        }))
      : [newTarget()],
  );
  const [error, setError] = useState<string | null>(null);

  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api.get<Role[]>('/api/admin/roles') });
  const categories = useQuery({
    queryKey: ['categories'],
    queryFn: () => api.get<Category[]>('/api/categories'),
  });
  // The server owns both enums. Asking for them means a trigger added backend
  // side appears here without a second edit, and one removed stops being
  // offered instead of failing on save.
  const vocabulary = useQuery({
    queryKey: ['notification-vocabulary'],
    queryFn: () => api.get<NotificationVocabulary>('/api/admin/notification-rules/vocabulary'),
  });

  const known = new Set((vocabulary.data?.triggerTypes ?? []).map((entry) => entry.name));
  const grouped = TRIGGER_GROUPS.map((group) => ({
    heading: group.heading,
    triggers: group.triggers.filter((trigger) => known.size === 0 || known.has(trigger)),
  })).filter((group) => group.triggers.length > 0);
  // Anything the server offers that this build has no group for still needs to
  // be selectable, or an admin cannot use it at all.
  const ungrouped = [...known].filter(
    (trigger) => !TRIGGER_GROUPS.some((group) => group.triggers.includes(trigger)),
  );

  const scheduled =
    vocabulary.data?.triggerTypes.find((entry) => entry.name === triggerType)?.scheduled ?? false;
  const categoryApplies = triggerHasCategory(triggerType);

  const setTarget = (key: string, patch: Partial<TargetDraft>) =>
    setTargets((current) => current.map((t) => (t.key === key ? { ...t, ...patch } : t)));

  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        triggerType,
        frequency,
        // A category on a trigger that has none would stop the rule matching
        // anything, so it is dropped rather than carried over from whatever the
        // trigger was before it was changed.
        assetCategoryId: categoryApplies && categoryId ? Number(categoryId) : null,
        active,
        targets: targets
          .filter((t) => (t.targetType === 'ROLE' ? t.roleId : t.emailAddress.trim()))
          .map((t) => ({
            targetType: t.targetType,
            roleId: t.targetType === 'ROLE' ? Number(t.roleId) : null,
            emailAddress: t.targetType === 'EMAIL' ? t.emailAddress.trim() : null,
          })),
      };
      return rule
        ? api.put(`/api/admin/notification-rules/${rule.id}`, body)
        : api.post('/api/admin/notification-rules', body);
    },
    onSuccess: onSaved,
    onError: (caught) =>
      setError(caught instanceof ApiError ? caught.message : 'Could not save this rule.'),
  });

  const usable = targets.filter((t) => (t.targetType === 'ROLE' ? t.roleId : t.emailAddress.trim()));

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{rule ? 'Edit rule' : 'New notification rule'}</DialogTitle>
      <DialogContent>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Name" value={name} onChange={(event) => setName(event.target.value)} />

          <TextField
            select
            label="Raised by"
            value={triggerType}
            onChange={(event) => setTriggerType(event.target.value as NotificationTrigger)}
            helperText={
              scheduled
                ? 'A periodic sweep. It raises a notice the first time something crosses the line, not every time it runs.'
                : 'Raised the moment somebody does this.'
            }
          >
            {grouped.flatMap((group) => [
              <ListSubheader key={group.heading}>{group.heading}</ListSubheader>,
              ...group.triggers.map((trigger) => (
                <MenuItem key={trigger} value={trigger}>
                  {triggerLabel(trigger)}
                </MenuItem>
              )),
            ])}
            {ungrouped.map((trigger) => (
              <MenuItem key={trigger} value={trigger}>
                {triggerLabel(trigger)}
              </MenuItem>
            ))}
          </TextField>

          <TextField
            select
            label="Email frequency"
            value={frequency}
            onChange={(event) => setFrequency(event.target.value as NotificationFrequency)}
            helperText="How often the emails go out. The in-app notification always appears straight away — a summary only batches the email."
          >
            {(vocabulary.data?.frequencies ?? ['IMMEDIATE', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY']).map(
              (value) => (
                <MenuItem key={value} value={value}>
                  {frequencyLabel(value)}
                </MenuItem>
              ),
            )}
          </TextField>

          <TextField
            select
            label="Category"
            value={categoryApplies ? categoryId : ''}
            onChange={(event) => setCategoryId(event.target.value)}
            helperText={
              categoryApplies
                ? 'Narrows the rule to one category.'
                : 'This trigger is not about a category, so the rule covers all of them.'
            }
            disabled={!categoryApplies}
          >
            <MenuItem value="">Every category</MenuItem>
            {(categories.data ?? []).map((category) => (
              <MenuItem key={category.id} value={String(category.id)}>
                {category.name}
              </MenuItem>
            ))}
          </TextField>

          <FormControlLabel
            control={<Switch checked={active} onChange={(event) => setActive(event.target.checked)} />}
            label="Active"
          />

          <Divider />
          <Typography variant="subtitle2">Recipients</Typography>

          {targets.map((target) => (
            <Stack key={target.key} direction="row" spacing={1} alignItems="flex-start">
              <TextField
                select
                label="Kind"
                value={target.targetType}
                onChange={(event) =>
                  setTarget(target.key, { targetType: event.target.value as 'ROLE' | 'EMAIL' })
                }
                sx={{ maxWidth: 180 }}
              >
                <MenuItem value="ROLE">A role</MenuItem>
                <MenuItem value="EMAIL">Email address</MenuItem>
              </TextField>

              {target.targetType === 'ROLE' ? (
                <TextField
                  select
                  label="Role"
                  value={target.roleId}
                  onChange={(event) => setTarget(target.key, { roleId: event.target.value })}
                  helperText="Whoever holds it when the notification is sent."
                >
                  {(roles.data ?? []).map((role) => (
                    <MenuItem key={role.id} value={String(role.id)}>
                      {role.name}
                    </MenuItem>
                  ))}
                </TextField>
              ) : (
                <TextField
                  label="Email address"
                  value={target.emailAddress}
                  onChange={(event) => setTarget(target.key, { emailAddress: event.target.value })}
                  helperText="A shared inbox, or somebody with no account here."
                />
              )}

              <IconButton
                sx={{ mt: 1 }}
                disabled={targets.length === 1}
                onClick={() => setTargets((current) => current.filter((t) => t.key !== target.key))}
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            </Stack>
          ))}

          <Button
            startIcon={<AddIcon />}
            onClick={() => setTargets((current) => [...current, newTarget()])}
          >
            Add another
          </Button>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          disabled={!name.trim() || usable.length === 0 || save.isPending}
          onClick={() => save.mutate()}
        >
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}
