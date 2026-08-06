import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { LinearProgress } from '@mui/material';
import { AppShell } from './components/AppShell';
import { RequirePermission } from './auth/RequirePermission';
import { LoginPage } from './pages/LoginPage';

/**
 * Route guards key on permission strings, never role names — the same rule the
 * API enforces independently. The guard here is convenience, not security.
 *
 * Every screen past the sign-in page is loaded on demand. The whole application
 * used to arrive as one file, so somebody opening the asset list also paid for
 * the report builder, the plugin admin screens and the import dialog before
 * seeing anything. Most users never open most of these — and the permission
 * guards mean many of them *cannot*.
 *
 * The shell and the sign-in page stay eagerly imported on purpose: they are on
 * the path to every screen, so splitting them would only add a round trip
 * before the first paint.
 */

const ChangePasswordPage = lazyPage(() => import('./pages/ChangePasswordPage'), 'ChangePasswordPage');
const DashboardPage = lazyPage(() => import('./pages/DashboardPage'), 'DashboardPage');
const AssetListPage = lazyPage(() => import('./pages/AssetListPage'), 'AssetListPage');
const AssetDetailPage = lazyPage(() => import('./pages/AssetDetailPage'), 'AssetDetailPage');
const AssetFormPage = lazyPage(() => import('./pages/AssetFormPage'), 'AssetFormPage');
const LocationsPage = lazyPage(() => import('./pages/LocationsPage'), 'LocationsPage');
const AuditPage = lazyPage(() => import('./pages/AuditPage'), 'AuditPage');
const VerificationPage = lazyPage(() => import('./pages/VerificationPage'), 'VerificationPage');
const NotificationsPage = lazyPage(() => import('./pages/NotificationsPage'), 'NotificationsPage');
const NotificationRulesPage = lazyPage(
  () => import('./pages/settings/NotificationRulesPage'), 'NotificationRulesPage');
const EmailSettingsPage = lazyPage(
  () => import('./pages/settings/EmailSettingsPage'), 'EmailSettingsPage');
const BackupsPage = lazyPage(() => import('./pages/settings/BackupsPage'), 'BackupsPage');
const PurchaseOrdersPage = lazyPage(
  () => import('./pages/purchase-orders/PurchaseOrdersPage'), 'PurchaseOrdersPage');
const PurchaseOrderFormPage = lazyPage(
  () => import('./pages/purchase-orders/PurchaseOrderFormPage'), 'PurchaseOrderFormPage');
const PurchaseOrderDetailPage = lazyPage(
  () => import('./pages/purchase-orders/PurchaseOrderDetailPage'), 'PurchaseOrderDetailPage');
const CategoriesPage = lazyPage(() => import('./pages/admin/CategoriesPage'), 'CategoriesPage');
const DevicesPage = lazyPage(() => import('./pages/admin/DevicesPage'), 'DevicesPage');
const ReportsPage = lazyPage(() => import('./pages/reports/ReportsPage'), 'ReportsPage');
const PluginsPage = lazyPage(() => import('./pages/admin/PluginsPage'), 'PluginsPage');
const PluginDetailPage = lazyPage(() => import('./pages/admin/PluginDetailPage'), 'PluginDetailPage');
const UsersPage = lazyPage(() => import('./pages/admin/UsersPage'), 'UsersPage');
const RolesPage = lazyPage(() => import('./pages/admin/RolesPage'), 'RolesPage');
const FieldVisibilityPage = lazyPage(
  () => import('./pages/admin/FieldVisibilityPage'), 'FieldVisibilityPage');
