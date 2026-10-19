import { readFile, readdir, writeFile } from 'node:fs/promises';
import { createServer } from 'vite';
const server = await createServer({
  server: { middlewareMode: true, hmr: false, watch: null },
  optimizeDeps: { noDiscovery: true, include: [] },
  appType: 'custom',
});
try {
  const { renderPage } = await server.ssrLoadModule('/src/prerender.tsx');
  let html = await readFile('dist/index.html', 'utf8');
  html = html.replace('<div id="root"></div>', `<div id="root">${await renderPage()}</div>`);
  const stylesheet = html.match(/<link rel="stylesheet" crossorigin href="([^"]+)">/);
  if (!stylesheet) throw new Error('Missing public stylesheet');
  const css = await readFile(`dist${stylesheet[1]}`, 'utf8');
  html = html.replace(stylesheet[0], `<style>${css}</style>`);
  const font = (await readdir('dist/assets')).find((name) =>
    /^inter-latin-wght-normal-.*\.woff2$/.test(name),
  );
  if (!font) throw new Error('Missing Latin font');
  html = html.replace(
    '</head>',
    `<link rel="preload" href="/assets/${font}" as="font" type="font/woff2" crossorigin>\n</head>`,
  );
  await writeFile('dist/index.html', html);
  console.log(
    'Prerendered public home with its critical CSS and Latin font preload; session and map remain client side.',
  );
} finally {
  await server.close();
}
