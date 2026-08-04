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
