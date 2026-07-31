import { Checkbox, FormControlLabel, Grid, MenuItem, TextField } from '@mui/material';
import type { CustomFieldDefinition } from '../api/types';

interface Props {
  definitions: CustomFieldDefinition[];
  values: Record<string, unknown>;
  onChange: (fieldName: string, value: unknown) => void;
  disabled?: boolean;
}

/**
 * Renders a form from a field-definition list rather than a hardcoded layout.
 * The same component serves asset custom fields today and plugin configuration
 * schemas later, because both are "a bag of declared fields".
 *
 * It renders exactly the definitions it was handed. The server already withheld
 * any the viewer may not see, so a gated field never reaches this component —
 * and this component never decides visibility for itself.
 *
 * Single column on narrow viewports regardless of how many fields a category has.
 */
export function DynamicFieldForm({ definitions, values, onChange, disabled }: Props) {
  if (definitions.length === 0) return null;

  return (
    <Grid container spacing={2}>
      {definitions.map((definition) => {
        const value = values[definition.fieldName];

        if (definition.fieldType === 'BOOLEAN') {
          return (
            <Grid item xs={12} sm={6} key={definition.id}>
              <FormControlLabel
                control={
                  <Checkbox
                    checked={value === true || value === 'true'}
                    disabled={disabled}
                    onChange={(event) => onChange(definition.fieldName, event.target.checked)}
                  />
                }
                label={definition.fieldName + (definition.required ? ' *' : '')}
              />
            </Grid>
          );
        }

        const shared = {
          label: definition.fieldName,
          required: definition.required,
          disabled,
          value: value === undefined || value === null ? '' : String(value),
          onChange: (event: React.ChangeEvent<HTMLInputElement>) =>
            onChange(definition.fieldName, event.target.value),
        };

        return (
          <Grid item xs={12} sm={6} key={definition.id}>
            {definition.fieldType === 'ENUM' ? (
              <TextField select {...shared}>
                <MenuItem value="">
                  <em>Not set</em>
                </MenuItem>
                {(definition.enumOptions ?? []).map((option) => (
                  <MenuItem key={option} value={option}>
                    {option}
                  </MenuItem>
                ))}
              </TextField>
            ) : (
              <TextField
                {...shared}
                type={definition.fieldType === 'NUMBER' ? 'number' : definition.fieldType === 'DATE' ? 'date' : 'text'}
                InputLabelProps={definition.fieldType === 'DATE' ? { shrink: true } : undefined}
              />
            )}
          </Grid>
        );
      })}
    </Grid>
  );
}
