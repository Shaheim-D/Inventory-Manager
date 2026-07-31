import { useState } from 'react';
import { Link as RouterLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  AppBar,
  Box,
  Divider,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Menu,
  MenuItem,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';
import AddIcon from '@mui/icons-material/Add';
import DashboardIcon from '@mui/icons-material/SpaceDashboard';
import InventoryIcon from '@mui/icons-material/Inventory2';
import RouterIcon from '@mui/icons-material/Router';
import PlaceIcon from '@mui/icons-material/Place';
import FactCheckIcon from '@mui/icons-material/FactCheck';
import HistoryIcon from '@mui/icons-material/History';
import CategoryIcon from '@mui/icons-material/Category';
import PeopleIcon from '@mui/icons-material/People';
import AdminPanelSettingsIcon from '@mui/icons-material/AdminPanelSettings';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import PaletteIcon from '@mui/icons-material/Palette';
import { useAuth } from '../auth/AuthContext';
import { useBranding } from '../theme/BrandingProvider';

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
}

const NAV: NavSection[] = [
  {
    items: [
      { label: 'Dashboard', to: '/', icon: <DashboardIcon />, permissions: ['dashboard:view'] },
      {
        label: 'Assets',
        to: '/assets',
        icon: <InventoryIcon />,
        permissions: ['asset:read'],
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
        label: 'Inventory Verification',
        to: '/verification',
        icon: <FactCheckIcon />,
        permissions: ['asset:write'],
      },
      { label: 'Audit History', to: '/audit', icon: <HistoryIcon />, permissions: ['audit:view'] },
    ],
  },
  {
    heading: 'Admin',
    items: [
      {
        label: 'Devices',
        to: '/admin/devices',
        icon: <RouterIcon />,
        permissions: ['asset:read'],
        createTo: '/admin/devices?new=1',
        createPermissions: ['category:manage'],
      },
      {
        label: 'Categories & Custom Fields',
        to: '/admin/categories',
        icon: <CategoryIcon />,
        permissions: ['category:manage'],
      },
      { label: 'Users', to: '/admin/users', icon: <PeopleIcon />, permissions: ['user:manage'] },
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
  const { organizationName, logoUrl } = useBranding();
  const location = useLocation();
  const navigate = useNavigate();

  // A rail by default, expanding on hover, so content gets the width most of
  // the time without anyone having to click anything to get it back.
  const expanded = !permanent || hovering;

  const sections = NAV.map((section) => ({
    ...section,
    items: section.items.filter((item) => hasAny(...item.permissions)),
  })).filter((section) => section.items.length > 0);

  const isActive = (to: string) =>
    to === '/' ? location.pathname === '/' : location.pathname.startsWith(to.split('?')[0]);

  const drawerContent = (
    <Box
      role="navigation"
      sx={{ overflowX: 'hidden', overflowY: 'auto' }}
      onMouseEnter={() => setHovering(true)}
      onMouseLeave={() => setHovering(false)}
    >
      {sections.map((section, index) => (
        <List
          key={section.heading ?? index}
          subheader={
            section.heading && expanded ? (
              <ListSubheader disableSticky>{section.heading}</ListSubheader>
            ) : section.heading ? (
              <Divider sx={{ my: 1 }} />
            ) : undefined
          }
        >
          {section.items.map((item) => {
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
                sx={{ minHeight: 44, px: expanded ? 2 : 1.5 }}
              >
                <Tooltip title={expanded ? '' : item.label} placement="right">
                  <ListItemIcon
                    sx={{
                      minWidth: expanded ? 40 : 'auto',
                      color: active ? 'primary.main' : undefined,
                    }}
                  >
                    {item.icon}
                  </ListItemIcon>
                </Tooltip>
                {expanded && (
                  <ListItemText
                    primary={item.label}
                    primaryTypographyProps={{ noWrap: true, fontWeight: active ? 600 : 400 }}
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
          })}
        </List>
      ))}
    </Box>
  );

  const width = permanent ? (expanded ? DRAWER_WIDTH : RAIL_WIDTH) : DRAWER_WIDTH;

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar position="fixed" sx={{ zIndex: theme.zIndex.drawer + 1 }}>
        <Toolbar>
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
              gap: 1.5,
              color: 'inherit',
              textDecoration: 'none',
              flexGrow: 1,
            }}
          >
            {logoUrl && (
              <Box
                component="img"
                src={logoUrl}
                alt={organizationName}
                sx={{
                  height: 30,
                  maxWidth: 200,
                  objectFit: 'contain',
                  bgcolor: 'common.white',
                  borderRadius: 0.5,
                  p: 0.5,
                }}
              />
            )}
            <Typography variant="h6" noWrap sx={{ display: { xs: logoUrl ? 'none' : 'block', sm: 'block' } }}>
              Inventory Manager
            </Typography>
          </Box>

          <IconButton color="inherit" onClick={(event) => setAccountAnchor(event.currentTarget)}>
            <AccountCircleIcon />
          </IconButton>
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
            <MenuItem
              onClick={() => {
                setAccountAnchor(null);
                navigate('/change-password');
              }}
            >
              Change password
            </MenuItem>
            <MenuItem onClick={() => void signOut()}>Sign out</MenuItem>
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
    </Box>
  );
}
