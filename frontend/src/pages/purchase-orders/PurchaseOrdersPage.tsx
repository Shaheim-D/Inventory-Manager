import { Paper, Tab, Tabs } from '@mui/material';
import { useNavigate, useParams } from 'react-router-dom';
import { PageHeader } from '../../components/PageHeader';
import { useAuth } from '../../auth/AuthContext';
import { PurchaseOrderListPage } from './PurchaseOrderListPage';
import { ApprovalsPage } from './ApprovalsPage';
import { PurchasingPage } from './PurchasingPage';
import { ReceivingPage } from './ReceivingPage';

/**
 * Everything to do with purchase orders behind one nav entry.
 *
 * Approvals, purchasing and receiving were three top-level items, which read as
 * three modules when they are three steps of one thing. As tabs the order of
 * the work is visible on the screen — a request moves left to right — and the
 * navigation stops implying they are unrelated.
 *
 * Each tab is gated on the permission that makes it useful and simply is not
 * rendered otherwise, the same rule the nav itself follows. The tab lives in the
 * URL so a link to the receiving queue is still a link to the receiving queue.
 */
// Named for what is sitting in them rather than for the act performed there.
// "Approvals" and "Purchasing" read as filing cabinets; "Awaiting approval"
// says there is something in it waiting on you.
const TABS = [
  // Not "All orders" — the scope filter inside this tab already offers that,
  // and two controls with the same words mean different things.
  { key: 'orders', label: 'Orders', permission: 'purchase_order:view' },
  { key: 'approvals', label: 'Awaiting approval', permission: 'purchase_order:approve' },
  { key: 'purchasing', label: 'Awaiting purchase', permission: 'purchase_order:approve' },
  { key: 'receiving', label: 'Awaiting delivery', permission: 'purchase_order:receive' },
] as const;

export function PurchaseOrdersPage() {
  const { tab } = useParams();
  const navigate = useNavigate();
  const { has } = useAuth();

  const visible = TABS.filter((entry) => has(entry.permission));
  const current = visible.some((entry) => entry.key === tab) ? tab : visible[0]?.key;

  if (!current) return null;

  return (
    <>
      <PageHeader
        title="Purchase orders"
        subtitle="Request something, get it approved, buy it, and book it in when it turns up."
      />

      <Paper variant="outlined" sx={{ mb: 2 }}>
        <Tabs
          value={current}
          onChange={(_, next) => navigate(`/purchase-orders/${next}`)}
          variant="scrollable"
          scrollButtons="auto"
        >
          {visible.map((entry) => (
            <Tab key={entry.key} value={entry.key} label={entry.label} />
          ))}
        </Tabs>
      </Paper>

      {current === 'orders' && <PurchaseOrderListPage />}
      {current === 'approvals' && <ApprovalsPage />}
      {current === 'purchasing' && <PurchasingPage />}
      {current === 'receiving' && <ReceivingPage />}
    </>
  );
}
