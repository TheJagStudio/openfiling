import type { Environment } from './environment.model';

/** `ng serve` configuration: relative URLs, proxied to filing-api on :8080 by proxy.conf.json. */
export const environment: Environment = {
  production: false,
  apiBaseUrl: '',
  appName: 'OpenFiling',
  swaggerUiUrl: '/swagger-ui.html',
  redactionPollMs: 3000,
  unreadPollMs: 30000,
  systemPollMs: 15000,
  refreshLeadMs: 60000,
};
