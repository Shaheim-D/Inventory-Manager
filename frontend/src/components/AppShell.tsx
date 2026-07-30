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
  ListItemText,
  ListSubheader,
  Menu,
  MenuItem,
  Toolbar,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';
import { useAuth } from '../auth/AuthContext';
import { useBranding } from '../theme/BrandingProvider';

const DRAWER_WIDTH = 260;

interface NavItem {
  label: string;
  to: string;
  /** Any one of these is enough to make the item useful. */
  permissions: string[];
}

interface NavSection {
  heading?: string;
  items: NavItem[];
}

/**
 * Navigation is gated by permission key, exactly as fields are: an item the
 * viewer could not use simply is not rendered. A section with nothing left in
 * it disappears too, so nobody sees an empty "Admin" heading.
 */
const NAV: NavSection[] = [
  {
    items: [
      { label: 'Dashboard', to: '/', permissions: ['dashboard:view'] },
      { label: 'Assets', to: '/assets', permissions: ['asset:read'] },
      { label: 'Locations', to: '/locations', permissions: ['location:read'] },
      { label: 'Inventory Verification', to: '/verification', permissions: ['asset:write'] },
      { label: 'Audit History', to: '/audit', permissions: ['audit:view'] },
    ],
  },
  {
    heading: 'Admin',
    items: [
      { label: 'Categories & Custom Fields', to: '/admin/categories', permissions: ['category:manage'] },
      { label: 'Users', to: '/admin/users', permissions: ['user:manage'] },
      { label: 'Roles & Permissions', to: '/admin/roles', permissions: ['role:manage'] },
      { label: 'Field Visibility Rules', to: '/admin/field-visibility', permissions: ['role:manage'] },
      { label: 'Branding', to: '/admin/branding', permissions: ['branding:manage'] },
    ],
  },
];

export function AppShell() {
  const theme = useTheme();
  const permanent = useMediaQuery(theme.breakpoints.up('md'));
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [accountAnchor, setAccountAnchor] = useState<null | HTMLElement>(null);
  const { user, hasAny, signOut } = useAuth();
  const { organizationName, logoUrl } = useBranding();
  const location = useLocation();
  const navigate = useNavigate();

  const sections = NAV.map((section) => ({
    ...section,
    items: section.items.filter((item) => hasAny(...item.permissions)),
  })).filter((section) => section.items.length > 0);

  const drawerContent = (
    <Box role="navigation" sx={{ overflow: 'auto' }}>
      {sections.map((section, index) => (
        <List
          key={section.heading ?? index}
          subheader={section.heading ? <ListSubheader disableSticky>{section.heading}</ListSubheader> : undefined}
        >
          {section.items.map((item) => (
            <ListItemButton
              key={item.to}
              selected={item.to === '/' ? location.pathname === '/' : location.pathname.startsWith(item.to)}
              onClick={() => {
                navigate(item.to);
                setDrawerOpen(false);
              }}
              sx={{ minHeight: 44 }}
            >
              <ListItemText primary={item.label} />
            </ListItemButton>
          ))}
          {index < sections.length - 1 && <Divider sx={{ mt: 1 }} />}
        </List>
      ))}
    </Box>
  );

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
            sx={{ display: 'flex', alignItems: 'center', gap: 1.5, color: 'inherit', textDecoration: 'none', flexGrow: 1 }}
          >
            {logoUrl && (
              <Box
                component="img"
                src={logoUrl}
                alt={organizationName}
                sx={{ height: 30, maxWidth: 200, objectFit: 'contain', bgcolor: 'common.white', borderRadius: 0.5, p: 0.5 }}
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
          width: DRAWER_WIDTH,
          flexShrink: 0,
          '& .MuiDrawer-paper': { width: DRAWER_WIDTH, boxSizing: 'border-box' },
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
