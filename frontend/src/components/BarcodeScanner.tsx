import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, Snackbar } from '@mui/material';
import { api, ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';

/**
 * Scan an asset tag anywhere in the application and land on that asset.
 *
 * Asset tag stickers carry a barcode of the tag number. The scanners that read
 * them are keyboard wedges: they type the characters as if somebody had, very
 * fast, and finish with Enter. So there is no device to talk to and no driver
 * to install — the whole feature is knowing the difference between a scan and
 * a person at a keyboard.
 *
 * That difference is speed. A wedge emits characters a few milliseconds apart;
 * nobody types four characters with fifty-millisecond gaps and then hits Enter.
 * Both tests have to pass — a burst that never ends in Enter is not a scan, and
 * neither is a slow one.
 *
 * The important part is what it refuses to do:
 *
 *   - **It never touches a field.** If the keystrokes are going into an input,
 *     a textarea, a select or anything contenteditable, this does not look at
 *     them at all. That is not only about not breaking typing: scanning a tag
 *     straight into the asset-tag box on the asset form is a thing people will
 *     want to do, and it keeps working precisely because this stays out of the
 *     way when a field has focus.
 *   - **It does not guess.** A tag matching nothing says so, rather than
 *     silently doing nothing or dumping the operator somewhere unexpected.
 *
 * Requires `asset:read`; without it there is nothing to navigate to.
 */

/** Longest gap between two keystrokes that can still be one scan. */
const MAX_KEYSTROKE_GAP_MS = 60;

/** Below this, a fast keypress or two is more likely a stray than a barcode. */
const MIN_SCAN_LENGTH = 3;

interface Found {
  id: number;
  displayLabel: string;
}

export function BarcodeScanner() {
  const navigate = useNavigate();
  const { has } = useAuth();
  const enabled = has('asset:read');

  const buffer = useRef('');
  const lastKeyAt = useRef(0);
  type Result =
    | { kind: 'found'; label: string }
    | { kind: 'missing'; tag: string }
    | { kind: 'error'; message: string };
  const [result, setResult] = useState<Result | null>(null);

  const resolve = useCallback(
    async (tag: string) => {
      try {
        const found = await api.get<Found>(`/api/assets/lookup?assetTag=${encodeURIComponent(tag)}`);
        navigate(`/assets/${found.id}`);
        setResult({ kind: 'found', label: found.displayLabel });
      } catch (caught) {
        if (caught instanceof ApiError && caught.status === 404) {
          setResult({ kind: 'missing', tag });
          return;
        }
        // Anything else — offline, a 500, a session that expired between the
        // scan and the lookup — is not "no such tag". Reporting it as one would
        // send somebody hunting for a sticker problem they do not have.
        setResult({
          kind: 'error',
          message: caught instanceof ApiError ? caught.message : 'Could not look that tag up.',
        });
      }
    },
    [navigate],
  );

  useEffect(() => {
    if (!enabled) return;

    function onKeyDown(event: KeyboardEvent) {
      // A field has the keystrokes. Leave them alone entirely.
      const target = event.target as HTMLElement | null;
      if (target) {
        const tag = target.tagName;
        if (
          tag === 'INPUT' ||
          tag === 'TEXTAREA' ||
          tag === 'SELECT' ||
          target.isContentEditable
        ) {
          buffer.current = '';
          return;
        }
      }

      // A shortcut is being pressed, not a barcode.
      if (event.ctrlKey || event.metaKey || event.altKey) {
        buffer.current = '';
        return;
      }

      const now = Date.now();
      const gap = now - lastKeyAt.current;
      lastKeyAt.current = now;

      if (event.key === 'Enter') {
        const scanned = buffer.current;
        buffer.current = '';
        // Enter closes a scan only if it arrived as fast as the rest of it.
        if (scanned.length >= MIN_SCAN_LENGTH && gap <= MAX_KEYSTROKE_GAP_MS) {
          // Swallow it, so the Enter that terminates a barcode cannot also
          // press whatever button happens to have focus.
          event.preventDefault();
          void resolve(scanned);
        }
        return;
      }

      // Printable characters only: `key` is one character for those and a word
      // like "Shift" or "ArrowLeft" for everything else.
      if (event.key.length !== 1) return;

      // Too slow to be part of the previous burst, so this is the start of a
      // new one rather than a continuation.
      buffer.current = gap > MAX_KEYSTROKE_GAP_MS ? event.key : buffer.current + event.key;
    }

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [enabled, resolve]);

  if (!enabled) return null;

  return (
    <Snackbar
      open={result !== null}
      autoHideDuration={result?.kind === 'missing' ? 8000 : 3000}
      onClose={() => setResult(null)}
      anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
    >
      {result?.kind === 'found' ? (
        <Alert severity="success" variant="filled" onClose={() => setResult(null)}>
          Scanned — opened {result.label}
        </Alert>
      ) : result?.kind === 'missing' ? (
        <Alert
          severity="warning"
          variant="filled"
          onClose={() => setResult(null)}
          action={
            // The tag may belong to something recorded under a different field,
            // or not recorded at all. A search is the useful next move, and it
            // is one click rather than retyping what was just scanned.
            <Button
              color="inherit"
              size="small"
              onClick={() => {
                const { tag } = result;
                setResult(null);
                navigate(`/assets?q=${encodeURIComponent(tag)}`);
              }}
            >
              Search
            </Button>
          }
        >
          No asset has the tag {result.tag}
        </Alert>
      ) : (
        <Alert severity="error" variant="filled" onClose={() => setResult(null)}>
          {result?.kind === 'error' ? result.message : ''}
        </Alert>
      )}
    </Snackbar>
  );
}
