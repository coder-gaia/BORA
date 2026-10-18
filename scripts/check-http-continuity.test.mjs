import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import http from 'node:http';
import { once } from 'node:events';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

async function runProbe(loseSession) {
  let sessionReads = 0;
  let validatedLogin = false;
  const server = http.createServer(async (request, response) => {
    const json = (payload, status = 200) => {
      response.writeHead(status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(payload));
    };
    if (request.url === '/api/v1/auth/csrf') {
      response.setHeader('Set-Cookie', 'BORA_SESSION=before-login; HttpOnly; Path=/');
      return json({ token: 'masked-csrf', headerName: 'X-CSRF-TOKEN' });
    }
    if (request.url === '/api/v1/auth/demo') {
      let body = '';
      for await (const part of request) body += part;
      validatedLogin =
        request.headers.cookie === 'BORA_SESSION=before-login' &&
        request.headers['x-csrf-token'] === 'masked-csrf' &&
        JSON.parse(body).role === 'STORE';
      if (!validatedLogin) return json({}, 403);
      response.setHeader('Set-Cookie', 'BORA_SESSION=after-login; HttpOnly; Path=/');
      return json({ authenticated: true, user: { id: 'same-user', role: 'STORE' } });
    }
    if (request.url === '/api/v1/auth/me') {
      sessionReads++;
      if (request.headers.cookie !== 'BORA_SESSION=after-login') return json({}, 401);
      if (loseSession && sessionReads > 3) return json({ authenticated: false, user: null });
      return json({ authenticated: true, user: { id: 'same-user', role: 'STORE' } });
    }
    if (request.url === '/actuator/health/readiness') return json({ status: 'UP' });
    response.end('<!doctype html><title>Bora</title>');
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try {
    const child = spawn(
      process.execPath,
      [
        fileURLToPath(new URL('./check-http-continuity.mjs', import.meta.url)),
        '--url',
        `http://127.0.0.1:${server.address().port}`,
        '--duration',
        '0.25',
        '--interval',
        '10',
        '--timeout',
        '2000',
        '--demo-session',
      ],
      { windowsHide: true },
    );
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    const [exitCode] = await once(child, 'close');
    assert.equal(stderr, '');
    assert.equal(validatedLogin, true);
    const report = JSON.parse(stdout);
    assert.equal(report.sessionChecked, true);
    assert.equal(report.endpoints.length, 3);
    assert.ok(sessionReads > 3);
    return { exitCode, report };
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
}

test('preserva o cookie rotacionado e exige a mesma identidade em toda a medição', async () => {
  const { exitCode, report } = await runProbe(false);
  assert.equal(exitCode, 0);
  assert.equal(report.totals.failures, 0);
  assert.ok(report.totals.requests > 0);
});

test('detecta perda de sessão mesmo quando página e readiness continuam disponíveis', async () => {
  const { exitCode, report } = await runProbe(true);
  assert.equal(exitCode, 1);
  assert.ok(report.totals.failures > 0);
  const session = report.endpoints.find(({ path }) => path === '/api/v1/auth/me');
  assert.ok(session.failureExamples.some(({ reason }) => reason === 'session_changed'));
  assert.ok(
    report.endpoints
      .filter(({ path }) => path !== session.path)
      .every(({ failures }) => failures === 0),
  );
});
