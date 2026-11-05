import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const source = fileURLToPath(new URL('../infra/deploy/scheduled-deploy.sh', import.meta.url));
const version = 'a'.repeat(40);
const bash = process.platform === 'win32' ? 'C:/Program Files/Git/bin/bash.exe' : 'bash';

function shellPath(value) {
  const normalized = value.replaceAll('\\', '/');
  return process.platform === 'win32'
    ? normalized.replace(/^([A-Za-z]):\//u, (_, drive) => `/${drive.toLowerCase()}/`)
    : normalized;
}

function fields(path) {
  return readFileSync(path, 'utf8').split('\0');
}

function fixture(t, { omitDate = false, omitTimeout = false } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'bora-scheduled-deploy-'));
  t.after(() => {
    assert.equal(dirname(resolve(root)), resolve(tmpdir()));
    assert.ok(basename(root).startsWith('bora-scheduled-deploy-'));
    rmSync(root, { recursive: true, force: true });
  });
  const directory = join(root, 'release with spaces', 'infra', 'deploy');
  const bin = join(root, 'mock tools');
  mkdirSync(directory, { recursive: true });
  mkdirSync(bin);
  const wrapper = join(directory, 'scheduled-deploy.sh');
  copyFileSync(source, wrapper);
  const dateLog = join(root, 'date.log');
  const timeoutLog = join(root, 'timeout.log');
  const deployLog = join(root, 'deploy.log');
  const executable = (name, body) =>
    writeFileSync(join(bin, name), `#!/bin/bash\nset -euo pipefail\n${body}\n`, { mode: 0o755 });
  if (!omitDate) {
    executable(
      'date',
      `printf '%s\\0' "\${TZ-}" "$@" > "$BORA_TEST_DATE_LOG"
printf '%s' "$BORA_TEST_CLOCK"
exit "$BORA_TEST_DATE_EXIT"`,
    );
  }
  if (!omitTimeout) {
    executable(
      'timeout',
      `printf '%s\\0' "$@" > "$BORA_TEST_TIMEOUT_LOG"
if [[ $BORA_TEST_TIMEOUT_EXIT != 0 ]]; then exit "$BORA_TEST_TIMEOUT_EXIT"; fi
[[ $1 == --kill-after=60s && $2 == 20m ]]
shift 2
exec "$@"`,
    );
  }
  executable('bash', 'exec /bin/bash "$@"');
  writeFileSync(
    join(directory, 'deploy.sh'),
    `#!/bin/bash
set -euo pipefail
printf '%s\\0' "$#" "$1" "\${BORA_ROOT-}" "\${GHCR_USERNAME-}" > "$BORA_TEST_DEPLOY_LOG"
cat >> "$BORA_TEST_DEPLOY_LOG"
exit "$BORA_TEST_DEPLOY_EXIT"
`,
  );
  function run({
    clock = '1 10 00',
    dateExit = 0,
    timeoutExit = 0,
    deployExit = 0,
    args = [version],
    input = '',
    isolatedPath = false,
  } = {}) {
    return spawnSync(
      bash,
      [
        '--noprofile',
        '--norc',
        '-c',
        'export PATH="$BORA_TEST_BIN${BORA_TEST_KEEP_PATH:+:$PATH}"; exec /bin/bash "$BORA_TEST_WRAPPER" "$@"',
        'scheduled-deploy-test',
        ...args,
      ],
      {
        encoding: 'utf8',
        input,
        timeout: 10_000,
        env: {
          ...process.env,
          BORA_TEST_BIN: shellPath(bin),
          BORA_TEST_KEEP_PATH: isolatedPath ? '' : '1',
          BORA_TEST_WRAPPER: shellPath(wrapper),
          BORA_TEST_CLOCK: clock,
          BORA_TEST_DATE_EXIT: String(dateExit),
          BORA_TEST_TIMEOUT_EXIT: String(timeoutExit),
          BORA_TEST_DEPLOY_EXIT: String(deployExit),
          BORA_TEST_DATE_LOG: shellPath(dateLog),
          BORA_TEST_TIMEOUT_LOG: shellPath(timeoutLog),
          BORA_TEST_DEPLOY_LOG: shellPath(deployLog),
          BORA_ROOT: '/opt/bora',
          GHCR_USERNAME: 'test-user',
        },
      },
    );
  }
  return { run, dateLog, timeoutLog, deployLog, directory };
}

