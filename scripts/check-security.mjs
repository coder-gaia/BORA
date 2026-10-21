import { writeFile } from 'node:fs/promises';

// Smoke test of the public boundary. Never print cookies, tokens or response bodies.
const args = process.argv.slice(2);
if (![1, 3].includes(args.length) || (args.length === 3 && args[1] !== '--output')) {
  throw new Error('Uso: node scripts/check-security.mjs origem [--output arquivo.json]');
}
const origin = new URL(args[0]);
if (
  !['http:', 'https:'].includes(origin.protocol) ||
  origin.username ||
  origin.password ||
  origin.pathname !== '/' ||
  origin.search ||
  origin.hash ||
  (origin.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(origin.hostname))
) {
  throw new Error('Use uma origem HTTPS ou HTTP local, sem caminho nem credenciais.');
}
const checks = [];
const startedAt = new Date().toISOString();
function check(name, passed) {
  checks.push({ name, passed: Boolean(passed) });
}
async function probe(path, options) {
  const response = await fetch(new URL(path, origin), {
    redirect: 'error',
    signal: AbortSignal.timeout(15_000),
    ...options,
  });
  await response.body?.cancel();
  return { response };
}

try {
  const { response: home } = await probe('/');
  check(
    'Página inicial responde com HTML',
    home.status === 200 && home.headers.get('content-type')?.includes('text/html'),
  );
  check('Servidor não é identificado pelo cabeçalho Server', !home.headers.has('server'));
  check('MIME sniffing desativado', home.headers.get('x-content-type-options') === 'nosniff');
  check(
    'Referer limitado entre origens',
    home.headers.get('referrer-policy') === 'strict-origin-when-cross-origin',
  );
  if (origin.protocol === 'https:') {
    check(
      'HTTPS persistente com HSTS',
      /(?:^|;)\s*max-age=[1-9]\d*/u.test(home.headers.get('strict-transport-security') || ''),
    );
  }
  const csp = home.headers.get('content-security-policy') || '';
  check(
    'CSP bloqueia objetos e enquadramento externo',
    /(?:^|;)\s*object-src 'none'(?:;|$)/u.test(csp) &&
      /(?:^|;)\s*frame-ancestors 'none'(?:;|$)/u.test(csp),
  );
  const scriptPolicy = csp.split(';').find((directive) => /^\s*script-src\s/u.test(directive));
  check(
    'CSP não libera script arbitrário',
    /^\s*script-src\s+'self'\s*$/u.test(scriptPolicy || ''),
  );

  for (const path of [
    '/api/v1/auth/simulator',
    '/api/v1/simulator/scenario',
    '/actuator/env',
    '/actuator/metrics',
    '/actuator/heapdump',
  ]) {
    const { response } = await probe(path);
    check(`Fronteira pública bloqueia ${path}`, response.status === 404);
  }
  for (const path of ['/api/v1/orders', '/api/v1/ops/metrics', '/api/v1/courier/me']) {
    const { response } = await probe(path);
    check(`Sessão obrigatória em ${path}`, response.status === 401);
  }
  const { response: csrf } = await probe('/api/v1/auth/csrf');
  const cookies = csrf.headers.getSetCookie();
  const session = cookies.find((cookie) => cookie.startsWith('BORA_SESSION=')) || '';
  check(
    'Sessão anônima usa cookie HttpOnly e SameSite=Lax',
    csrf.status === 200 &&
      /;\s*HttpOnly(?:;|$)/iu.test(session) &&
      /;\s*SameSite=Lax(?:;|$)/iu.test(session),
  );
  if (origin.protocol === 'https:') {
    check('Cookie de produção exige HTTPS', /;\s*Secure(?:;|$)/iu.test(session));
  }
  // Missing CSRF must stop the request before creating a visitor account.
  const { response: denied } = await probe('/api/v1/auth/demo', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: 'STORE' }),
  });
  check('Entrada sem CSRF é recusada', denied.status === 403);
  const { response: cors } = await probe('/api/v1/auth/me', {
    headers: { Origin: 'https://untrusted.invalid' },
  });
  check('Origem externa não recebe acesso CORS', !cors.headers.has('access-control-allow-origin'));
} catch {
  check('Todas as consultas terminaram sem erro de transporte', false);
}
const report = {
  startedAt,
  finishedAt: new Date().toISOString(),
  origin: origin.origin,
  passed: checks.length > 0 && checks.every((entry) => entry.passed),
  checks,
};
const output = `${JSON.stringify(report, null, 2)}\n`;
if (args[2]) await writeFile(args[2], output);
process.stdout.write(output);
if (!report.passed) process.exitCode = 1;
