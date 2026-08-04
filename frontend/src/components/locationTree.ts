import { alpha, type SxProps, type Theme } from '@mui/material/styles';
import type { Location } from '../api/types';

/**
 * Locations are a real hierarchy — a site holds a building, which holds a rack —
 * and a name on its own is often not enough to tell two apart. "Rack 4" exists
 * at three sites; "Kingston Headend - Rack 4" does not.
 *
 * <p>The path is built here rather than sent by the server on purpose. The
 * parent relation is lazy, so resolving it during serialization would either
 * throw outside the session or load the whole chain per asset — an N+1 on every
 * page of a 200-row asset list, to render a string the client can already
 * assemble from a list it has cached anyway.
 */

/** "Kingston Headend - Rack 4". Falls back to whatever is known. */
export function locationPath(locations: Location[] | undefined, id: number | null | undefined): string {
  if (id == null) return '';
  const byId = new Map((locations ?? []).map((entry) => [entry.id, entry]));
  const parts: string[] = [];

  let current = byId.get(id);
  // Bounded rather than trusting the data: a cycle would otherwise hang the
  // render, and a hierarchy this deep is already wrong.
  for (let depth = 0; current && depth < 10; depth += 1) {
    parts.unshift(current.name);
    current = current.parentLocationId == null ? undefined : byId.get(current.parentLocationId);
  }
  return parts.join(' - ');
}

export interface LocationOption {
  location: Location;
  /** 0 for a top-level location, 1 for its children, and so on. */
  depth: number;
  /** The full "Parent - Child" path, for the closed select and for searching. */
  path: string;
}

/**
 * The tree flattened into the order it should be listed in: every location
 * directly beneath its parent, children after the parent they belong to.
 *
 * <p>Alphabetical within each level, so the list is stable and findable rather
 * than ordered by whenever somebody happened to create things.
 */
export function locationOptions(locations: Location[] | undefined): LocationOption[] {
  const all = locations ?? [];
  const byParent = new Map<number | null, Location[]>();
  for (const entry of all) {
    const key = entry.parentLocationId ?? null;
    byParent.set(key, [...(byParent.get(key) ?? []), entry]);
  }
  for (const group of byParent.values()) {
    group.sort((a, b) => a.name.localeCompare(b.name));
  }

  const options: LocationOption[] = [];
  const walk = (parentId: number | null, depth: number, prefix: string) => {
    for (const location of byParent.get(parentId) ?? []) {
      const path = prefix ? `${prefix} - ${location.name}` : location.name;
      options.push({ location, depth, path });
      // Bounded for the same reason locationPath is.
      if (depth < 9) walk(location.id, depth + 1, path);
    }
  };
  walk(null, 0, '');

  // A location whose parent is missing from the list -- filtered out, or not
  // readable -- would otherwise vanish from the picker entirely.
  const seen = new Set(options.map((option) => option.location.id));
  for (const orphan of all.filter((entry) => !seen.has(entry.id))) {
    options.push({ location: orphan, depth: 0, path: orphan.name });
  }
  return options;
}

/**
 * How a row in a location dropdown is drawn at its depth.
 *
 * <p>A child is a shaded band rather than a dash in front of its name: the
 * shading is the whole row, so the grouping is legible at a glance and down the
 * left edge of a long list, where a punctuation mark buried in the text is not.
 * Depth deepens the shade to a limit — past the third level the difference stops
 * being distinguishable, and pretending otherwise just makes deep rows dark.
 *
 * <p>Shared by every location picker so they all read the same way. Left as a
 * style rather than a component because each caller renders its own MenuItem
 * with its own value and key handling.
 */
export function locationOptionSx(depth: number): SxProps<Theme> {
  if (depth === 0) return { pl: 2 };
  return {
    pl: 2 + depth * 2,
    // Derived from the text colour, so it shades correctly in either theme
    // instead of being a grey that disappears against a dark background.
    bgcolor: (theme: Theme) =>
      alpha(theme.palette.text.primary, 0.03 + 0.03 * Math.min(depth, 3)),
  };
}
