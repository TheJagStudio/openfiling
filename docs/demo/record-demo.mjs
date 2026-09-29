// OpenFiling 60-second product demo recorder.
// Prereq: stack running (..\..\dev.ps1 up). Run:  node record-demo.mjs  then  .\encode.ps1
// Captures CDP screencast frames (JPEG q100, 1920x1080) + an ffconcat timeline, and landing.png.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';

const BASE = 'http://localhost:4200';
const API = 'http://localhost:8080/api/v1';
const SAMPLES = path.resolve('../../samples');
const W = 1920, H = 1080;

// ---------- API helpers (seed a fresh draft so the video always shows a clean filing) ----------
async function api(method, p, token, body) {
  const r = await fetch(API + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) throw new Error(`${method} ${p} -> ${r.status} ${await r.text()}`);
  return r.status === 204 ? null : r.json();
}
const login = async (email, password) => (await api('POST', '/auth/login', null, { email, password })).accessToken;

const inv = await login('inventor@openfiling.local', 'Inventor#12345');
const draft = await api('POST', '/applications', inv, {
  title: 'Solid-state battery electrolyte with self-healing ceramic interphase',
  applicationType: 'UTILITY', entityStatus: 'SMALL',
  abstractText: 'A sulfide solid electrolyte with a self-healing ceramic interphase that suppresses lithium dendrites.',
  technologyField: 'Electrochemistry — energy storage', attorneyDocketNumber: 'OF-2026-017',
  inventors: [
    { givenName: 'Ada', familyName: 'Lovelace', city: 'Alexandria', state: 'VA', country: 'US', citizenship: 'US' },
    { givenName: 'Alan', familyName: 'Turing', city: 'Arlington', state: 'VA', country: 'US', citizenship: 'GB' },
  ],
});

// ---------- browser + capture ----------
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1, colorScheme: 'light' });
const page = await ctx.newPage();
page.setDefaultTimeout(5000);
const wait = (ms) => page.waitForTimeout(ms);

// Landing screenshot for the README.
await page.goto(BASE + '/', { waitUntil: 'networkidle' });
await wait(1200);
await page.screenshot({ path: 'landing.png' });

fs.rmSync('frames', { recursive: true, force: true }); fs.mkdirSync('frames');
const cdp = await ctx.newCDPSession(page);
const frames = [];
cdp.on('Page.screencastFrame', async ({ data, metadata, sessionId }) => {
  const f = `frames/${String(frames.length).padStart(5, '0')}.jpg`;
  fs.writeFileSync(f, Buffer.from(data, 'base64'));
  frames.push({ f, t: metadata.timestamp });
  await cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {});
});
await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 100, maxWidth: W, maxHeight: H, everyNthFrame: 1 });

async function caption(title, sub = '') {
  await page.evaluate(([t, s]) => {
    let el = document.getElementById('__cap');
    if (!el) {
      el = document.createElement('div');
      el.id = '__cap';
      el.style.cssText = 'position:fixed;left:50%;bottom:32px;transform:translateX(-50%);z-index:99999;' +
        'background:rgba(15,23,42,.94);color:#fff;padding:14px 28px;border-radius:14px;font:600 24px system-ui,sans-serif;' +
        'box-shadow:0 10px 36px rgba(0,0,0,.35);text-align:center;pointer-events:none;max-width:80%;border:1px solid rgba(250,204,21,.35)';
      document.body.appendChild(el);
    }
    el.innerHTML = t + (s ? `<div style="font:400 16px system-ui;opacity:.82;margin-top:5px">${s}</div>` : '');
  }, [title, sub]);
}
async function go(p, title, sub) {
  await page.goto(BASE + p, { waitUntil: 'networkidle' }).catch(() => {});
  await wait(400);
  if (title) await caption(title, sub);
}
async function scroll(px, steps = 6, ms = 150) {
  for (let i = 0; i < steps; i++) { await page.mouse.wheel(0, px / steps); await wait(ms); }
}
async function step(name, fn) { try { await fn(); } catch (e) { console.log('skip', name, e.message.split('\n')[0]); } }
async function signIn(email, password) {
  await page.evaluate(() => localStorage.clear());
  await go('/login');
  await page.type('#login-email', email, { delay: 18 });
  await page.type('#login-password', password, { delay: 18 });
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/dashboard|applications|examiner/, { timeout: 10000 }).catch(() => {});
  await page.waitForLoadState('networkidle');
}

// 1. Landing
await step('landing', async () => {
  await go('/', 'OpenFiling — secure patent application filing', 'Spring Boot 4 · Angular 22 + ZardUI · PostgreSQL · S3 · Lambda · Claude');
  await wait(2600); await scroll(900); await wait(1400);
});

// 2. Inventor dashboard
await step('dashboard', async () => {
  await signIn('inventor@openfiling.local', 'Inventor#12345');
  await caption('Inventor dashboard', 'JWT (RS256) + rotating refresh tokens — inventors only ever see their own filings');
  await wait(2600);
});

