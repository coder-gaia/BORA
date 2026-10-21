import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { createConnection } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { parseArgs, promisify } from 'node:util';

const execute = promisify(execFile);
const sourceRoot = fileURLToPath(new URL('..', import.meta.url));
const { values } = parseArgs({
  options: {
    'docker-context': { type: 'string' },
    report: { type: 'string' },
    changes: { type: 'string', default: '20' },
  },
  allowPositionals: false,
});
const changes = Number(values.changes);
assert.ok(Number.isInteger(changes) && changes >= 2 && changes <= 100);
const dockerArguments = values['docker-context'] ? ['--context', values['docker-context']] : [];
const directory = await mkdtemp(join(tmpdir(), 'bora-caddy-policy-'));
const container = `bora-caddy-policy-${process.pid}-${Date.now()}`;
const configDirectory = join(directory, 'config');
const webDirectory = join(directory, 'web');
const caddyfile = await readFile(join(sourceRoot, 'infra/caddy/Caddyfile'), 'utf8');
const policy = (
  await readFile(join(sourceRoot, 'infra/caddy/content-security-policy'), 'utf8')
).trim();
const compose = await readFile(join(sourceRoot, 'infra/compose/compose.prod.yml'), 'utf8');
const image = compose.match(/\n  caddy:\r?\n    image: (caddy:[^\s]+)/)?.[1];
assert.ok(
  image?.includes('@sha256:'),
  'A imagem deve ser a mesma referência imutável da produção.',
);
const restrictivePolicy =
  "default-src 'none'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'";
const alternatePolicy = `${policy}; upgrade-insecure-requests`;
const report = {
  startedAt: new Date().toISOString(),
  image,
  caddyfileSha256: createHash('sha256').update(caddyfile).digest('hex'),
  policySha256: createHash('sha256').update(`${policy}\n`).digest('hex'),
  retries: 0,
  scope:
    'Caddyfile de produção, HTTP/1.1 local e rename em filesystem Linux, sem APIs ou dados do projeto',
  structuralReload: { requests: 0, failures: 0, results: [] },
  atomicPolicy: { changes, requests: 0, failures: 0, examples: [] },
};
let created = false;

async function docker(...arguments_) {
  const { stdout } = await execute('docker', [...dockerArguments, ...arguments_], {
    windowsHide: true,
    maxBuffer: 4 * 1024 * 1024,
  });
  return stdout.trim();
}

async function publishPolicy(value) {
  if (created) {
    // O rename do Windows pode falhar enquanto o bind mount está lendo o arquivo.
    // O deploy usa Linux; exercitar o rename no mesmo filesystem do proxy.
    await new Promise((resolve, reject) => {
      const child = execFile(
        'docker',
        [
          ...dockerArguments,
          'exec',
          '-i',
          container,
          'sh',
          '-c',
          'set -eu; temporary=$(mktemp /etc/caddy/.policy.XXXXXX); cat > "$temporary"; chmod 644 "$temporary"; mv -f "$temporary" /etc/caddy/content-security-policy',
        ],
        { windowsHide: true },
        (error) => (error ? reject(error) : resolve()),
      );
      child.stdin.on('error', reject);
      child.stdin.end(`${value}\n`);
    });
  } else {
    const temporary = join(configDirectory, `.policy-${process.pid}-${Date.now()}`);
    await writeFile(temporary, `${value}\n`, 'utf8');
    await rename(temporary, join(configDirectory, 'content-security-policy'));
  }
}

