import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const checker = fileURLToPath(new URL('./check-rules.mjs', import.meta.url));
const validMessage = 'chore: bootstrap monorepo\n\nEstrutura inicial do produto.\n';

function repository(t) {
  const cwd = mkdtempSync(join(tmpdir(), 'bora-rules-'));
  t.after(() => {
    assert.equal(dirname(resolve(cwd)), resolve(tmpdir()));
    assert.ok(basename(cwd).startsWith('bora-rules-'));
    rmSync(cwd, { recursive: true, force: true });
  });
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (/^GIT_(?:AUTHOR|COMMITTER|DIR|WORK_TREE|INDEX_FILE|CONFIG)/u.test(key)) delete env[key];
  }
  env.GIT_CONFIG_NOSYSTEM = '1';
  env.GIT_CONFIG_GLOBAL = process.platform === 'win32' ? 'NUL' : '/dev/null';
  function git(...args) {
    const result = spawnSync('git', args, { cwd, env, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout;
  }
  git('init', '--initial-branch=main');
  git('config', 'user.name', 'Alexandre Gaia');
  git('config', 'user.email', 'alexandregaia911@gmail.com');
  git('config', 'commit.gpgsign', 'false');
  git('config', 'core.autocrlf', 'false');
  function check(message) {
    const args = [checker];
    if (message !== undefined) {
      const path = join(cwd, 'commit-message');
      writeFileSync(path, message);
      args.push('--commit-msg', path);
    }
    return spawnSync(process.execPath, args, { cwd, env, encoding: 'utf8' });
  }
  return { cwd, git, check };
}

test('valida o primeiro commit antes de existir histórico e aceita arquivos binários', (t) => {
  const repo = repository(t);
  writeFileSync(join(repo.cwd, 'README.md'), '# Bora\n');
  writeFileSync(join(repo.cwd, 'asset.bin'), Buffer.from([0, 255, 226, 128, 148]));
  repo.git('add', 'README.md', 'asset.bin');
  const result = repo.check(validMessage);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /1 arquivos de texto e 0 commits/u);
});

test('o hook verifica o conteúdo preparado, mesmo se a cópia de trabalho já foi corrigida', (t) => {
  const repo = repository(t);
  const path = join(repo.cwd, 'README.md');
  writeFileSync(path, `Bora ${String.fromCodePoint(0x2014)} entregas\n`);
  repo.git('add', 'README.md');
  writeFileSync(path, 'Bora: entregas\n');
  const result = repo.check(validMessage);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /README\.md:1/u);
});

test('a verificação inclui mensagens e identidades de todo o histórico', (t) => {
  const repo = repository(t);
  repo.git('config', 'user.name', 'Outra Pessoa');
  repo.git('config', 'user.email', 'outra@example.com');
  repo.git('commit', '--allow-empty', '-m', validMessage);
  repo.git('config', 'user.name', 'Alexandre Gaia');
  repo.git('config', 'user.email', 'alexandregaia911@gmail.com');
  repo.git('commit', '--allow-empty', '-m', validMessage);
  const result = repo.check();
  assert.equal(result.status, 1);
  assert.match(result.stderr, /autor: identidade/u);
  assert.match(result.stderr, /committer: identidade/u);
});

test('o hook bloqueia autor ou committer diferente do proprietário', (t) => {
  const repo = repository(t);
  repo.git('config', 'user.email', 'outra@example.com');
  const result = repo.check(validMessage);
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Autor atual/u);
  assert.match(result.stderr, /Committer atual/u);
});

test('mensagens exigem formato completo e rejeitam coautoria e traços proibidos', (t) => {
  const repo = repository(t);
  const invalidMessages = [
    'chore: Bootstrap monorepo\n\nEstrutura inicial.\n',
    'chore: bootstrap monorepo\n',
    'chore: bootstrap monorepo\n\nPrimeira linha.\nSegunda linha.\n',
    `${validMessage}\nCo-authored-by: Outra Pessoa <outra@example.com>\n`,
    `chore: bootstrap monorepo\n\nEstrutura ${String.fromCodePoint(0x2013)} inicial.\n`,
  ];
  for (const message of invalidMessages) {
    assert.equal(repo.check(message).status, 1, message);
  }
});
