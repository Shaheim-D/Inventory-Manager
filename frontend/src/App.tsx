import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { RequirePermission } from './auth/RequirePermission';
import { LoginPage } from './pages/LoginPage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { DashboardPage } from './pages/DashboardPage';
import { AssetListPage } from './pages/AssetListPage';
import { AssetDetailPage } from './pages/AssetDetailPage';
import { AssetFormPage } from './pages/AssetFormPage';
import { LocationsPage } from './pages/LocationsPage';
import { AuditPage } from './pages/AuditPage';
import { VerificationPage } from './pages/VerificationPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { NotificationRulesPage } from './pages/settings/NotificationRulesPage';
import { EmailSettingsPage } from './pages/settings/EmailSettingsPage';
import { PurchaseOrdersPage } from './pages/purchase-orders/PurchaseOrdersPage';
import { PurchaseOrderFormPage } from './pages/purchase-orders/PurchaseOrderFormPage';
import { PurchaseOrderDetailPage } from './pages/purchase-orders/PurchaseOrderDetailPage';
import { CategoriesPage } from './pages/admin/CategoriesPage';
import { DevicesPage } from './pages/admin/DevicesPage';
import { UsersPage } from './pages/admin/UsersPage';
import { RolesPage } from './pages/admin/RolesPage';
import { FieldVisibilityPage } from './pages/admin/FieldVisibilityPage';
import { BrandingPage } from './pages/admin/BrandingPage';

/**
 * Route guards key on permission strings, never role names — the same rule the
 * API enforces independently. The guard here is convenience, not security.
 */
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
        <Route path="/change-password" element={<ChangePasswordPage />} />

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
        <Route path="/audit" element={guard(['audit:view'], <AuditPage />)} />
        {/* No permission: these rows are addressed to the caller, and every
            query behind them is scoped to whoever is signed in. */}
        <Route path="/notifications" element={<NotificationsPage />} />

        <Route
          path="/settings/notification-rules"
          element={guard(['notification_rule:manage'], <NotificationRulesPage />)}
        />
        <Route
          path="/settings/email"
          element={guard(['notification_rule:manage'], <EmailSettingsPage />)}
        />

        <Route path="/admin/categories" element={guard(['category:manage'], <CategoriesPage />)} />
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

function guard(permissions: string[], element: React.ReactNode) {
  return <RequirePermission permissions={permissions}>{element}</RequirePermission>;
}
