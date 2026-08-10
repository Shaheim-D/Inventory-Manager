import { useState } from 'react';
import { Link as RouterLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Badge,
  Box,
  Divider,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Collapse,
  Menu,
  MenuItem,
  Switch,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import VpnKeyIcon from '@mui/icons-material/VpnKey';
import MenuIcon from '@mui/icons-material/Menu';
import NotificationsIcon from '@mui/icons-material/Notifications';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';
import AddIcon from '@mui/icons-material/Add';
import DashboardIcon from '@mui/icons-material/SpaceDashboard';
import InventoryIcon from '@mui/icons-material/Inventory2';
import RouterIcon from '@mui/icons-material/Router';
import PlaceIcon from '@mui/icons-material/Place';
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart';
import FactCheckIcon from '@mui/icons-material/FactCheck';
import HistoryIcon from '@mui/icons-material/History';
import CategoryIcon from '@mui/icons-material/Category';
import PeopleIcon from '@mui/icons-material/People';
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import PaletteIcon from '@mui/icons-material/Palette';
import RuleIcon from '@mui/icons-material/Rule';
import AssessmentIcon from '@mui/icons-material/Assessment';
import ExtensionIcon from '@mui/icons-material/Extension';
import SettingsIcon from '@mui/icons-material/Settings';
import ExpandLess from '@mui/icons-material/ExpandLess';
import ExpandMore from '@mui/icons-material/ExpandMore';
import MailIcon from '@mui/icons-material/Email';
import BackupIcon from '@mui/icons-material/CloudDownloadOutlined';
import DarkModeIcon from '@mui/icons-material/DarkModeOutlined';
import LightModeIcon from '@mui/icons-material/LightModeOutlined';
import KeyIcon from '@mui/icons-material/VpnKeyOutlined';
import LogoutIcon from '@mui/icons-material/LogoutOutlined';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { useBranding } from '../theme/BrandingProvider';
import { NotificationToaster } from './NotificationToaster';
import { BarcodeScanner } from './BarcodeScanner';

const DRAWER_WIDTH = 260;
/** Wide enough for an icon and its hit area, and nothing else. */
const RAIL_WIDTH = 60;

interface NavItem {
  label: string;
  to: string;
  icon: React.ReactNode;
  /** Any one of these is enough to make the item useful. */
  permissions: string[];
  /**
   * Where this item's "new" shortcut goes. It appears only while the user is
   * already inside that module — a plus beside Assets is a genuine shortcut
   * when you are looking at an asset, and noise when you are in Devices.
   */
  createTo?: string;
  createPermissions?: string[];
}

interface NavSection {
  heading?: string;
  items: NavItem[];
  /** Folds under one clickable heading instead of listing everything always. */
  collapsible?: boolean;
  icon?: React.ReactNode;
}

const NAV: NavSection[] = [
  {
    items: [
      // One row, because it is now one page: the figures and the whole asset
      // list. Either permission is enough to open it -- a Purchaser has
      // asset:read and no dashboard:view -- and the page renders whichever
      // half they may see.
      {
        label: 'Dashboard',
        to: '/',
        icon: <DashboardIcon />,
        permissions: ['dashboard:view', 'asset:read'],
        createTo: '/assets/new',
        createPermissions: ['asset:write'],
      },
      {
        label: 'Locations',
        to: '/locations',
        icon: <PlaceIcon />,
        permissions: ['location:read'],
        createTo: '/locations?new=1',
        createPermissions: ['location:write'],
      },
      {
        label: 'Purchase Orders',
        to: '/purchase-orders',
        icon: <ShoppingCartIcon />,
        permissions: ['purchase_order:view'],
        createTo: '/purchase-orders/new',
        createPermissions: ['purchase_order:create'],
      },
      {
        label: 'Inventory Verification',
        to: '/verification',
        icon: <FactCheckIcon />,
        permissions: ['asset:write'],
      },
      { label: 'Reports', to: '/reports', icon: <AssessmentIcon />, permissions: ['report:view'] },
    ],
  },
  {
    // The things that are looked after regularly rather than set up once: the
    // catalogue, the shape of an asset, the people, and the record of what
    // everyone did. They sat under Settings, which put a weekly job behind a
    // fold meant for a yearly one -- and Audit History sat up with the work,
    // which is not what it is.
    //
    // Not collapsible: a fold is for things you rarely open, and the point of
    // moving these was that they are not that.
    heading: 'Manage',
    items: [
      {
        label: 'Categories & Fields',
        to: '/admin/categories',
        icon: <CategoryIcon />,
        permissions: ['category:manage'],
      },
      {
        label: 'Devices',
        to: '/admin/devices',
        icon: <RouterIcon />,
        permissions: ['asset:read'],
        createTo: '/admin/devices?new=1',
        createPermissions: ['category:manage'],
      },
      { label: 'Users', to: '/admin/users', icon: <PeopleIcon />, permissions: ['user:manage'] },
      { label: 'Audit History', to: '/audit', icon: <HistoryIcon />, permissions: ['audit:view'] },
    ],
  },
  {
    // One collapsible group rather than two flat sections. Everything here is
    // configuration somebody sets up once and then rarely touches, so it earns
    // a fold rather than eight permanent rows above the work.
    heading: 'Settings',
    icon: <SettingsIcon />,
    collapsible: true,
    items: [
      {
        label: 'Roles & Permissions',
        to: '/admin/roles',
        icon: <AdminPanelSettingsIcon />,
        permissions: ['role:manage'],
      },
      {
        label: 'Field Visibility Rules',
        to: '/admin/field-visibility',
        icon: <VisibilityOffIcon />,
        permissions: ['role:manage'],
      },
      {
        label: 'Notification Rules',
        to: '/settings/notification-rules',
        icon: <RuleIcon />,
        permissions: ['notification_rule:manage'],
      },
      {
        label: 'SMTP Settings',
        to: '/settings/email',
        icon: <MailIcon />,
        permissions: ['notification_rule:manage'],
      },
      {
        label: 'RADIUS',
        to: '/settings/radius',
        icon: <VpnKeyIcon />,
        permissions: ['user:manage'],
      },
      {
        label: 'Backups',
        to: '/settings/backups',
        icon: <BackupIcon />,
        permissions: ['backup:run'],
      },
      {
        label: 'Plugins',
        to: '/admin/plugins',
        icon: <ExtensionIcon />,
        permissions: ['plugin:manage'],
      },
      { label: 'Branding', to: '/admin/branding', icon: <PaletteIcon />, permissions: ['branding:manage'] },
    ],
  },
];

export function AppShell() {
  const theme = useTheme();
  const permanent = useMediaQuery(theme.breakpoints.up('md'));
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [hovering, setHovering] = useState(false);
  const [accountAnchor, setAccountAnchor] = useState<null | HTMLElement>(null);
  const { user, hasAny, signOut } = useAuth();
  const { organizationName, logoUrl, mode, toggleMode } = useBranding();
  const location = useLocation();
  const navigate = useNavigate();

  // Polled rather than pushed. A badge that is a minute stale is fine, and it
  // avoids a WebSocket for one integer -- the same reasoning Phase 9 §7 applies
  // to import and sync progress.
  const unread = useQuery({
    queryKey: ['notifications-unread'],
    queryFn: () => api.get<{ unread: number; latestId: number }>('/api/notifications/unread-count'),
    // Twenty seconds rather than sixty because the same answer drives the
    // on-screen popup, and a notice that arrives a minute after the fact is not
    // one anybody would call live.
    refetchInterval: 20_000,
    refetchOnWindowFocus: true,
  });

  // A rail by default, expanding on hover, so content gets the width most of
  // the time without anyone having to click anything to get it back.
  const expanded = !permanent || hovering;

  const sections = NAV.map((section) => ({
    ...section,
    items: section.items.filter((item) => hasAny(...item.permissions)),
  })).filter((section) => section.items.length > 0);

  // Longest match wins, rather than every prefix lighting up. /purchase-orders
  // is a real page and so is /purchase-orders/receiving; a plain startsWith
  // would highlight both at once while standing on the second.
  const activePath = sections
    .flatMap((section) => section.items.map((item) => item.to.split('?')[0]))
    .filter((path) =>
      path === '/'
        ? location.pathname === '/'
        : location.pathname === path || location.pathname.startsWith(path + '/'),
    )
    .sort((a, b) => b.length - a.length)[0];

  const isActive = (to: string) => to.split('?')[0] === activePath;

  // Open when you are already inside it, so navigating to a settings page and
  // then looking at the nav does not show the group you are standing in as shut.
  const insideSettings = sections.some(
    (section) => section.collapsible && section.items.some((item) => isActive(item.to)),
  );
  const [settingsOpen, setSettingsOpen] = useState(false);
  const showSettings = settingsOpen || insideSettings;

  const renderItem = (item: NavItem, indented: boolean) => {
    const active = isActive(item.to);
    const showCreate =
      expanded && active && item.createTo && hasAny(...(item.createPermissions ?? []));

    return (
      <ListItemButton
        key={item.to}
        selected={active}
        onClick={() => {
          navigate(item.to);
          setDrawerOpen(false);
        }}
        sx={{
          minHeight: 42,
          // A pill needs air around it or it reads as a full-bleed band again.
          mx: 1,
          px: expanded ? 1.5 : 1.25,
          pl: expanded && indented ? 3.5 : undefined,
          color: active ? 'primary.main' : 'text.primary',
        }}
      >
        <Tooltip title={expanded ? '' : item.label} placement="right">
          <ListItemIcon
            sx={{ minWidth: expanded ? 36 : 'auto', color: active ? 'primary.main' : undefined }}
          >
            {item.icon}
          </ListItemIcon>
        </Tooltip>
        {expanded && (
          <ListItemText
            primary={item.label}
            primaryTypographyProps={{
              noWrap: true,
              variant: 'body2',
              fontWeight: active ? 650 : 500,
            }}
          />
        )}
        {showCreate && (
          <Tooltip title={`New ${item.label.replace(/s$/, '').toLowerCase()}`}>
            <IconButton
              size="small"
              edge="end"
              onClick={(event) => {
                event.stopPropagation();
                navigate(item.createTo!);
                setDrawerOpen(false);
              }}
            >
              <AddIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
      </ListItemButton>
    );
  };

  const drawerContent = (
    <Box
      role="navigation"
      sx={{ overflowX: 'hidden', overflowY: 'auto' }}
      onMouseEnter={() => setHovering(true)}
      onMouseLeave={() => setHovering(false)}
    >
      {sections.map((section, index) => {
        if (!section.collapsible) {
          return (
            <List key={section.heading ?? index}>
              {/* A heading on a flat section is a label, not a control. In the
                  rail there is no room for the word, so the divider carries the
                  separation on its own rather than leaving the group looking
                  like a continuation of the one above. */}
              {section.heading && <Divider sx={{ mx: 2, mt: 1, mb: expanded ? 0 : 1 }} />}
              {section.heading && expanded && (
                <ListSubheader
                  disableSticky
                  sx={{
                    bgcolor: 'transparent',
                    lineHeight: 2.2,
                    fontSize: 11,
                    letterSpacing: '0.08em',
                    textTransform: 'uppercase',
                    color: 'text.secondary',
                  }}
                >
                  {section.heading}
                </ListSubheader>
              )}
              {section.items.map((item) => renderItem(item, false))}
            </List>
          );
        }

        return (
          <List key={section.heading ?? index} sx={{ px: 0 }}>
            <Divider sx={{ mx: 2, my: 1 }} />
            <ListItemButton
              // In the rail there is nothing to reveal, so the icon goes
              // straight to the first page rather than toggling a list nobody
              // can see. Expanded, it folds.
              onClick={() => {
                if (expanded) setSettingsOpen((open) => !open);
                else {
                  navigate(section.items[0].to);
                  setDrawerOpen(false);
                }
              }}
              selected={!expanded && insideSettings}
              sx={{ minHeight: 42, mx: 1, px: expanded ? 1.5 : 1.25 }}
            >
              <Tooltip title={expanded ? '' : (section.heading ?? '')} placement="right">
                <ListItemIcon
                  sx={{
                    minWidth: expanded ? 36 : 'auto',
                    color: insideSettings ? 'primary.main' : undefined,
                  }}
                >
                  {section.icon}
                </ListItemIcon>
              </Tooltip>
              {expanded && (
                <>
                  <ListItemText
                    primary={section.heading}
                    primaryTypographyProps={{
                      noWrap: true,
                      variant: 'body2',
                      fontWeight: insideSettings ? 650 : 500,
                    }}
                  />
                  {showSettings ? <ExpandLess /> : <ExpandMore />}
                </>
              )}
            </ListItemButton>

            <Collapse in={expanded && showSettings} unmountOnExit>
              {section.items.map((item) => renderItem(item, true))}
            </Collapse>
          </List>
        );
      })}
    </Box>
  );

  const width = permanent ? (expanded ? DRAWER_WIDTH : RAIL_WIDTH) : DRAWER_WIDTH;

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar position="fixed" sx={{ zIndex: theme.zIndex.drawer + 1 }}>
        <Toolbar sx={{ gap: 0.5 }}>
          {!permanent && (
            <IconButton color="inherit" edge="start" onClick={() => setDrawerOpen(true)} sx={{ mr: 1 }}>
              <MenuIcon />
            </IconButton>
          )}
          <Box
            component={RouterLink}
            to="/"
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: 1.25,
              color: 'inherit',
              textDecoration: 'none',
              flexGrow: 1,
              minWidth: 0,
            }}
          >
            {logoUrl ? (
              // In light mode the logo simply sits on the background. In dark
              // mode it gets a light plate back -- not for decoration, but
              // because most uploaded logos are dark ink on transparency and
              // would otherwise vanish entirely. The organisation's mark is the
              // one piece of branding dark mode keeps, so it has to stay
              // legible; the plate is the smallest thing that guarantees it
              // without asking anyone to upload a second file.
              <Box
                component="img"
                src={logoUrl}
                alt={organizationName}
                sx={{
                  height: 30,
                  maxWidth: 190,
                  objectFit: 'contain',
                  ...(mode === 'dark' && {
                    bgcolor: 'rgba(255, 255, 255, 0.92)',
                    borderRadius: 1,
                    px: 0.75,
                    py: 0.5,
                    boxSizing: 'content-box',
                  }),
                }}
              />
            ) : (
              <Box
                sx={{
                  width: 30,
                  height: 30,
                  borderRadius: 1.5,
                  bgcolor: 'primary.main',
                  color: 'primary.contrastText',
                  display: 'grid',
                  placeItems: 'center',
                  flexShrink: 0,
                }}
              >
                <InventoryIcon sx={{ fontSize: 18 }} />
              </Box>
            )}
            {/* The logo already says whose installation this is, so the name
                beside it was saying it twice. Without a logo there is nothing
                to read at all, so the product name stands in -- an unbranded
                install still needs a word in the corner. */}
            {!logoUrl && (
              <Typography variant="subtitle1" noWrap sx={{ display: { xs: 'none', sm: 'block' } }}>
                Inventory Manager
              </Typography>
            )}
          </Box>

          <Tooltip title="Notifications">
            <IconButton color="inherit" onClick={() => navigate('/notifications')}>
              <Badge badgeContent={unread.data?.unread ?? 0} color="error" max={99}>
                <NotificationsIcon />
              </Badge>
            </IconButton>
          </Tooltip>

          <Tooltip title={user?.username ?? 'Account'}>
            <IconButton color="inherit" onClick={(event) => setAccountAnchor(event.currentTarget)}>
              <AccountCircleIcon />
            </IconButton>
          </Tooltip>
          <Menu anchorEl={accountAnchor} open={Boolean(accountAnchor)} onClose={() => setAccountAnchor(null)}>
            <MenuItem disabled>
              <Box>
                <Typography variant="body2" fontWeight={600}>
                  {user?.username}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {user?.roles.join(', ') || 'No roles assigned'}
                </Typography>
              </Box>
            </MenuItem>
            <Divider />
            {/* Deliberately does not close the menu. Appearance is the one
                choice here you might want to try and reverse immediately, and
                having the menu vanish under you to do that is the difference
                between a toggle and a trip. */}
            <MenuItem onClick={toggleMode}>
              <ListItemIcon sx={{ minWidth: 36 }}>
                {mode === 'dark' ? <LightModeIcon fontSize="small" /> : <DarkModeIcon fontSize="small" />}
              </ListItemIcon>
              <ListItemText primary={mode === 'dark' ? 'Light mode' : 'Dark mode'} />
              <Switch
                edge="end"
                size="small"
                checked={mode === 'dark'}
                inputProps={{ 'aria-label': 'Dark mode' }}
                sx={{ ml: 1, pointerEvents: 'none' }}
              />
            </MenuItem>
            <Divider />
            <MenuItem
              onClick={() => {
                setAccountAnchor(null);
                navigate('/change-password');
              }}
            >
              <ListItemIcon sx={{ minWidth: 36 }}>
                <KeyIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText primary="Change password" />
            </MenuItem>
            <MenuItem onClick={() => void signOut()}>
              <ListItemIcon sx={{ minWidth: 36 }}>
                <LogoutIcon fontSize="small" />
              </ListItemIcon>
              <ListItemText primary="Sign out" />
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>

      <Drawer
        variant={permanent ? 'permanent' : 'temporary'}
        open={permanent || drawerOpen}
        onClose={() => setDrawerOpen(false)}
        ModalProps={{ keepMounted: true }}
        sx={{
          width: permanent ? RAIL_WIDTH : DRAWER_WIDTH,
          flexShrink: 0,
          '& .MuiDrawer-paper': {
            width,
            boxSizing: 'border-box',
            overflowX: 'hidden',
            // The rail expands over the content rather than pushing it, so
            // hovering the nav never reflows whatever you were reading.
            transition: theme.transitions.create('width', {
              easing: theme.transitions.easing.sharp,
              duration: theme.transitions.duration.shortest,
            }),
            ...(permanent && { zIndex: theme.zIndex.drawer, boxShadow: expanded ? 3 : 0 }),
          },
        }}
      >
        {permanent && <Toolbar />}
        {drawerContent}
      </Drawer>

      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, sm: 3 }, width: 0 }}>
        <Toolbar />
        <Outlet />
      </Box>

      {/* Outside the main column: it is fixed to the viewport, and nesting it in
          a scrolling region would only make that a coincidence. */}
      <NotificationToaster />

      {/* Listens on the window, so a tag scanned on any screen resolves. Renders
          nothing until a scan actually happens. */}
      <BarcodeScanner />
    </Box>
  );
}