async function partialRequest(port) {
  const socket = createConnection({ host: '127.0.0.1', port });
  let data = '';
  let errorCode;
  let errorMessage;
  let remoteFin = false;
  const closed = new Promise((resolve) => socket.once('close', resolve));
  socket.on('data', (chunk) => {
    data += chunk.toString();
  });
  socket.on('error', (error) => {
    errorCode = error.code;
    errorMessage = error.message;
  });
  socket.on('end', () => {
    remoteFin = true;
  });
  await once(socket, 'connect');
  socket.setTimeout(5000, () => socket.destroy(new Error('Prazo do teste TCP excedido.')));
  socket.write('GET / HTTP/1.1\r\nHost: reload.test\r\nConnection: close\r\n');
  // Envia os headers antes da alteração, deixando somente a linha vazia pendente.
  await delay(150);
  return async () => {
    if (!socket.destroyed) socket.write('\r\n');
    await closed;
    return {
      success: /^HTTP\/1\.1 200\b/.test(data),
      bytesReceived: Buffer.byteLength(data),
      remoteFin,
      ...(errorCode ? { errorCode } : {}),
      ...(errorMessage ? { errorMessage } : {}),
    };
  };
}

try {
  await mkdir(configDirectory);
  await mkdir(webDirectory);
  await writeFile(join(configDirectory, 'Caddyfile'), caddyfile);
  await publishPolicy(policy);
  await writeFile(join(configDirectory, 'upstream-mode'), 'both\n');
  await writeFile(
    join(webDirectory, 'index.html'),
    '<!doctype html><title>Teste isolado Bora</title>',
  );
  const alternateCaddyfile = caddyfile.replace(
    'Referrer-Policy strict-origin-when-cross-origin',
    'Referrer-Policy no-referrer',
  );
  assert.notEqual(alternateCaddyfile, caddyfile);
  await writeFile(join(configDirectory, 'alternate.Caddyfile'), alternateCaddyfile);
  await docker(
    'run',
    '--detach',
    '--name',
    container,
    '--label',
    'bora.test=caddy-policy',
    '--memory',
    '96m',
    '--pids-limit',
    '100',
    '--read-only',
    '--tmpfs',
    '/config',
    '--tmpfs',
    '/data',
    '--tmpfs',
    '/tmp',
    '--tmpfs',
    '/etc/caddy',
    '--cap-drop',
    'ALL',
    '--cap-add',
    'NET_BIND_SERVICE',
    '--security-opt',
    'no-new-privileges',
    '--publish',
    '127.0.0.1::80',
    '--env',
    'SITE_ADDRESS=:80',
    '--env',
    'ACME_EMAIL=tests@example.test',
    '--mount',
    `type=bind,source=${configDirectory},target=/test-config,readonly`,
    '--mount',
    `type=bind,source=${webDirectory},target=/srv/bora/current,readonly`,
    '--entrypoint',
    '/bin/sh',
    image,
    '-c',
    'cp /test-config/* /etc/caddy/ && exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile',
  );
  created = true;
  const mapping = JSON.parse(await docker('inspect', container))[0];
  const port = Number(mapping.NetworkSettings.Ports['80/tcp'][0].HostPort);
  const origin = `http://127.0.0.1:${port}`;
  const request = async () => {
    const response = await fetch(origin, { signal: AbortSignal.timeout(5000) });
    await response.arrayBuffer();
    return response;
  };
  // Somente a preparação tolera o curto intervalo entre docker run e listen.
  let initialResponse;
  for (let attempt = 0; attempt < 30; attempt++) {
    try {
      initialResponse = await request();
      break;
    } catch {
      await delay(100);
    }
  }
  assert.equal(initialResponse?.status, 200);
  assert.equal(initialResponse.headers.get('content-security-policy'), policy);

  // Diagnóstico separado: reproduz a limitação estrutural; não transforma falhas em sucesso.
  for (let change = 0; change < 3; change++) {
    const finish = await partialRequest(port);
    const configuration = change % 2 === 0 ? 'alternate.Caddyfile' : 'Caddyfile';
    await docker(
      'exec',
      container,
      'caddy',
      'reload',
      '--config',
      `/etc/caddy/${configuration}`,
      '--adapter',
      'caddyfile',
    );
    await delay(150);
    const result = await finish();
    report.structuralReload.requests++;
    if (!result.success) report.structuralReload.failures++;
    report.structuralReload.results.push(result);
    const response = await request();
    assert.equal(response.status, 200);
    assert.equal(
      response.headers.get('referrer-policy'),
      change % 2 === 0 ? 'no-referrer' : 'strict-origin-when-cross-origin',
    );
  }
  report.structuralReload.qualification = report.structuralReload.failures ? 'FAIL' : 'PASS';
  report.structuralReload.reference = 'https://github.com/caddyserver/caddy/issues/8233';

  const readActiveConfiguration = () =>
    docker('exec', container, 'wget', '-qO-', 'http://127.0.0.1:2019/config/');
  const configurationBefore = await readActiveConfiguration();
  let stop = false;
  const loops = Array.from({ length: 3 }, async () => {
    while (!stop) {
      report.atomicPolicy.requests++;
      try {
        const response = await request();
        assert.equal(response.status, 200);
        assert.ok(
          [policy, alternatePolicy].includes(response.headers.get('content-security-policy')),
        );
      } catch (error) {
        report.atomicPolicy.failures++;
        if (report.atomicPolicy.examples.length < 5) {
          report.atomicPolicy.examples.push({
            at: new Date().toISOString(),
            message: error.message,
            code: error.cause?.code,
          });
        }
      }
      await delay(5);
    }
  });
  try {
    for (let change = 0; change < changes; change++) {
      await publishPolicy(change % 2 === 0 ? alternatePolicy : policy);
      await delay(100);
    }
  } finally {
    stop = true;
    await Promise.all(loops);
  }
  assert.ok(report.atomicPolicy.requests >= changes);
  assert.equal(report.atomicPolicy.failures, 0);
  const finishAtomic = await partialRequest(port);
  await publishPolicy(alternatePolicy);
  assert.equal((await finishAtomic()).success, true);
  await publishPolicy(policy);
  assert.equal((await request()).headers.get('content-security-policy'), policy);
  assert.equal(await readActiveConfiguration(), configurationBefore);
  const mappingAfter = JSON.parse(await docker('inspect', container))[0];
  assert.equal(mappingAfter.Id, mapping.Id);
  assert.equal(mappingAfter.State.StartedAt, mapping.State.StartedAt);
  report.atomicPolicy.activeConfigurationUnchanged = true;
  report.atomicPolicy.containerUnchanged = true;
  report.atomicPolicy.partialRequestSurvived = true;
  report.atomicPolicy.qualification = 'PASS';

  await docker('exec', container, 'rm', '/etc/caddy/content-security-policy');
  assert.equal((await request()).headers.get('content-security-policy'), restrictivePolicy);
  await publishPolicy('');
  assert.equal((await request()).headers.get('content-security-policy'), restrictivePolicy);
  await publishPolicy(policy);
  assert.equal((await request()).headers.get('content-security-policy'), policy);
  report.missingAndEmptyPolicyFailClosed = true;
  report.result = 'PASS_ATOMIC_POLICY_WITH_STRUCTURAL_RELOAD_REPORTED_SEPARATELY';
} catch (error) {
  report.result = 'FAIL';
  report.error = { message: error.message, stack: error.stack };
  process.exitCode = 1;
} finally {
  if (created) {
    report.caddyLogs = await execute('docker', [...dockerArguments, 'logs', container], {
      windowsHide: true,
      maxBuffer: 4 * 1024 * 1024,
    })
      .then(({ stdout, stderr }) => `${stdout}${stderr}`)
      .catch(() => 'Logs indisponíveis.');
    await docker('rm', '--force', container);
  }
  await rm(directory, { recursive: true, force: true });
  report.finishedAt = new Date().toISOString();
  if (values.report) await writeFile(values.report, `${JSON.stringify(report, null, 2)}\n`);
  const { caddyLogs: _, ...summary } = report;
  console.log(JSON.stringify(summary, null, 2));
}
