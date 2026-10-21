import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { operationsVisualFixture } from './operations-visual-fixture';

test.use({ locale: 'pt-BR', timezoneId: 'America/Sao_Paulo', reducedMotion: 'reduce' });
const widths = [320, 390, 1440] as const;
const realMap = process.env.BORA_OPS_QA_MAP_MODE !== 'unavailable';
const indicators = [
  ['Pedidos em andamento', '5'],
  ['Buscando entregador', '3'],
  ['Espera de 3 min ou mais', '2'],
  ['Prazo vencido', '2'],
  ['Prazo em até 5 min', '2'],
  ['Entregues na última hora', '1'],
] as const;
async function audit(page: Page, state: string, report: Record<string, unknown>) {
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
    `No horizontal overflow in ${state}`,
  ).toBe(true);
  for (const control of await page.locator('button:visible, input:visible, select:visible').all()) {
    await expect(
      control,
      `Visible control has an accessible name in ${state}`,
    ).toHaveAccessibleName(/\S/);
  }
  const duplicateIds = await page.evaluate(() => {
    const ids = [...document.querySelectorAll('[id]')].map((item) => item.id);
    return [...new Set(ids.filter((value, index) => ids.indexOf(value) !== index))];
  });
  expect(duplicateIds).toEqual([]);
  const accessibility = await page.evaluate(async () => {
    const axe = (
      window as Window & {
        axe?: {
          run: (
            context: Document,
            options: { resultTypes: string[] },
          ) => Promise<{
            testEngine: unknown;
            url: string;
            timestamp: string;
            toolOptions: unknown;
            violations: unknown[];
            incomplete: unknown[];
          }>;
        };
      }
    ).axe;
    if (!axe) throw new Error('The required local axe script did not load.');
    const result = await axe.run(document, { resultTypes: ['violations', 'incomplete'] });
    // Preserve every node/check, including target, html, any/all/none and relatedNodes,
    // so an incomplete result can be reviewed without rerunning or guessing its cause.
    return {
      testEngine: result.testEngine,
      url: result.url,
      timestamp: result.timestamp,
      toolOptions: result.toolOptions,
      violations: result.violations,
      incomplete: result.incomplete,
    };
  });
  report[state] = {
    horizontalOverflow: false,
    duplicateIds,
    accessibility,
    activeElement: await page.evaluate(() => ({
      tag: document.activeElement?.tagName,
      text: document.activeElement?.textContent?.trim().slice(0, 120),
    })),
  };
  expect(accessibility.violations, `Axe violations in ${state}`).toEqual([]);
}
async function capture(page: Page, testInfo: TestInfo, width: number, state: string) {
  const path = testInfo.outputPath(`ops-${width}-${state}.png`);
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
  await testInfo.attach(`ops-${width}-${state}`, { path, contentType: 'image/png' });
}

