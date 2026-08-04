import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, AlertTitle, Box, Slide, Typography } from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { AppNotification } from '../api/types';
import { notificationLink, triggerChip } from './notificationLabels';

/** Long enough to read a subject and act on it, short enough not to sit there. */
const DISMISS_AFTER_MS = 8_000;
/** More than a few at once stops being a notice and becomes a wall. */
const MOST_AT_ONCE = 3;

/**
 * The on-screen notice for something that arrives while you are sitting in the
 * app.
 *
 * <p>The whole design turns on one distinction: what arrived *while you were
 * here* against what was already waiting when you got here. The first is worth
 * interrupting for — you are looking at the screen, and a purchase request that
 * lands while you work should not wait for you to think to check the bell. The
 * second is not: signing in on Monday to a flurry of popups for things that
 * happened over the weekend is noise, and they are already listed in the
 * notification centre where they belong.
 *
 * <p>So the baseline is the newest notification id at the moment this mounts,
 * and only ids above it are ever shown. Nothing that existed before you arrived
 * can pop up, by construction rather than by a timestamp comparison that
 * would need a clock both ends agree on.
 *
 * <p>Polled, like the badge. A WebSocket for this would mean a session-aware
 * push channel and a reconnect story for a payload that is a handful of rows a
 * day — the same trade Phase 9 §7 makes for import progress.
 */
export function NotificationToaster() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [showing, setShowing] = useState<AppNotification[]>([]);

  // null until the first poll answers. Until then there is no baseline, so
  // nothing can be judged new -- which is exactly right on a cold load.
  const baseline = useRef<number | null>(null);

  const unread = useQuery({
    // Shares the bell's key deliberately: one request answers both, and the
    // badge and the popup can never disagree about what has arrived.
    queryKey: ['notifications-unread'],
    queryFn: () => api.get<{ unread: number; latestId: number }>('/api/notifications/unread-count'),
    refetchInterval: 20_000,
    refetchOnWindowFocus: true,
  });

  const latestId = unread.data?.latestId;

  useEffect(() => {
    if (latestId == null) return;
    if (baseline.current === null) {
      baseline.current = latestId;
      return;
    }
    if (latestId <= baseline.current) return;

    const after = baseline.current;
    // Advanced before the request, not after: two polls overlapping would
    // otherwise both ask for the same range and show everything twice.
    baseline.current = latestId;

    void api
      .get<AppNotification[]>(`/api/notifications/since/${after}`)
      .then((arrived) => {
        if (arrived.length === 0) return;
        setShowing((current) => [...current, ...arrived].slice(-MOST_AT_ONCE));
        // The centre is probably open in another tab, or about to be.
        void queryClient.invalidateQueries({ queryKey: ['notifications'] });
      })
      .catch(() => {
        // A failed fetch is not worth telling anyone about -- the notification
        // is safe in the log and the bell already counts it.
      });
  }, [latestId, queryClient]);

  const dismiss = useCallback(
    (id: number) => setShowing((current) => current.filter((entry) => entry.id !== id)),
    [],
  );

  const open = useCallback(
    (entry: AppNotification) => {
      dismiss(entry.id);
      void api.post(`/api/notifications/${entry.id}/read`).then(() => {
        void queryClient.invalidateQueries({ queryKey: ['notifications'] });
        void queryClient.invalidateQueries({ queryKey: ['notifications-unread'] });
      });
      // Somewhere useful either way: the thing it is about when there is one,
      // and the centre when there is not, so a click is never a dead end.
      navigate(notificationLink(entry) ?? '/notifications');
    },
    [dismiss, navigate, queryClient],
  );

  if (showing.length === 0) return null;

  return (
    <Box
      sx={{
        position: 'fixed',
        // Above the bottom of the viewport rather than anchored to the content,
        // so it is in the same place whatever page is underneath it.
        bottom: { xs: 8, sm: 24 },
        right: { xs: 8, sm: 24 },
        left: { xs: 8, sm: 'auto' },
        zIndex: (theme) => theme.zIndex.snackbar,
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
        maxWidth: { sm: 420 },
        pointerEvents: 'none',
      }}
    >
      {showing.map((entry) => (
        <Toast key={entry.id} entry={entry} onDismiss={dismiss} onOpen={open} />
      ))}
    </Box>
  );
}

function Toast({
  entry,
  onDismiss,
  onOpen,
}: {
  entry: AppNotification;
  onDismiss: (id: number) => void;
  onOpen: (entry: AppNotification) => void;
}) {
  const [visible, setVisible] = useState(true);

  useEffect(() => {
    // Two stages so the slide-out is seen rather than the element vanishing.
    const hide = setTimeout(() => setVisible(false), DISMISS_AFTER_MS);
    const remove = setTimeout(() => onDismiss(entry.id), DISMISS_AFTER_MS + 300);
    return () => {
      clearTimeout(hide);
      clearTimeout(remove);
    };
  }, [entry.id, onDismiss]);

  return (
    <Slide direction="left" in={visible} mountOnEnter unmountOnExit>
      <Alert
        severity="info"
        variant="filled"
        // Stopped, or dismissing one would also open it.
        onClose={(event) => {
          event.stopPropagation();
          onDismiss(entry.id);
        }}
        onClick={() => onOpen(entry)}
        sx={{ cursor: 'pointer', pointerEvents: 'auto', boxShadow: 6 }}
      >
        <AlertTitle sx={{ mb: 0.25 }}>{triggerChip(entry.triggerType)}</AlertTitle>
        <Typography variant="body2" fontWeight={600}>
          {entry.subject}
        </Typography>
        <Typography
          variant="caption"
          sx={{
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
          }}
        >
          {entry.body}
        </Typography>
      </Alert>
    </Slide>
  );
}
