import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const ownerName = 'Alexandre Gaia';
const ownerEmail = 'alexandregaia911@gmail.com';
const forbiddenDash = /[\u2012-\u2015]/u;
const titlePattern =
  /^(?:feat|fix|chore|docs|test|refactor|perf|build|ci)(?:\([a-z0-9]+(?:[/-][a-z0-9]+)*\))?: [a-z][a-z0-9 /:,.()'&+\-]*$/u;
const failures = [];

function git(args, { optional = false, binary = false } = {}) {
  const result = spawnSync('git', args, {
    encoding: binary ? undefined : 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    if (optional) return null;
    throw new Error(`Falha ao executar git ${args[0]}: ${String(result.stderr).trim()}`);
  }
  return result.stdout;
}

function checkText(text, label) {
  const match = forbiddenDash.exec(text);
  if (match) {
    const line = text.slice(0, match.index).split('\n').length;
    failures.push(`${label}:${line}: caractere de traço não permitido.`);
  }
}

function checkMessage(message, label) {
  const normalized = message.replace(/\r\n/gu, '\n').trimEnd();
  const lines = normalized.split('\n');
  if (!titlePattern.test(lines[0])) {
    failures.push(`${label}: título deve seguir Conventional Commits, em inglês e minúsculas.`);
  }
  if (lines.length !== 3 || lines[1] !== '' || !lines[2]?.trim()) {
    failures.push(`${label}: use título, linha em branco e uma única linha de corpo em pt-BR.`);
  }
  if (/^\s*co-authored-by\s*:/imu.test(normalized)) {
    failures.push(`${label}: trailer de coautoria não permitido.`);
  }
  checkText(normalized, label);
}

function checkIdentity(name, email, label) {
  if (name !== ownerName || email !== ownerEmail) {
    failures.push(`${label}: identidade deve ser ${ownerName} <${ownerEmail}>.`);
  }
}

function checkCurrentIdentity(kind) {
  const ident = git(['var', `GIT_${kind}_IDENT`]).trim();
  const match = /^(.*?) <([^>]+)> \d+ [+-]\d{4}$/u.exec(ident);
  if (!match) throw new Error(`Identidade Git inválida: ${kind}.`);
  checkIdentity(match[1], match[2], kind === 'AUTHOR' ? 'Autor atual' : 'Committer atual');
}

try {
  const args = process.argv.slice(2);
  let messageFile;
  if (args.length > 0) {
    if (args.length !== 2 || args[0] !== '--commit-msg') {
      throw new Error('Uso: node scripts/check-rules.mjs [--commit-msg arquivo]');
    }
    messageFile = args[1];
  }

  const files = git(['ls-files', '-z']).split('\0').filter(Boolean);
  let textFiles = 0;
  for (const file of files) {
    checkText(file, 'Nome de arquivo');
    const bytes = messageFile ? git(['show', `:${file}`], { binary: true }) : readFileSync(file);
    if (bytes.includes(0)) continue;
    let content;
    try {
      content = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    } catch {
      continue;
    }
    textFiles += 1;
    checkText(content, file);
  }

  const hasHead = git(['rev-parse', '--verify', 'HEAD'], { optional: true }) !== null;
  const commits = hasHead ? git(['log', '--format=%H']).trim().split('\n') : [];
  for (const commit of commits) {
    const [author, authorEmail, committer, committerEmail, message] = git([
      'show',
      '-s',
      '--format=%an%x00%ae%x00%cn%x00%ce%x00%B',
      commit,
    ]).split('\0');
    checkIdentity(author, authorEmail, `${commit.slice(0, 7)}: autor`);
    checkIdentity(committer, committerEmail, `${commit.slice(0, 7)}: committer`);
    checkMessage(message, commit.slice(0, 7));
  }

  if (messageFile) {
    const message = readFileSync(messageFile, 'utf8')
      .split(/\r?\n/u)
      .filter((line) => !line.startsWith('#'))
      .join('\n');
    checkCurrentIdentity('AUTHOR');
    checkCurrentIdentity('COMMITTER');
    checkMessage(message, 'Mensagem do commit');
  }

  if (failures.length) {
    console.error(failures.join('\n'));
    process.exitCode = 1;
  } else {
    console.log(`Regras verificadas: ${textFiles} arquivos de texto e ${commits.length} commits.`);
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
