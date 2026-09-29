export interface NavItem {
  label: string;
  path: string;
  icon: string;
  /** Exact-match active state (for `/`). */
  exact?: boolean;
  description: string;
}

export const PUBLIC_NAV: readonly NavItem[] = [
  { label: 'Home', path: '/', icon: 'lucideHouse', exact: true, description: 'About OpenFiling' },
  { label: 'Search', path: '/search', icon: 'lucideSearch', description: 'Published applications and patents' },
  { label: 'Status lookup', path: '/status', icon: 'lucideScanSearch', description: 'TSDR-style status by application number' },
];

export const MEMBER_NAV: readonly NavItem[] = [
  { label: 'Dashboard', path: '/dashboard', icon: 'lucideLayoutDashboard', description: 'Your filings at a glance' },
  { label: 'My applications', path: '/applications', icon: 'lucideFolderOpen', description: 'Drafts and filed applications' },
];

export const EXAMINER_NAV: NavItem = {
  label: 'Examiner queue',
  path: '/examiner',
  icon: 'lucideInbox',
  description: 'Filed applications awaiting action',
};

export const ADMIN_NAV: readonly NavItem[] = [
  { label: 'Users', path: '/admin/users', icon: 'lucideUsers', description: 'Accounts, roles and lockouts' },
  { label: 'Audit log', path: '/admin/audit', icon: 'lucideScrollText', description: 'Every sensitive action' },
  { label: 'System', path: '/admin/system', icon: 'lucideActivity', description: 'Component health and redaction stats' },
];