test('permite a abertura exata e o último minuto permitido nos cinco dias úteis', (t) => {
  for (const day of [1, 2, 3, 4, 5]) {
    for (const time of ['09 20', '21 44']) {
      const isolated = fixture(t);
      const result = isolated.run({ clock: `${day} ${time}` });
      assert.equal(result.status, 0, result.stderr);
      assert.ok(existsSync(isolated.deployLog));
    }
  }
});

test('bloqueia antes da abertura, a partir de 21h45 e durante o fim de semana', (t) => {
  for (const clock of ['1 09 19', '5 21 45', '3 00 00', '4 23 59', '6 12 00', '7 12 00']) {
    const isolated = fixture(t);
    const result = isolated.run({ clock });
    assert.equal(result.status, 1, clock);
    assert.match(result.stderr, /Publicação bloqueada/u);
    assert.ok(!existsSync(isolated.timeoutLog));
    assert.ok(!existsSync(isolated.deployLog));
  }
});

test('falha sem executar o deploy quando o relógio falha ou retorna dados inválidos', (t) => {
  for (const clock of [
    '',
    'inválido',
    '0 09 20',
    '8 09 20',
    '1 24 00',
    '1 09 60',
    '1 9 20',
    '1 09 20\nextra',
  ]) {
    const isolated = fixture(t);
    const result = isolated.run({ clock });
    assert.equal(result.status, 1, clock);
    assert.match(result.stderr, /horário inválido/u);
    assert.ok(!existsSync(isolated.timeoutLog));
  }
  const isolated = fixture(t);
  const result = isolated.run({ dateExit: 1 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Não foi possível conferir/u);
  assert.ok(!existsSync(isolated.timeoutLog));
});

test('rejeita argumentos ausentes, extras ou um SHA inválido antes de consultar o relógio', (t) => {
  for (const args of [
    [],
    ['a'.repeat(39)],
    ['a'.repeat(41)],
    ['A'.repeat(40)],
    ['$(touch unexpected)'],
    [version, '--override'],
  ]) {
    const isolated = fixture(t);
    const result = isolated.run({ args });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /Uso: scheduled-deploy/u);
    assert.ok(!existsSync(isolated.dateLog));
    assert.ok(!existsSync(isolated.timeoutLog));
  }
});

test('exige date e timeout disponíveis antes de executar qualquer publicação', (t) => {
  for (const missing of ['date', 'timeout']) {
    const isolated = fixture(t, {
      omitDate: missing === 'date',
      omitTimeout: missing === 'timeout',
    });
    const result = isolated.run({ isolatedPath: true });
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, new RegExp(`Comando ausente: ${missing}`, 'u'));
    assert.ok(!existsSync(isolated.deployLog));
  }
});

test('usa o relógio remoto de São Paulo e limita o processo completo sem consumir o token', (t) => {
  const isolated = fixture(t);
  const token = 'token-ficticio-para-o-teste\n';
  const result = isolated.run({ input: token });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(fields(isolated.dateLog), ['America/Sao_Paulo', '+%u %H %M', '']);
  assert.deepEqual(fields(isolated.timeoutLog), [
    '--kill-after=60s',
    '20m',
    'bash',
    `${shellPath(isolated.directory)}/deploy.sh`,
    version,
    '',
  ]);
  assert.deepEqual(fields(isolated.deployLog), ['1', version, '/opt/bora', 'test-user', token]);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, '');
});

test('preserva a falha do deploy e a expiração do timeout na resposta ao chamador', (t) => {
  const failed = fixture(t);
  assert.equal(failed.run({ deployExit: 17 }).status, 17);
  const expired = fixture(t);
  assert.equal(expired.run({ timeoutExit: 124 }).status, 124);
  assert.ok(!existsSync(expired.deployLog));
});
