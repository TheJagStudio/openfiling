/** Only same-app absolute paths are allowed as post-login redirects (no open redirects). */
export function safeReturnUrl(raw: string | null | undefined): string {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) return '/';
  if (raw.startsWith('/login') || raw.startsWith('/register')) return '/';
  return raw;
}
