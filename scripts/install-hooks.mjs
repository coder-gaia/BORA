import { spawnSync } from 'node:child_process';

const result = spawnSync('git', ['config', '--local', 'core.hooksPath', 'scripts/hooks'], {
  stdio: 'inherit',
});
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
console.log('Hook local de commit instalado.');
