import type { Routes } from '@angular/router';

import { adminGuard, authGuard, examinerGuard, guestGuard } from './core/auth/guards';

/** Every feature is lazy-loaded; titles feed OpenFilingTitleStrategy ("Title · OpenFiling"). */
export const routes: Routes = [
  { path: '', title: '', loadComponent: () => import('./features/landing/landing.page').then((m) => m.LandingPage) },
  {
    path: 'login',
    title: 'Sign in',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'register',
    title: 'Create account',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/register.page').then((m) => m.RegisterPage),
  },
  {
    path: 'dashboard',
    title: 'Dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./features/dashboard/dashboard.page').then((m) => m.DashboardPage),
  },
  {
    path: 'applications',
    canActivate: [authGuard],
    children: [
      {
        path: '',
        title: 'My applications',
        loadComponent: () => import('./features/applications/my-applications.page').then((m) => m.MyApplicationsPage),
      },
      {
        path: 'new',
        title: 'New application',
        loadComponent: () => import('./features/applications/new-application.page').then((m) => m.NewApplicationPage),
      },
      {
        path: ':id',
        title: 'Application',
        loadComponent: () => import('./features/applications/detail/application-detail.page').then((m) => m.ApplicationDetailPage),
      },
      {
        path: ':id/receipt',
        title: 'Filing receipt',
        loadComponent: () => import('./features/applications/receipt.page').then((m) => m.ReceiptPage),
      },
    ],
  },
  {
    path: 'examiner',
    title: 'Examiner queue',
    canActivate: [examinerGuard],
    loadComponent: () => import('./features/examiner/examiner-queue.page').then((m) => m.ExaminerQueuePage),
  },
  {
    path: 'search',
    title: 'Search published applications',
    loadComponent: () => import('./features/public/search.page').then((m) => m.SearchPage),
  },
  {
    path: 'status',
    children: [
      {
        path: '',
        title: 'Status lookup',
        loadComponent: () => import('./features/public/status-lookup.page').then((m) => m.StatusLookupPage),
      },
      {
        path: ':applicationNumber',
        title: 'Application status',
        loadComponent: () => import('./features/public/public-status.page').then((m) => m.PublicStatusPage),
      },
    ],
  },
  {
    path: 'notifications',
    title: 'Notifications',
    canActivate: [authGuard],
    loadComponent: () => import('./features/notifications/notifications.page').then((m) => m.NotificationsPage),
  },
  {
    path: 'account',
    title: 'Account & security',
    canActivate: [authGuard],
    loadComponent: () => import('./features/account/account.page').then((m) => m.AccountPage),
  },
  {
    path: 'admin',
    canActivate: [adminGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'users' },
      {
        path: 'users',
        title: 'Users · Admin',
        loadComponent: () => import('./features/admin/admin-users.page').then((m) => m.AdminUsersPage),
      },
      {
        path: 'audit',
        title: 'Audit log · Admin',
        loadComponent: () => import('./features/admin/admin-audit.page').then((m) => m.AdminAuditPage),
      },
      {
        path: 'system',
        title: 'System · Admin',
        loadComponent: () => import('./features/admin/admin-system.page').then((m) => m.AdminSystemPage),
      },
    ],
  },
  {
    path: '**',
    title: 'Page not found',
    loadComponent: () => import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
