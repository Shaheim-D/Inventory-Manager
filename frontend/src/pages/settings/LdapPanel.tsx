import { useEffect, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Button,
  Chip,
  Divider,
  FormControlLabel,
  Grid,
  IconButton,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../../api/client';

interface LdapSettings {
  enabled: boolean;
  host: string | null;
  port: number;
  transport: 'NONE' | 'STARTTLS' | 'LDAPS' | null;
  userSearchBase: string | null;
  userSearchFilter: string;
  groupAttribute: string;
  upnSuffix: string | null;
  bindDn: string | null;
  connectTimeoutSeconds: number;
  bindPasswordSet: boolean;
  bindPasswordReadable: boolean;
  usable: boolean;
}

interface RoleMapping {
  id: number;
  groupValue: string;
  roleId: number;
  roleName: string | null;
}

interface Role {
  id: number;
  name: string;
}

interface TestResult {
  ok: boolean;
  message: string;
  groups?: string[];
  mappedRoles?: string[];
  distinguishedName?: string;
}

/**
 * LDAP / Active Directory sign-in.
 *
 * <p>Sits beside the RADIUS pane on Settings → Remote Authentication rather
 * than replacing it. Both can be on: RADIUS proves a password, and LDAP does
 * that <em>and</em> reads group membership, which is the only way a role can
 * follow an AD group.
 */
export function LdapPanel() {
  const queryClient = useQueryClient();

  const settings = useQuery({
    queryKey: ['ldap-settings'],
    queryFn: () => api.get<LdapSettings>('/api/admin/ldap-settings'),
  });
  const mappings = useQuery({
    queryKey: ['ldap-role-mappings'],
    queryFn: () => api.get<RoleMapping[]>('/api/admin/ldap-settings/role-mappings'),
  });
  const roles = useQuery({
    queryKey: ['roles'],
    queryFn: () => api.get<Role[]>('/api/admin/roles'),
  });

  const [form, setForm] = useState({
    enabled: false,
    host: '',
    port: '636',
    transport: 'LDAPS' as 'NONE' | 'STARTTLS' | 'LDAPS',
    userSearchBase: '',
    userSearchFilter: '(sAMAccountName={0})',
    groupAttribute: 'memberOf',
    upnSuffix: '',
    bindDn: '',
    bindPassword: '',
    connectTimeoutSeconds: '5',
  });
  const [passwordTouched, setPasswordTouched] = useState(false);
  const [banner, setBanner] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  const [testUser, setTestUser] = useState({ username: '', password: '' });
  const [testResult, setTestResult] = useState<TestResult | null>(null);
  const [newMapping, setNewMapping] = useState({ groupValue: '', roleId: '' });

  useEffect(() => {
    const data = settings.data;
    if (!data) return;
    setForm({
      enabled: data.enabled,
      host: data.host ?? '',
      port: String(data.port ?? 636),
      transport: data.transport ?? 'LDAPS',
      userSearchBase: data.userSearchBase ?? '',
      userSearchFilter: data.userSearchFilter ?? '(sAMAccountName={0})',
      groupAttribute: data.groupAttribute ?? 'memberOf',
      upnSuffix: data.upnSuffix ?? '',
      bindDn: data.bindDn ?? '',
      bindPassword: '',
      connectTimeoutSeconds: String(data.connectTimeoutSeconds ?? 5),
    });
    setPasswordTouched(false);
  }, [settings.data]);

  const set = (patch: Partial<typeof form>) => setForm((current) => ({ ...current, ...patch }));

  const save = useMutation({
    mutationFn: () =>
      api.put<LdapSettings>('/api/admin/ldap-settings', {
        enabled: form.enabled,
        host: form.host.trim() || null,
        port: form.port ? Number(form.port) : 636,
        transport: form.transport,
        userSearchBase: form.userSearchBase.trim() || null,
        userSearchFilter: form.userSearchFilter.trim() || null,
        groupAttribute: form.groupAttribute.trim() || null,
        upnSuffix: form.upnSuffix.trim() || null,
        bindDn: form.bindDn.trim() || null,
        // Omitted unless touched, so saving after changing the host does not
        // blank a password this screen was never shown.
        ...(passwordTouched ? { bindPassword: form.bindPassword } : {}),
        connectTimeoutSeconds: Number(form.connectTimeoutSeconds) || 5,
      }),
    onSuccess: () => {
      setBanner({ kind: 'success', text: 'Saved.' });
      void queryClient.invalidateQueries({ queryKey: ['ldap-settings'] });
    },
    onError: (caught) =>
      setBanner({
        kind: 'error',
        text: caught instanceof ApiError ? caught.message : 'Could not save these settings.',
      }),
  });

  const test = useMutation({
    mutationFn: () => api.post<TestResult>('/api/admin/ldap-settings/test', testUser),
    onSuccess: (result) => setTestResult(result),
    onError: (caught) =>
      setTestResult({
        ok: false,
        message: caught instanceof ApiError ? caught.message : 'The test could not be run.',
      }),
  });

  const addMapping = useMutation({
    mutationFn: () =>
      api.post('/api/admin/ldap-settings/role-mappings', {
        groupValue: newMapping.groupValue.trim(),
        roleId: Number(newMapping.roleId),
      }),
    onSuccess: () => {
      setNewMapping({ groupValue: '', roleId: '' });
      void queryClient.invalidateQueries({ queryKey: ['ldap-role-mappings'] });
    },
    onError: (caught) =>
      setBanner({
        kind: 'error',
        text: caught instanceof ApiError ? caught.message : 'That mapping could not be added.',
      }),
  });

  const removeMapping = useMutation({
    mutationFn: (id: number) => api.del(`/api/admin/ldap-settings/role-mappings/${id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['ldap-role-mappings'] }),
  });

  const usingServiceAccount = form.bindDn.trim().length > 0;

  return (
    <>
      {settings.isLoading && <LinearProgress />}
      {banner && (
        <Alert severity={banner.kind} sx={{ mb: 2 }} onClose={() => setBanner(null)}>
          {banner.text}
        </Alert>
      )}
      {settings.data && settings.data.bindPasswordSet && !settings.data.bindPasswordReadable && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          The stored service account password cannot be decrypted with this installation's
          encryption key. That normally means the database was restored onto a host without the
          original <code>APP_ENCRYPTION_KEY</code>. Re-enter it below.
        </Alert>
      )}

      <FormControlLabel
        control={
          <Switch checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        }
        label="Let people sign in with their Active Directory account"
      />

      <Grid container spacing={2} sx={{ mt: 0.5 }}>
        <Grid item xs={12} md={5}>
          <TextField
            fullWidth
            label="Domain controller"
            value={form.host}
            onChange={(e) => set({ host: e.target.value })}
            helperText="Hostname or IP, e.g. dc1.corp.example.com"
          />
        </Grid>
        <Grid item xs={6} md={2}>
          <TextField
            fullWidth
            type="number"
            label="Port"
            value={form.port}
            onChange={(e) => set({ port: e.target.value })}
          />
        </Grid>
        <Grid item xs={6} md={3}>
          <TextField
            select
            fullWidth
            label="Security"
            value={form.transport}
            onChange={(e) => set({ transport: e.target.value as typeof form.transport })}
          >
            <MenuItem value="LDAPS">LDAPS (port 636)</MenuItem>
            <MenuItem value="STARTTLS">StartTLS (port 389)</MenuItem>
            <MenuItem value="NONE">None — unencrypted</MenuItem>
          </TextField>
        </Grid>
        <Grid item xs={6} md={2}>
          <TextField
            fullWidth
            type="number"
            label="Timeout"
            value={form.connectTimeoutSeconds}
            onChange={(e) => set({ connectTimeoutSeconds: e.target.value })}
            helperText="Seconds"
          />
        </Grid>

        {form.transport === 'NONE' && (
          <Grid item xs={12}>
            <Alert severity="warning">
              A simple LDAP bind sends the password in the clear. Use LDAPS or StartTLS for
              anything but a lab.
            </Alert>
          </Grid>
        )}

        <Grid item xs={12}>
          <TextField
            fullWidth
            label="Search base"
            value={form.userSearchBase}
            onChange={(e) => set({ userSearchBase: e.target.value })}
            helperText="Where to look for people, e.g. DC=corp,DC=example,DC=com"
          />
        </Grid>
      </Grid>

      <Divider sx={{ my: 3 }} />

      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        How this application reaches the directory
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Two ways, and you need one. A <strong>UPN suffix</strong> binds straight as
        <code> username@suffix</code>, which Active Directory allows — no service account exists,
        so none can leak. A <strong>service account</strong> looks the person up first, which is
        needed where users are not allowed to search the directory.
      </Typography>

      <Grid container spacing={2}>
        <Grid item xs={12} md={4}>
          <TextField
            fullWidth
            label="UPN suffix"
            value={form.upnSuffix}
            onChange={(e) => set({ upnSuffix: e.target.value })}
            helperText="e.g. corp.example.com"
            disabled={usingServiceAccount}
          />
        </Grid>
        <Grid item xs={12} md={5}>
          <TextField
            fullWidth
            label="Service account DN"
            value={form.bindDn}
            onChange={(e) => set({ bindDn: e.target.value })}
            helperText="CN=svc-inventory,OU=Service,DC=corp,DC=example,DC=com"
          />
        </Grid>
        <Grid item xs={12} md={3}>
          <TextField
            fullWidth
            type="password"
            label="Service account password"
            value={form.bindPassword}
            onChange={(e) => {
              setPasswordTouched(true);
              set({ bindPassword: e.target.value });
            }}
            placeholder={settings.data?.bindPasswordSet ? '••••••••' : ''}
            helperText={
              settings.data?.bindPasswordSet ? 'Stored. Leave blank to keep it.' : 'Stored encrypted.'
            }
            disabled={!usingServiceAccount}
          />
        </Grid>
      </Grid>

      <Divider sx={{ my: 3 }} />

      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        Directory attributes
      </Typography>
      <Grid container spacing={2}>
        <Grid item xs={12} md={7}>
          <TextField
            fullWidth
            label="User search filter"
            value={form.userSearchFilter}
            onChange={(e) => set({ userSearchFilter: e.target.value })}
            helperText="{0} is the username as typed. Active Directory uses sAMAccountName."
          />
        </Grid>
        <Grid item xs={12} md={5}>
          <TextField
            fullWidth
            label="Group attribute"
            value={form.groupAttribute}
            onChange={(e) => set({ groupAttribute: e.target.value })}
            helperText="memberOf on Active Directory"
          />
        </Grid>
      </Grid>

      <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 3 }}>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? 'Saving…' : 'Save'}
        </Button>
        {settings.data?.usable && <Chip size="small" color="success" label="Configured" />}
      </Stack>

      <Divider sx={{ my: 3 }} />

      {/* Testing before switching it on is the point: the result lists the
          groups, which are the exact strings the mappings below have to match. */}
      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        Test with a real account
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Runs the real sign-in path against the settings as saved, even with the switch off. The
        result lists the groups the directory returned — those are the strings to map to roles
        below.
      </Typography>
      <Grid container spacing={2} alignItems="flex-start">
        <Grid item xs={12} sm={4}>
          <TextField
            fullWidth
            label="Directory username"
            value={testUser.username}
            onChange={(e) => setTestUser({ ...testUser, username: e.target.value })}
          />
        </Grid>
        <Grid item xs={12} sm={4}>
          <TextField
            fullWidth
            type="password"
            label="Password"
            value={testUser.password}
            onChange={(e) => setTestUser({ ...testUser, password: e.target.value })}
          />
        </Grid>
        <Grid item xs={12} sm={4}>
          <Button
            variant="outlined"
            sx={{ mt: 1 }}
            disabled={test.isPending}
            onClick={() => test.mutate()}
          >
            {test.isPending ? 'Testing…' : 'Test sign-in'}
          </Button>
        </Grid>
      </Grid>

      {testResult && (
        <Alert
          severity={testResult.ok ? 'success' : 'error'}
          sx={{ mt: 2 }}
          onClose={() => setTestResult(null)}
        >
          <AlertTitle>{testResult.ok ? 'The directory accepted that account' : 'Test failed'}</AlertTitle>
          {testResult.message}
          {testResult.groups && testResult.groups.length > 0 && (
            <>
              <Typography variant="body2" sx={{ mt: 1.5, fontWeight: 600 }}>
                Groups returned
              </Typography>
              <Stack spacing={0.5} sx={{ mt: 0.5 }}>
                {testResult.groups.map((group) => (
                  <Typography key={group} variant="caption" sx={{ fontFamily: 'monospace' }}>
                    {group}
                  </Typography>
                ))}
              </Stack>
            </>
          )}
          {testResult.ok && (
            <Typography variant="body2" sx={{ mt: 1.5 }}>
              {testResult.mappedRoles && testResult.mappedRoles.length > 0
                ? `This account would get: ${testResult.mappedRoles.join(', ')}.`
                : 'No group below matches, so this account would get Unassigned — a read-only view.'}
            </Typography>
          )}
        </Alert>
      )}

      <Divider sx={{ my: 3 }} />

      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        Which group grants which role
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        This is the part RADIUS cannot do. Either the group's full DN or just its name works.
        Roles are <strong>replaced</strong> at every sign-in, so removing somebody from a group in
        Active Directory removes their access here the next time they sign in. Accounts created in
        this application are never touched.
      </Typography>

      <Paper variant="outlined" sx={{ mb: 2 }}>
        <Stack divider={<Divider />}>
          {(mappings.data ?? []).map((mapping) => (
            <Stack
              key={mapping.id}
              direction="row"
              alignItems="center"
              spacing={2}
              sx={{ px: 2, py: 1 }}
            >
              <Typography variant="body2" sx={{ fontFamily: 'monospace', flex: 1 }}>
                {mapping.groupValue}
              </Typography>
              <Chip size="small" label={mapping.roleName ?? `Role ${mapping.roleId}`} />
              <IconButton
                size="small"
                aria-label={`Remove mapping for ${mapping.groupValue}`}
                onClick={() => removeMapping.mutate(mapping.id)}
              >
                <DeleteOutlineIcon fontSize="small" />
              </IconButton>
            </Stack>
          ))}
          {(mappings.data ?? []).length === 0 && (
            <Typography variant="body2" color="text.secondary" sx={{ p: 2 }}>
              No groups mapped yet. Everybody signing in through the directory lands in
              Unassigned, which is read-only.
            </Typography>
          )}
        </Stack>
      </Paper>

      <Grid container spacing={2} alignItems="flex-start">
        <Grid item xs={12} sm={6}>
          <TextField
            fullWidth
            label="Directory group"
            value={newMapping.groupValue}
            onChange={(e) => setNewMapping({ ...newMapping, groupValue: e.target.value })}
            helperText="IT Staff, or the full CN=IT Staff,OU=Groups,DC=..."
          />
        </Grid>
        <Grid item xs={12} sm={4}>
          <TextField
            select
            fullWidth
            label="Grants role"
            value={newMapping.roleId}
            onChange={(e) => setNewMapping({ ...newMapping, roleId: e.target.value })}
          >
            {(roles.data ?? []).map((role) => (
              <MenuItem key={role.id} value={role.id}>
                {role.name}
              </MenuItem>
            ))}
          </TextField>
        </Grid>
        <Grid item xs={12} sm={2}>
          <Button
            variant="outlined"
            sx={{ mt: 1 }}
            disabled={!newMapping.groupValue.trim() || !newMapping.roleId || addMapping.isPending}
            onClick={() => addMapping.mutate()}
          >
            Add
          </Button>
        </Grid>
      </Grid>
    </>
  );
}