for (const width of widths)
  test(`operação visual e teclado em ${width}px sem autenticação real`, async ({
    page,
    baseURL,
  }, testInfo) => {
    test.skip(
      process.env.BORA_OPS_VISUAL_QA !== 'true',
      'Explicit isolated visual QA with mocked HTTP/WS.',
    );
    test.setTimeout(180_000);
    const origin = new URL(baseURL!);
    expect(['localhost', '127.0.0.1']).toContain(origin.hostname);
    expect(origin.protocol).toBe('http:');
    const axePath =
      process.env.BORA_AXE_SCRIPT_PATH ??
      createRequire(import.meta.url).resolve('axe-core/axe.min.js');
    // Visual QA is opt-in, but axe is mandatory when it is enabled. Missing/unreadable
    // sources fail before navigation rather than silently reducing the audit coverage.
    const axeSource = await readFile(axePath, 'utf8');
    const axeUrl = new URL('/__axe.js', origin).href;
    await page.route(axeUrl, (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/javascript; charset=utf-8',
        body: axeSource,
      }),
    );
    await page.setViewportSize({ width, height: 900 });
    // Date is deliberately two hours behind. Timers/performance remain real.
    await page.clock.setFixedTime(new Date('2026-10-20T11:00:00Z'));
    const fixture = await operationsVisualFixture(page);
    const report: Record<string, unknown> = {
      width,
      mapMode: realMap ? 'real' : 'unavailable',
      fixture: fixture.summary,
    };
    const errors: string[] = [],
      network: { url: string; status: number }[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => {
      if (
        message.type() === 'error' &&
        /Cannot update a component|while rendering|hydration|hydrating|Minified React|Content Security Policy|Refused to/i.test(
          message.text(),
        )
      )
        errors.push(message.text());
    });
    page.on('response', (response) => {
      if (/tiles\.openfreemap\.org|maplibre-gl-worker/.test(response.url()))
        network.push({ url: response.url(), status: response.status() });
    });
    if (!realMap)
      await page.route('https://tiles.openfreemap.org/**', (route) =>
        route.fulfill({ status: 503, body: '' }),
      );
    try {
      const navigation = await page.goto('/operacao');
      await page.addScriptTag({ url: axeUrl });
      const csp = navigation?.headers()['content-security-policy'] || '';
      report.csp = csp;
      if (process.env.BORA_OPS_QA_EXPECT_CSP === 'true') {
        expect(csp).toContain("script-src 'self'");
        expect(csp).toContain("worker-src 'self'");
        expect(csp).toContain('https://tiles.openfreemap.org');
      }
      const roles = page.getByRole('group', { name: 'Escolher papel', exact: true });
      await expect(roles.getByRole('button', { name: 'Operação', exact: true })).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      await expect(
        page.getByRole('heading', { name: 'Visão geral da operação', exact: true }),
      ).toBeVisible();
      await expect(
        page.getByRole('combobox', { name: 'Status do pedido', exact: true }),
      ).toHaveCount(0);
      expect(fixture.queries).toHaveLength(0);
      const cards = page.locator('.operations-indicators > div');
      await expect(cards).toHaveCount(6);
      for (let index = 0; index < indicators.length; index++) {
        await expect(cards.nth(index).locator('dt')).toHaveText(indicators[index]![0]);
        await expect(cards.nth(index).locator('dd')).toHaveText(indicators[index]![1]);
      }
      const map = page.locator('.operations-map-block');
      if (realMap) {
        await expect(
          map.getByRole('button', { name: 'Aproximar grupo central', exact: true }),
        ).toBeEnabled({ timeout: 45_000 });
        await expect(map.locator('canvas')).toBeVisible();
        await expect(map).toContainText('120 nesta área.');
      } else {
        await expect(map).toContainText('O mapa não carregou.');
        testInfo.annotations.push({
          type: 'map-unverified',
          description: 'Explicit fallback mode: no cluster/worker/CSP rendering claim.',
        });
      }
      await audit(page, 'overview', report);
      await capture(page, testInfo, width, 'overview');
      if (realMap) {
        // Metric scale is useful to the user and proves a real camera zoom without an instance hook.
        const scale = map.locator('.maplibregl-ctrl-scale');
        await expect(scale).toBeVisible();
        await expect(scale).toContainText(/\d/);
        const beforeScale = await scale.innerText();
        // Actual MapLibre source worker + rendered feature + expansion zoom.
        await expect(async () => {
          if (width === 320) {
            await map.locator('canvas').focus();
            await page.keyboard.press('Enter');
          } else {
            await map.getByRole('button', { name: 'Aproximar grupo central', exact: true }).focus();
            await page.keyboard.press('Enter');
          }
          await expect(
            map
              .getByRole('status')
              .filter({ hasText: /Mapa aproximado\. \d+ entregadores neste grupo\./ }),
          ).toBeVisible({ timeout: 750 });
        }).toPass({ timeout: 8_000, intervals: [250, 500] });
        await expect(scale).not.toHaveText(beforeScale!);
        const list = map.locator('details.operations-courier-list');
        await list.locator('summary').focus();
        await page.keyboard.press('Enter');
        await expect(list).toHaveAttribute('open', '');
        await expect(list.locator('li')).toHaveCount(10);
        const first = await list.locator('li').first().textContent();
        await list.getByRole('button', { name: 'Localizar', exact: true }).first().focus();
        await page.keyboard.press('Enter');
        await expect(list.locator('li').first()).toHaveText(first!);
        await list.getByRole('button', { name: 'Próxima', exact: true }).focus();
        await page.keyboard.press('Enter');
        await expect(list.getByText('Página 2 de 12', { exact: true })).toBeVisible();
        report.keyboardMap = {
          expand: 'actual rendered cluster',
          listPageSize: 10,
          preservedWhileLocating: true,
        };
      }
      await page.getByRole('button', { name: 'Ver pedidos', exact: true }).focus();
      await page.keyboard.press('Enter');
      await expect(page.getByRole('heading', { name: 'Pedidos', exact: true })).toBeFocused();
      const sorting = page.getByRole('combobox', { name: 'Ordenar pedidos', exact: true });
      await sorting.selectOption('WAITING');
      await expect(
        page.getByRole('combobox', { name: 'Status do pedido', exact: true }),
      ).toHaveValue('SEARCHING');
      const rows = page.locator('.order-list-button');
      await expect(rows).toHaveCount(3);
      expect(
        await rows.evaluateAll((items) => items.map((item) => item.getAttribute('data-order-id'))),
      ).toEqual(fixture.waitingIds);
      await expect(rows.first()).toContainText(/Aguardando há [5-9] min/);
      expect(fixture.queries.at(-1)!.searchParams.get('sort')).toBe('WAITING');
      expect(fixture.queries.at(-1)!.searchParams.get('status')).toBe('SEARCHING');
      const countBefore = fixture.queries.length;
      fixture.publishOrdersChanged();
      await expect(
        page.getByRole('status').filter({ hasText: 'Há atualizações disponíveis.' }),
      ).toBeVisible();
      expect(fixture.queries).toHaveLength(countBefore);
      expect(
        await rows.evaluateAll((items) => items.map((item) => item.getAttribute('data-order-id'))),
      ).toEqual(fixture.waitingIds);
      await audit(page, 'queue', report);
      await capture(page, testInfo, width, 'queue');
      await page
        .getByRole('combobox', { name: 'Status do pedido', exact: true })
        .selectOption('ASSIGNED');
      await expect(sorting).toHaveValue('RECENT');
      await page.getByLabel('Código do pedido', { exact: true }).fill(fixture.assigned.publicCode);
      await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
      const selected = page.locator(`.order-list-button[data-order-id="${fixture.assigned.id}"]`);
      await expect(selected).toBeVisible();
      await selected.focus();
      await page.keyboard.press('Enter');
      const detail = page.locator(`.order-detail[data-order-id="${fixture.assigned.id}"]`);
      await expect(
        detail.getByRole('heading', { name: 'Detalhe do pedido', exact: true }),
      ).toBeFocused();
      await detail.getByRole('button', { name: 'Cancelar pedido', exact: true }).click();
      await expect(
        detail.getByRole('heading', { name: 'Cancelar este pedido?', exact: true }),
      ).toBeFocused();
      await detail.getByRole('button', { name: 'Rever pedido', exact: true }).focus();
      await page.keyboard.press('Enter');
      await expect(
        detail.getByRole('heading', { name: 'Detalhe do pedido', exact: true }),
      ).toBeFocused();
      await detail.getByRole('button', { name: 'Redistribuir pedido', exact: true }).click();
      await expect(
        detail.getByRole('heading', { name: 'Redistribuir este pedido?', exact: true }),
      ).toBeFocused();
      await expect(detail.locator('.operations-confirmation')).toContainText(
        fixture.assigned.publicCode,
      );
      await expect(
        detail.getByRole('button', { name: 'Confirmar redistribuição', exact: true }),
      ).toBeEnabled();
      await audit(page, 'confirmation', report);
      await capture(page, testInfo, width, 'confirmation');
      await detail.getByRole('button', { name: 'Confirmar redistribuição', exact: true }).focus();
      await page.keyboard.press('Enter');
      await expect(detail.getByRole('alert')).toContainText('O pedido mudou.');
      expect(fixture.mutations).toHaveLength(1);
      expect(fixture.mutations[0]!.body).toEqual({ expectedVersion: fixture.assigned.version });
      await detail.getByRole('button', { name: 'Voltar', exact: true }).click();
      await expect(selected).toBeFocused();
      await expect(page.getByLabel('Código do pedido', { exact: true })).toHaveValue(
        fixture.assigned.publicCode,
      );
      await page.getByRole('button', { name: 'Visão geral', exact: true }).click();
      await expect(page.locator('.operations-indicators')).toBeVisible();
      fixture.revoke();
      await expect(
        page.getByRole('alert').filter({ hasText: 'Sua sessão da operação não está disponível.' }),
      ).toBeVisible({ timeout: 10_000 });
      await expect(page.locator('.operations-indicators')).toHaveCount(0);
      await expect(page.locator('.operations-map-surface')).toHaveCount(0);
      await expect(page.locator('.order-list-button')).toHaveCount(0);
      await expect(page.getByText(fixture.assigned.publicCode, { exact: true })).toHaveCount(0);
      await audit(page, 'revoked', report);
      await capture(page, testInfo, width, 'revoked');
      fixture.releaseRevokedSession();
      await expect(page.locator('.session-summary')).toHaveCount(0);
      await expect(page.locator('.operations-workspace')).toHaveCount(0);
      expect(fixture.logins).toBe(0);
      expect(fixture.unexpected).toEqual([]);
      expect(fixture.mutations).toHaveLength(1);
      expect(fixture.viewports.every((viewport) => viewport.scope === 'OPERATIONS')).toBe(true);
      if (realMap) {
        expect(fixture.viewports.length).toBeGreaterThan(0);
        expect(fixture.closedMapSockets).toBeGreaterThan(0);
        expect(
          network.some(
            (item) => item.url.includes('/planet/') && item.status >= 200 && item.status < 300,
          ),
        ).toBe(true);
      }
      expect(errors).toEqual([]);
    } finally {
      fixture.releaseRevokedSession();
      await testInfo.attach(`ops-${width}-qa-report`, {
        body: JSON.stringify(
          {
            ...report,
            errors,
            network,
            logins: fixture.logins,
            unexpected: fixture.unexpected,
            mutations: fixture.mutations,
            viewports: fixture.viewports,
            closedMapSockets: fixture.closedMapSockets,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });
    }
  });
