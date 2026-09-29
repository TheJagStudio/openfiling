import { isPublicUrl } from './public-routes';

describe('isPublicUrl', () => {
  it('treats landing, search, status and auth pages as public', () => {
    for (const url of ['/', '/?x=1', '/search?q=battery', '/status', '/status/19000123', '/login?returnUrl=%2F', '/register']) {
      expect(isPublicUrl(url)).toBe(true);
    }
  });

  it('treats signed-in areas as protected', () => {
    for (const url of ['/dashboard', '/applications/abc', '/examiner', '/admin/users', '/statuses', '/account']) {
      expect(isPublicUrl(url)).toBe(false);
    }
  });
});
