import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { AppNotification, NotificationPage, NotificationTrigger } from '../api/types';
import { PageHeader } from '../components/PageHeader';

const TRIGGER_LABELS: Record<NotificationTrigger, string> = {
  WARRANTY_EXPIRATION: 'Warranty',
  PURCHASE_ORDER_SUBMITTED: 'Purchase request',
  INVENTORY_STALENESS_CHECK: 'Verification',
};

/** Where a notification's subject actually lives, when it has one. */
function linkFor(entry: AppNotification): string | null {
  if (entry.entityId == null) return null;
  if (entry.entityType === 'ASSET') return `/assets/${entry.entityId}`;
  if (entry.entityType === 'PURCHASE_ORDER') return `/purchase-orders/order/${entry.entityId}`;
  return null;
}

/**
 * What the system has told this person.
 *
 * A card list rather than `EntityTable`: the body is the content, not a cell,
 * and the useful action is going to the thing it is about. Reading one marks it
 * read, because clicking through to an asset and coming back to find it still
 * bold is the sort of thing that makes a badge worthless.
 */
export function NotificationsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const notifications = useQuery({
    queryKey: ['notifications'],
    queryFn: () => api.get<NotificationPage>('/api/notifications?size=100'),
  });

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    void queryClient.invalidateQueries({ queryKey: ['notifications-unread'] });
  };

  const markRead = useMutation({
    mutationFn: (id: number) => api.post(`/api/notifications/${id}/read`),
    onSuccess: refresh,
  });

  const markAllRead = useMutation({
    mutationFn: () => api.post('/api/notifications/read-all'),
    onSuccess: refresh,
  });

  const rows = notifications.data?.content ?? [];
  const unread = notifications.data?.unread ?? 0;

  const open = (entry: AppNotification) => {
    if (entry.readAt == null) markRead.mutate(entry.id);
    const to = linkFor(entry);
    if (to) navigate(to);
  };

  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle={
          unread > 0 ? `${unread} unread` : 'Everything here has been read.'
        }
        actions={
          unread > 0 ? (
            <Button onClick={() => markAllRead.mutate()} disabled={markAllRead.isPending}>
              Mark all read
            </Button>
          ) : undefined
        }
      />

      {notifications.isLoading && <LinearProgress />}

      {!notifications.isLoading && rows.length === 0 && (
        <Paper variant="outlined" sx={{ p: 4, textAlign: 'center' }}>
          <Typography color="text.secondary">
            Nothing yet. Warranty reminders and purchase requests waiting on you appear here.
          </Typography>
        </Paper>
      )}

      <Stack spacing={1.5}>
        {rows.map((entry) => {
          const to = linkFor(entry);
          const isUnread = entry.readAt == null;
          return (
            <Card
              key={entry.id}
              variant="outlined"
              sx={{
                // Unread is carried by a left edge rather than a background
                // wash, so a long list does not read as a wall of colour.
                borderLeft: 4,
                borderLeftColor: isUnread ? 'primary.main' : 'transparent',
              }}
            >
              <CardContent>
                <Stack
                  direction={{ xs: 'column', sm: 'row' }}
                  justifyContent="space-between"
                  alignItems={{ xs: 'flex-start', sm: 'center' }}
                  spacing={1}
                >
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Chip size="small" variant="outlined" label={TRIGGER_LABELS[entry.triggerType]} />
                    <Typography variant="subtitle1" fontWeight={isUnread ? 600 : 400}>
                      {entry.subject}
                    </Typography>
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {new Date(entry.createdAt).toLocaleString()}
                  </Typography>
                </Stack>

                <Typography
                  variant="body2"
                  color="text.secondary"
                  sx={{ mt: 1, whiteSpace: 'pre-wrap' }}
                >
                  {entry.body}
                </Typography>

                <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1.5 }} flexWrap="wrap" useFlexGap>
                  {to && (
                    <Button size="small" variant="outlined" onClick={() => open(entry)}>
                      {entry.entityType === 'ASSET' ? 'Open the asset' : 'Open the order'}
                    </Button>
                  )}
                  {isUnread && (
                    <Button size="small" onClick={() => markRead.mutate(entry.id)}>
                      Mark read
                    </Button>
                  )}
                  <Box sx={{ flexGrow: 1 }} />
                  {/* Only worth saying when it is a problem. A skipped email
                      just means nobody has configured a relay. */}
                  {entry.emailStatus === 'FAILED' && (
                    <Tooltip title={entry.emailError ?? ''}>
                      <Chip size="small" color="error" variant="outlined" label="Email failed" />
                    </Tooltip>
                  )}
                  {entry.emailStatus === 'SENT' && (
                    <Chip size="small" color="success" variant="outlined" label="Emailed" />
                  )}
                </Stack>
              </CardContent>
            </Card>
          );
        })}
      </Stack>

      {rows.length > 0 && (
        <Alert severity="info" sx={{ mt: 2 }}>
          Notifications are also emailed when an SMTP relay is configured under Settings.
        </Alert>
      )}
    </>
  );
}
