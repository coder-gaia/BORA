import { expect, test, type Locator, type Page } from '@playwright/test';
import { operationsVisualFixture } from './operations-visual-fixture';

async function expectHeadingFocus(heading: Locator, keyboard: boolean) {
  await expect(heading).toBeFocused();
  await expect
    .poll(() => heading.evaluate((element) => element.matches(':focus-visible')))
    .toBe(keyboard);
  await expect(heading).toHaveCSS('outline-style', keyboard ? 'solid' : 'none');
  if (keyboard) await expect(heading).toHaveCSS('outline-width', '2px');
}

async function activate(page: Page, control: Locator, keyboard: boolean) {
  if (keyboard) {
    await control.focus();
    await page.keyboard.press('Enter');
  } else await control.click();
}

for (const width of [320, 390, 1440]) {
  for (const role of ['STORE', 'OPERATIONS'] as const) {
    test(`foco por mouse e teclado em títulos de ${role} com ${width}px`, async ({
      page,
    }, testInfo) => {
      test.info().annotations.push({
        type: 'fixture',
        description: 'HTTP e WebSocket controlados; teste de foco real no navegador.',
      });
      await page.setViewportSize({ width, height: 900 });
      const fixture = await operationsVisualFixture(page);
      await page.route('https://tiles.openfreemap.org/**', (route) =>
        route.fulfill({ status: 503, body: '' }),
      );
      if (role === 'STORE') {
        await page.route('**/api/v1/auth/me', (route) =>
          route.fulfill({
            json: {
              authenticated: true,
              user: {
                id: '00000000-0000-4000-8000-000000000009',
                role,
                displayName: 'Loja do teste de foco',
                expiresAt: '2099-01-01T00:00:00Z',
                storeId: fixture.assigned.store.id,
                courierId: null,
              },
            },
          }),
        );
        await page.route('**/api/v1/stores/me', (route) =>
          route.fulfill({ json: fixture.assigned.store }),
        );
      }
      await page.goto(role === 'STORE' ? '/loja' : '/operacao');
      const listButton = page.getByRole('button', { name: 'Ver pedidos', exact: true });
      const primaryButton = page.getByRole('button', {
        name: role === 'STORE' ? 'Novo pedido' : 'Visão geral',
        exact: true,
      });
      const listTitle = page.getByRole('heading', { name: 'Pedidos', exact: true });
      const primaryTitle = page.getByRole('heading', {
        name: role === 'STORE' ? 'Novo pedido' : 'Visão geral da operação',
        exact: true,
      });
      await listButton.click();
      await expectHeadingFocus(listTitle, false);
      await primaryButton.click();
      await expectHeadingFocus(primaryTitle, false);
      const pointerScreenshot = testInfo.outputPath('titulo-mouse.png');
      await page.screenshot({
        path: pointerScreenshot,
        fullPage: true,
        animations: 'disabled',
      });
      await testInfo.attach('titulo-mouse', {
        path: pointerScreenshot,
        contentType: 'image/png',
      });
      await activate(page, listButton, true);
      await expectHeadingFocus(listTitle, true);
      // The heading remains a focus target, but does not enter the normal Tab sequence.
      await page.keyboard.press('Tab');
      const nextControl = page.locator(':focus');
      await expect(nextControl).not.toHaveJSProperty('tagName', 'H3');
      expect(await nextControl.evaluate((element) => element.matches(':focus-visible'))).toBe(true);
      await page.getByRole('combobox', { name: 'Mostrar', exact: true }).selectOption('ALL');
      await page.getByLabel('Código do pedido', { exact: true }).fill(fixture.assigned.publicCode);
      await page.getByRole('button', { name: 'Buscar pedido', exact: true }).click();
      const row = page.locator(`.order-list-button[data-order-id="${fixture.assigned.id}"]`);
      await activate(page, row, true);
      const detailTitle = page.getByRole('heading', { name: 'Detalhe do pedido', exact: true });
      await expectHeadingFocus(detailTitle, true);
      if (role === 'OPERATIONS') {
        await activate(
          page,
          page.getByRole('button', { name: 'Cancelar pedido', exact: true }),
          true,
        );
        await expectHeadingFocus(
          page.getByRole('heading', { name: 'Cancelar este pedido?', exact: true }),
          true,
        );
        await activate(page, page.getByRole('button', { name: 'Rever pedido', exact: true }), true);
        await expectHeadingFocus(detailTitle, true);
        await page.getByRole('button', { name: 'Redistribuir pedido', exact: true }).click();
        await expectHeadingFocus(
          page.getByRole('heading', { name: 'Redistribuir este pedido?', exact: true }),
          false,
        );
        await page.getByRole('button', { name: 'Rever pedido', exact: true }).click();
        await expectHeadingFocus(detailTitle, false);
      }
      await page.getByRole('button', { name: 'Voltar', exact: true }).click();
      await expect(row).toBeFocused();
      await row.click();
      await expectHeadingFocus(detailTitle, false);
      await page.getByRole('button', { name: 'Voltar', exact: true }).click();
      await expect(row).toBeFocused();
      await activate(page, primaryButton, true);
      await expectHeadingFocus(primaryTitle, true);
      const keyboardScreenshot = testInfo.outputPath('titulo-teclado.png');
      await page.screenshot({
        path: keyboardScreenshot,
        fullPage: true,
        animations: 'disabled',
      });
      await testInfo.attach('titulo-teclado', {
        path: keyboardScreenshot,
        contentType: 'image/png',
      });
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      expect(fixture.mutations).toEqual([]);
      expect(fixture.unexpected).toEqual([]);
    });
  }
}
