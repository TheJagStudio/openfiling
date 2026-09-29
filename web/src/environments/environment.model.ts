export interface Environment {
  production: boolean;
  /** Prefix for all API calls. Empty = same origin (dev server proxies to filing-api :8080). */
  apiBaseUrl: string;
  appName: string;
  /** springdoc Swagger UI of filing-api. */
  swaggerUiUrl: string;
  /** Poll interval (ms) for a document whose redaction is PENDING/PROCESSING. */
  redactionPollMs: number;
  /** Poll interval (ms) for the unread-notification badge. */
  unreadPollMs: number;
  /** Poll interval (ms) for the admin system page. */
  systemPollMs: number;
  /** Refresh the access token this many ms before it expires. */
  refreshLeadMs: number;
}
