/**
 * How values are written down, in one place.
 *
 * These started inside the purchase-orders module, which is where money and
 * dates first needed formatting — but an asset has a retail price and a device
 * has a default one, and each of those screens grew its own copy of the same
 * `toLocaleString` call. Four copies of a currency format is four places to
 * edit the day this has to show anything other than dollars.
 */

/** A price, or an em dash when there is genuinely no figure. */
export function money(value: number | null | undefined): string {
  if (value == null) return '—';
  return Number(value).toLocaleString(undefined, { style: 'currency', currency: 'USD' });
}

/** A timestamp in the viewer's own locale and zone. */
export function when(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString() : '—';
}
