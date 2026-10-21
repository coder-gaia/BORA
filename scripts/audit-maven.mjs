import { readFile, writeFile } from 'node:fs/promises';

const args = process.argv.slice(2);
if (args.length < 3 || args.at(-2) !== '--output') {
  throw new Error(
    'Uso: node scripts/audit-maven.mjs dependencias.txt [...] --output relatorio.json',
  );
}
const packages = new Map();
const sourceFiles = [];
for (const file of args.slice(0, -2)) {
  const content = await readFile(file, 'utf8');
  let dependencies = 0;
  for (const line of content.split(/\r?\n/u)) {
    const match = /^\s+([^\s:]+):([^\s:]+):jar:(?:[^\s:]+:)?([^\s:]+):(compile|runtime)\b/u.exec(
      line,
    );
    if (!match) continue;
    dependencies++;
    const [, group, artifact, version] = match;
    packages.set(`${group}:${artifact}@${version}`, {
      package: { ecosystem: 'Maven', name: `${group}:${artifact}` },
      version,
    });
  }
  if (dependencies === 0) {
    throw new Error(
      `Nenhuma dependência Maven de execução encontrada em ${file}; auditoria recusada.`,
    );
  }
  sourceFiles.push({ path: file, dependencies });
}
if (packages.size === 0)
  throw new Error('Nenhuma dependência Maven encontrada; auditoria recusada.');
const queries = [...packages.values()].sort((a, b) =>
  `${a.package.name}@${a.version}`.localeCompare(`${b.package.name}@${b.version}`),
);
const startedAt = new Date().toISOString();
const response = await fetch('https://api.osv.dev/v1/querybatch', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ queries }),
  signal: AbortSignal.timeout(60_000),
});
if (!response.ok) throw new Error(`OSV respondeu HTTP ${response.status}; auditoria incompleta.`);
const result = await response.json();
if (!Array.isArray(result.results) || result.results.length !== queries.length) {
  throw new Error('Resposta OSV incompleta; não é possível aprovar a auditoria.');
}
const findings = [];
for (let i = 0; i < queries.length; i++) {
  const entry = result.results[i];
  if (
    !entry ||
    typeof entry !== 'object' ||
    Array.isArray(entry) ||
    Object.keys(entry).some((key) => !['vulns', 'next_page_token'].includes(key)) ||
    (entry.next_page_token !== undefined && typeof entry.next_page_token !== 'string') ||
    (entry.vulns !== undefined &&
      (!Array.isArray(entry.vulns) ||
        entry.vulns.some((vulnerability) => typeof vulnerability?.id !== 'string')))
  ) {
    throw new Error('Resposta OSV inválida; não é possível aprovar a auditoria.');
  }
  if (entry.next_page_token) {
    throw new Error('OSV paginou um resultado; investigar manualmente antes de aprovar.');
  }
  if (entry.vulns?.length) {
    findings.push({ ...queries[i], vulnerabilities: entry.vulns });
  }
}
const report = {
  startedAt,
  finishedAt: new Date().toISOString(),
  source: 'https://api.osv.dev/v1/querybatch',
  scope:
    'Dependências Maven compile/runtime exportadas com dependency:list -DincludeScope=runtime.',
  packagesChecked: queries.length,
  sourceFiles,
  packages: queries,
  findings,
  passed: findings.length === 0,
};
await writeFile(args.at(-1), `${JSON.stringify(report, null, 2)}\n`);
console.log(
  JSON.stringify({
    packagesChecked: report.packagesChecked,
    findings: findings.length,
    passed: report.passed,
  }),
);
if (!report.passed) process.exitCode = 1;