// 3. New application wizard (glimpse)
await step('wizard', async () => {
  await go('/applications/new', 'New application wizard', 'Bibliographic data → inventors → review');
  await wait(1800);
});

// 4. Direct-to-S3 upload
await step('upload', async () => {
  await go(`/applications/${draft.id}?tab=documents`, 'Direct-to-S3 upload with pre-signed URLs', 'The PDF never touches the API server — the browser PUTs straight to the bucket');
  await wait(900);
  await page.locator('input[type="file"]').setInputFiles(
    ['specification-with-pii.pdf', 'claims.pdf', 'abstract.pdf', 'oath-declaration.pdf'].map((f) => path.join(SAMPLES, f)));
  await wait(1200);
  await page.getByRole('button', { name: /Upload \d+ ready file/i }).click();
  await wait(2500);
  await caption('S3 event → redaction Lambda', 'PDFBox + rules + Claude find PII / CUI and burn redactions into a public copy');
  await page.getByText(/\d+ redactions?/i).first().waitFor({ timeout: 30000 }).catch(() => {});
  await scroll(500); await wait(2600);
});

// 5. Submit (S-signature)
await step('submit', async () => {
  await scroll(-2000, 4, 80);
  await caption('File the application', 'Required-documents checklist, certification and S-signature');
  await page.getByRole('button', { name: /^Submit application/i }).first().click();
  await wait(900);
  await page.getByRole('checkbox').first().check().catch(() => page.getByRole('checkbox').first().click());
  await page.locator('#sub-signature').type('/Ada Lovelace/', { delay: 30 });
  await wait(500);
  await page.getByRole('dialog').getByRole('button', { name: /submit|file/i }).last().click();
  await page.waitForLoadState('networkidle'); await wait(1800);
  await caption('Filed — application number, confirmation number and receipt issued');
  await wait(1800);
});

// 6. Examiner queue + actions
const filed = await api('GET', `/applications/${draft.id}`, inv).catch(() => null);
await step('examiner', async () => {
  await signIn('examiner@openfiling.local', 'Examiner#12345');
  await go('/examiner', 'Examiner queue (TSDR-style)', 'Examiners see every filed application — role-based access enforced server-side');
  await wait(2800);
  await go(`/applications/${draft.id}`, 'Prosecution workflow', '12 statuses · 13 role-checked actions · full history in PostgreSQL');
  await wait(900);
  await page.getByRole('button', { name: /^Claim/i }).first().click().catch(() => {});
  await wait(1200);
  const act = page.getByRole('button', { name: /pre-exam/i }).first();
  if (await act.count()) {
    await act.click(); await wait(700);
    const confirm = page.getByRole('dialog').getByRole('button').last();
    if (await confirm.count()) await confirm.click();
    await page.waitForLoadState('networkidle');
  }
  await wait(2200);
});

// Move the case forward behind the scenes so the public page has a publication.
await step('advance', async () => {
  const ex = await login('examiner@openfiling.local', 'Examiner#12345');
  for (const a of [{ action: 'START_PRE_EXAM' }, { action: 'DOCKET', artUnit: '1700' }, { action: 'START_EXAMINATION' }, { action: 'PUBLISH' }]) {
    const d = await api('GET', `/applications/${draft.id}`, ex);
    await api('POST', `/applications/${draft.id}/actions`, ex, { ...a, version: d.version }).catch(() => {});
  }
});

// 7. Public status + sanitized copies
await step('public', async () => {
  await page.evaluate(() => localStorage.clear());
  const num = (await api('GET', `/applications/${draft.id}`, inv)).applicationNumber ?? filed?.applicationNumber;
  await go(`/status/${num}`, 'Public status lookup — no sign-in', 'Only published applications, only sanitized public copies; restricted documents stay private');
  await wait(1500); await scroll(700); await wait(2600);
});

// 8. Admin
await step('admin', async () => {
  await signIn('admin@openfiling.local', 'Admin#12345');
  await go('/admin/system', 'Admin — system health & redaction analytics', 'Database, S3 buckets and the Lambda monitored live');
  await wait(2200); await scroll(600); await wait(1200);
  await go('/admin/audit', 'Audit log', 'Every login, download and workflow action — with request id, IP and user agent');
  await wait(2400);
});

// Outro
await step('outro', async () => {
  await page.evaluate(() => localStorage.clear());
  await go('/', 'OpenFiling', 'Runs locally · maps 1:1 to AWS — S3 pre-signed URLs, S3 events → Lambda, RDS, ECS');
  await wait(2600);
});

await cdp.send('Page.stopScreencast');
const end = Date.now() / 1000;
let list = 'ffconcat version 1.0\n';
frames.forEach((fr, i) => {
  const next = i + 1 < frames.length ? frames[i + 1].t : end;
  list += `file '${fr.f}'\nduration ${Math.max(next - fr.t, 0.001).toFixed(4)}\n`;
});
list += `file '${frames.at(-1).f}'\n`;
fs.writeFileSync('frames.txt', list);
console.log('frames', frames.length, 'seconds', (end - frames[0].t).toFixed(2));
await ctx.close();
await browser.close();
