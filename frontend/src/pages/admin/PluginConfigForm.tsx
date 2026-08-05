import {
  Autocomplete,
  Chip,
  FormControlLabel,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import KeyIcon from '@mui/icons-material/Key';
import type { PluginConfigField } from './pluginTypes';

/**
 * A plugin's configuration form, rendered from what the plugin says it needs.
 *
 * <p>Nothing here knows what a Zabbix is. That is the acceptance criterion the
 * whole framework exists to meet, arriving in the UI: a new integration ships a
 * schema and this renders it, with no screen to edit and nothing to redeploy.
 *
 * <p>A secret field is handled visibly differently, because it is a different
 * kind of thing: it asks for the <em>name</em> of an environment variable, and
 * says so, since typing the token itself into a field that ends up in the
 * database is exactly the mistake the design set out to prevent.
 */
export function PluginConfigForm({
  fields,
  value,
  onChange,
  secretsResolved,
}: {
  fields: PluginConfigField[];
  value: Record<string, unknown>;
  onChange: (configuration: Record<string, unknown>) => void;
  secretsResolved?: Record<string, boolean>;
}) {
  const set = (name: string, next: unknown) => onChange({ ...value, [name]: next });

  return (
    <Stack spacing={2}>
      {fields.map((field) => {
        if (field.type === 'BOOLEAN') {
          return (
            <Stack key={field.name}>
              <FormControlLabel
                control={
                  <Switch
                    checked={Boolean(value[field.name])}
                    onChange={(event) => set(field.name, event.target.checked)}
                  />
                }
                label={field.label}
              />
              {field.help && (
                <Typography variant="caption" color="text.secondary" sx={{ ml: 6 }}>
                  {field.help}
                </Typography>
              )}
            </Stack>
          );
        }

        if (field.type === 'MULTI_SELECT') {
          const selected = Array.isArray(value[field.name]) ? (value[field.name] as string[]) : [];
          return (
            <Autocomplete
              key={field.name}
              multiple
              options={field.options}
              value={selected}
              onChange={(_event, next) => set(field.name, next)}
              renderInput={(params) => (
                <TextField
                  {...params}
                  label={field.label}
                  required={field.required}
                  helperText={field.help ?? undefined}
                  placeholder={selected.length === 0 ? 'Everything the plugin proposes' : ''}
                />
              )}
            />
          );
        }

        const resolved = secretsResolved?.[field.name];
        return (
          <TextField
            key={field.name}
            label={field.label}
            required={field.required}
            type={field.type === 'NUMBER' ? 'number' : 'text'}
            value={value[field.name] ?? ''}
            onChange={(event) =>
              set(field.name, field.type === 'NUMBER'
                ? (event.target.value === '' ? '' : Number(event.target.value))
                : event.target.value)
            }
            helperText={field.help ?? undefined}
            InputProps={
              field.secretRef
                ? {
                    startAdornment: <KeyIcon fontSize="small" sx={{ mr: 1, color: 'text.secondary' }} />,
                    endAdornment: resolved === undefined ? undefined : (
                      <Chip
                        size="small"
                        variant="outlined"
                        color={resolved ? 'success' : 'warning'}
                        label={resolved ? 'Variable is set' : 'Variable not found'}
                      />
                    ),
                  }
                : undefined
            }
          />
        );
      })}
    </Stack>
  );
}
