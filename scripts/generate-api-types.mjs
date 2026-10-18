import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const args = process.argv.slice(2);
const check = args.includes('--check');
const schemaOption = args.find((arg) => arg.startsWith('--schema='));
if (args.some((arg) => arg !== '--check' && !arg.startsWith('--schema='))) {
  throw new Error('Uso: node scripts/generate-api-types.mjs [--check] [--schema=caminho]');
}
const schemaPath = path.resolve(
  root,
  schemaOption?.slice('--schema='.length) || 'docs/api/openapi.json',
);
const outputPath = path.join(root, 'apps/web/src/generated/api.d.ts');
const require = createRequire(
  new URL('../apps/web/tools/api-contract/package.json', import.meta.url),
);
const { default: openapiTS, astToString } = require('openapi-typescript');
const prettier = require('prettier');
const schema = JSON.parse(await readFile(schemaPath, 'utf8'));
const ast = await openapiTS(schema, { alphabetize: true });
const declarations = astToString(ast).replaceAll('/** Format:', '/** Formato:');
const source = `/* Tipos gerados do contrato da API. Execute npm run contract:generate na pasta apps/web. */\n${declarations}`;
const contents = await prettier.format(source, {
  ...(await prettier.resolveConfig(outputPath)),
  filepath: outputPath,
});
if (check) {
  const expected = await readFile(outputPath, 'utf8');
  if (expected.replaceAll('\r\n', '\n') !== contents) {
    console.error(
      'Os tipos da API estão desatualizados. Execute npm run contract:generate --prefix apps/web.',
    );
    process.exitCode = 1;
  } else {
    console.log('Os tipos TypeScript correspondem ao contrato da API.');
  }
} else {
  await mkdir(path.dirname(outputPath), { recursive: true });
  await writeFile(outputPath, contents, 'utf8');
  console.log('Tipos TypeScript da API atualizados.');
}
