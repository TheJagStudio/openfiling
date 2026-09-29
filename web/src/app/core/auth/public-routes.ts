/** Routes anonymous visitors may use; losing a session there should not force a sign-in. */
const PUBLIC_PREFIXES = ['/search', '/status', '/login', '/register'] as const;

export function isPublicUrl(url: string): boolean {
  const path = url.split(/[?#]/)[0] || '/';
  if (path === '/') return true;
  return PUBLIC_PREFIXES.some((p) => path === p || path.startsWith(`${p}/`));
}
