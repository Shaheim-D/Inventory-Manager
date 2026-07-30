/**
 * The single place HTTP happens. Every request is same-origin and relative, so
 * nothing here knows or cares whether the app is behind nginx or on the Vite
 * dev server.
 */

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

function csrfToken(): string | undefined {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : undefined;
}

async function request<T>(method: string, path: string, body?: unknown, isForm = false): Promise<T> {
  const headers: Record<string, string> = {};
  if (!isForm && body !== undefined) headers['Content-Type'] = 'application/json';
  const token = csrfToken();
  if (token) headers['X-XSRF-TOKEN'] = token;

  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: isForm ? (body as FormData) : body === undefined ? undefined : JSON.stringify(body),
  });

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const payload = text ? safeParse(text) : undefined;

  if (!response.ok) {
    const message =
      (payload && typeof payload === 'object' && 'error' in payload
        ? String((payload as { error: unknown }).error)
        : undefined) ?? `Request failed (${response.status})`;
    throw new ApiError(response.status, message);
  }
  return payload as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  del: <T>(path: string, body?: unknown) => request<T>('DELETE', path, body),
  upload: <T>(path: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return request<T>('POST', path, form, true);
  },
};

/**
 * Spring only issues the CSRF cookie once something has been requested, so the
 * app touches a public endpoint before the first sign-in attempt.
 */
export async function primeCsrf(): Promise<void> {
  if (csrfToken()) return;
  await fetch('/api/branding', { credentials: 'same-origin' });
}
