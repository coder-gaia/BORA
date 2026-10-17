import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const args = process.argv.slice(2);
if (args.length > 1 || (args.length === 1 && args[0] !== '--write')) {
  console.error('Uso: node scripts/format.mjs [--write]');
  process.exit(1);
}

const tracked = spawnSync('git', ['ls-files', '-z'], { encoding: 'utf8' });
if (tracked.error) throw tracked.error;
if (tracked.status !== 0) {
  console.error(tracked.stderr);
  process.exit(tracked.status ?? 1);
}

const files = tracked.stdout.split('\0').filter(Boolean);
if (!files.length) {
  console.error('Nenhum arquivo versionado para formatar.');
  process.exit(1);
}
const prettier = fileURLToPath(
  new URL('../apps/web/node_modules/prettier/bin/prettier.cjs', import.meta.url),
);
const result = spawnSync(
  process.execPath,
  [prettier, args[0] === '--write' ? '--write' : '--check', '--ignore-unknown', ...files],
  { stdio: 'inherit' },
);
if (result.error) throw result.error;
process.exit(result.status ?? 1);
