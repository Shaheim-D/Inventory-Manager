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
import DoneAllIcon from '@mui/icons-material/DoneAll';
import FiberManualRecordIcon from '@mui/icons-material/FiberManualRecord';
import MarkEmailUnreadOutlinedIcon from '@mui/icons-material/MarkEmailUnreadOutlined';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { AppNotification, NotificationPage } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import {
  notificationLink,
  notificationLinkLabel,
  triggerChip,
} from '../components/notificationLabels';

/**
 * What the system has told this person.
 *
 * A card list rather than `EntityTable`: the body is the content, not a cell,
 * and the useful action is going to the thing it is about. Reading one marks it
 * read, because clicking through to an asset and coming back to find it still
 * bold is the sort of thing that makes a badge worthless.
 *
 * Read and unread are told apart four ways over — the left edge, the weight of
 * the subject, a dot against a tick, and the card's own background. One signal
 * is easy to miss when skimming, and the difference between "I have dealt with
 * this" and "I have not" is the entire point of the screen.
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

  const markUnread = useMutation({
    mutationFn: (id: number) => api.post(`/api/notifications/${id}/unread`),
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
    const to = notificationLink(entry);
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
          const to = notificationLink(entry);
          const isUnread = entry.readAt == null;
          return (
            <Card
              key={entry.id}
              variant="outlined"
              sx={{
                // Unread keeps the paper background and a coloured left edge;
                // read recedes into a shaded card with no edge, so the two are
                // distinguishable at a glance down a long list rather than only
                // by reading each subject.
                borderLeft: 4,
                borderLeftColor: isUnread ? 'primary.main' : 'transparent',
                bgcolor: isUnread ? 'background.paper' : 'action.hover',
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
                    {isUnread ? (
                      <Tooltip title="Unread">
                        <FiberManualRecordIcon color="primary" sx={{ fontSize: 12 }} />
                      </Tooltip>
                    ) : (
                      <Tooltip title={`Read ${new Date(entry.readAt as string).toLocaleString()}`}>
                        <DoneAllIcon sx={{ fontSize: 16, color: 'text.disabled' }} />
                      </Tooltip>
                    )}
                    <Chip size="small" variant="outlined" label={triggerChip(entry.triggerType)} />
                    <Typography
                      variant="subtitle1"
                      fontWeight={isUnread ? 600 : 400}
                      color={isUnread ? 'text.primary' : 'text.secondary'}
                    >
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
                      {notificationLinkLabel(entry)}
                    </Button>
                  )}
                  {isUnread ? (
                    <Button size="small" onClick={() => markRead.mutate(entry.id)}>
                      Mark read
                    </Button>
                  ) : (
                    // Somebody clears the badge, then realises they have not
                    // actually dealt with one. Without this the only way to keep
                    // track of it is to remember.
                    <Button
                      size="small"
                      startIcon={<MarkEmailUnreadOutlinedIcon fontSize="small" />}
                      onClick={() => markUnread.mutate(entry.id)}
                    >
                      Mark unread
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
                  {entry.emailStatus === 'DEFERRED' && (
                    <Tooltip title="This rule sends its emails on a summary rather than one at a time.">
                      <Chip size="small" variant="outlined" label="Emailing on a summary" />
                    </Tooltip>
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
