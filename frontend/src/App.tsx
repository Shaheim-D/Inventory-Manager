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

        <Route path="/locations" element={guard(['location:read'], <LocationsPage />)} />
        <Route path="/verification" element={guard(['asset:write'], <VerificationPage />)} />
        <Route path="/audit" element={guard(['audit:view'], <AuditPage />)} />

        <Route path="/admin/categories" element={guard(['category:manage'], <CategoriesPage />)} />
        <Route path="/devices" element={guard(['asset:read'], <DevicesPage />)} />
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
