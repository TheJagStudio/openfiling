import type { Environment } from './environment.model';

/**
 * Production configuration. The SPA is served from the same origin as filing-api
 * (CloudFront → S3 for the app, `/api/*` behaviour → ALB), so every API call is relative.
 */
export const environment: Environment = {
  production: true,
  apiBaseUrl: '',
  appName: 'OpenFiling',
  swaggerUiUrl: '/swagger-ui.html',
  redactionPollMs: 3000,
  unreadPollMs: 30000,
  systemPollMs: 15000,
  refreshLeadMs: 60000,
};
