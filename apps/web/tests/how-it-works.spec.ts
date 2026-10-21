import { expect, test } from '@playwright/test';
import { auditAccessibility } from './accessibility';

for (const width of [320, 390, 1440]) {
  test(`Como funciona: navegação, foco e acessibilidade em ${width}px`, async ({
    page,
  }, testInfo) => {
    testInfo.annotations.push({
      type: 'integration',
      description: 'Navegação e áreas reais; sem interceptar API, sessão, mapa ou WebSocket.',
    });
    await page.setViewportSize({ width, height: 900 });
    const errors: string[] = [];
    const mutations: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    page.on('console', (message) => {
      if (
        message.type() === 'error' &&
        /hydration|hydrating|Minified React|Content Security Policy/i.test(message.text())
      )
        errors.push(message.text());
    });
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.pathname.startsWith('/api/v1/') && !['GET', 'HEAD'].includes(request.method()))
        mutations.push(`${request.method()} ${url.pathname}`);
    });

    await page.goto('/');
    await expect(page.getByRole('heading', { name: /Cada entrega começa/ })).toBeVisible();
    const entry =
      width <= 600
        ? page.getByRole('link', { name: 'Ver o passo a passo', exact: true })
        : page
            .getByRole('navigation', { name: 'Navegação principal', exact: true })
            .getByRole('link', { name: 'Como funciona', exact: true });
    await entry.click();
    await expect(page).toHaveURL(/\/como-funciona$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      'Uma entrega, do começo ao fim.',
    );

    const document = await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page).toHaveTitle('Como funciona | Bora');
    await expect(page.locator('meta[name="description"]')).toHaveCount(1);
    await expect(page.locator('meta[name="description"]')).toHaveAttribute(
      'content',
      /crie um pedido.*dados fictícios/,
    );
    // Qualify this route behind the same CSP used by the existing accessibility suite.
    const csp = document?.headers()['content-security-policy'];
    if (csp) expect(csp).toMatch(/script-src[^;]*'self'/);
    await testInfo.attach('document-security-policy', {
      body: JSON.stringify({ policy: csp ?? null }, null, 2),
      contentType: 'application/json',
    });
    await auditAccessibility(page, testInfo, `como-funciona-${width}`);

    await page.keyboard.press('Tab');
    await expect(
      page.getByRole('link', { name: 'Pular para o conteúdo', exact: true }),
    ).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('main')).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(
      page.getByRole('link', { name: 'Praticar uma entrega', exact: true }),
    ).toBeFocused();
    await page.keyboard.press('Enter');
    const training = page.getByRole('heading', { name: 'Seu primeiro treino.', exact: true });
    await expect(training).toBeFocused();
    await expect(training).toHaveCSS('outline-style', 'solid');
    await expect(training).toHaveCSS('outline-width', '2px');
    await page.keyboard.press('Tab');
    await expect(
      page.getByRole('link', { name: 'Ir para a área do entregador', exact: true }),
    ).toBeFocused();

    await page.getByRole('link', { name: 'Ver as etapas', exact: true }).click();
    const stages = page.getByRole('heading', {
      name: 'Cada passo tem sua confirmação.',
      exact: true,
    });
    await expect(stages).toBeFocused();
    await expect(stages).toHaveCSS('outline-style', 'none');
    await page.keyboard.press('Tab');
    await expect(
      page.getByRole('link', { name: 'Ir para a área do entregador', exact: true }),
    ).toBeFocused();
    expect(
      await page.locator(':focus').evaluate((element) => element.matches(':focus-visible')),
    ).toBe(true);

    for (const area of [
      { name: 'loja', path: '/loja', button: 'Sou loja' },
      { name: 'entregador', path: '/entregador', button: 'Sou entregador' },
      { name: 'operação', path: '/operacao', button: 'Operação' },
    ]) {
      await page
        .getByRole('navigation', { name: 'Áreas de demonstração', exact: true })
        .getByRole('link', { name: new RegExp(`Abrir área d[ao] ${area.name}`) })
        .click();
      await expect(page).toHaveURL(new RegExp(`${area.path}$`));
      const signIn = page.getByRole('button', { name: area.button, exact: true });
      await expect(signIn).toBeEnabled();
      await expect(signIn).not.toHaveAttribute('aria-pressed', 'true');
      await page.goBack();
      await expect(page).toHaveURL(/\/como-funciona/);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    }

    await page.getByRole('link', { name: 'Voltar à cidade', exact: true }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { name: /Cada entrega começa/ })).toBeVisible();
    expect(mutations).toEqual([]);
    expect(errors).toEqual([]);
    await testInfo.attach('how-it-works-navigation', {
      body: JSON.stringify({ width, mutations, errors }, null, 2),
      contentType: 'application/json',
    });
  });
}