const BrandingPage = lazyPage(() => import('./pages/admin/BrandingPage'), 'BrandingPage');

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        element={
          <RequirePermission>
            <AppShell />
          </RequirePermission>
        }
      >
        <Route path="/" element={guard(['dashboard:view'], <DashboardPage />)} />
        <Route path="/change-password" element={page(<ChangePasswordPage />)} />

        <Route path="/assets" element={guard(['asset:read'], <AssetListPage />)} />
        <Route path="/assets/new" element={guard(['asset:write'], <AssetFormPage />)} />
        <Route path="/assets/:id" element={guard(['asset:read'], <AssetDetailPage />)} />
        <Route path="/assets/:id/edit" element={guard(['asset:write'], <AssetFormPage />)} />

        {/* One page with tabs. A single order lives under /order/:id so its id
            can never be mistaken for a tab name, whatever a tab is called later. */}
        <Route
          path="/purchase-orders"
          element={guard(['purchase_order:view'], <PurchaseOrdersPage />)}
        />
        <Route
          path="/purchase-orders/new"
          element={guard(['purchase_order:create'], <PurchaseOrderFormPage />)}
        />
        <Route
          path="/purchase-orders/order/:id"
          element={guard(['purchase_order:view'], <PurchaseOrderDetailPage />)}
        />
        <Route
          path="/purchase-orders/order/:id/edit"
          element={guard(['purchase_order:create'], <PurchaseOrderFormPage />)}
        />
        <Route
          path="/purchase-orders/:tab"
          element={guard(['purchase_order:view'], <PurchaseOrdersPage />)}
        />

        <Route path="/locations" element={guard(['location:read'], <LocationsPage />)} />
        <Route path="/verification" element={guard(['asset:write'], <VerificationPage />)} />
        <Route path="/reports" element={guard(['report:view'], <ReportsPage />)} />
        <Route path="/audit" element={guard(['audit:view'], <AuditPage />)} />
        {/* No permission: these rows are addressed to the caller, and every
            query behind them is scoped to whoever is signed in. */}
        <Route path="/notifications" element={page(<NotificationsPage />)} />

        <Route
          path="/settings/notification-rules"
          element={guard(['notification_rule:manage'], <NotificationRulesPage />)}
        />
        <Route
          path="/settings/email"
          element={guard(['notification_rule:manage'], <EmailSettingsPage />)}
        />
        <Route path="/settings/backups" element={guard(['backup:run'], <BackupsPage />)} />

        <Route path="/admin/categories" element={guard(['category:manage'], <CategoriesPage />)} />
        <Route path="/admin/plugins" element={guard(['plugin:manage'], <PluginsPage />)} />
        <Route path="/admin/plugins/:id" element={guard(['plugin:manage'], <PluginDetailPage />)} />
        <Route path="/admin/devices" element={guard(['asset:read'], <DevicesPage />)} />
        <Route path="/admin/users" element={guard(['user:manage'], <UsersPage />)} />
        <Route path="/admin/roles" element={guard(['role:manage'], <RolesPage />)} />
        <Route path="/admin/field-visibility" element={guard(['role:manage'], <FieldVisibilityPage />)} />
        <Route path="/admin/branding" element={guard(['branding:manage'], <BrandingPage />)} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

/**
 * Every screen is wrapped in its own Suspense boundary rather than one around
 * the whole router. A boundary above the shell would unmount the navigation
 * while a chunk loads, so moving between screens would blink the entire frame
 * — the fallback belongs where the content goes, not around the furniture.
 */
function guard(permissions: string[], element: React.ReactNode) {
  return <RequirePermission permissions={permissions}>{page(element)}</RequirePermission>;
}

/** A lazy screen with no permission of its own still needs the boundary. */
function page(element: React.ReactNode) {
  return <Suspense fallback={<LinearProgress />}>{element}</Suspense>;
}

/**
 * These screens are named exports, and `React.lazy` wants a module with a
 * default. This adapts one to the other so the pages do not have to change how
 * they are declared purely to be code-split.
 */
function lazyPage<K extends string>(
  load: () => Promise<Record<K, React.ComponentType>>,
  name: K,
) {
  return lazy(async () => ({ default: (await load())[name] }));
}
