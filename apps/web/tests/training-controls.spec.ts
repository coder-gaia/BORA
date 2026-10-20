import { expect, test } from '@playwright/test';
import { trainingFixture } from './training-fixture';

test('abre a área do entregador por endereço e reload sem trocar de papel automaticamente', async ({
  page,
}) => {
  const failures: string[] = [];
  let logins = 0;
  page.on('pageerror', (error) => failures.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error' && /hydration|hydrating|Minified React/i.test(message.text()))
      failures.push(message.text());
  });
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/v1/auth/demo') logins++;
  });
  await page.route('**/api/v1/auth/me', (route) =>
    route.fulfill({ json: { authenticated: false, user: null } }),
  );
  await page.goto('/entregador');
  await expect(page.getByRole('button', { name: 'Sou entregador', exact: true })).toBeEnabled();
  await expect(page.getByRole('heading', { name: 'Seu trabalho como entregador' })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole('button', { name: 'Sou entregador', exact: true })).toHaveAttribute(
    'aria-pressed',
    'false',
  );
  await page.getByRole('link', { name: 'Voltar à cidade', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Cada entrega começa com um bora.' }),
  ).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('button', { name: 'Sou entregador', exact: true })).toBeEnabled();
  expect(logins).toBe(0);
  expect(failures).toEqual([]);
});

for (const offset of [-3_600_000, 3_600_000])
  test(`oferta usa relógio remoto e encerra as ações com desvio local de ${offset / 3_600_000} h`, async ({
    page,
  }) => {
    const state = await trainingFixture(page, offset);
    await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toBeEnabled();
    await expect(page.locator('.offer-countdown')).toContainText(/Responda em até (2[0-9]|30) s/);
    expect(state.details).toEqual([]);
    expect(state.subscriptions).toEqual([]);
    await page.clock.install();
    state.advanceClock(31_000);
    await page.clock.runFor(31_000);
    await expect(page.getByRole('button', { name: 'Aceitar oferta', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Recusar oferta', exact: true })).toBeDisabled();
  });

test('recupera o mesmo treino após resposta perdida e reload, sem detalhe antes do aceite, e encerra depois de recusar', async ({
  page,
}) => {
  const state = await trainingFixture(page);
  state.loseNextResponse();
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('conexão foi interrompida');
  await page.reload();
  await page.getByRole('button', { name: 'Recuperar treino', exact: true }).click();
  await expect(page.getByText('Código deste treino', { exact: true })).toBeVisible();
  expect(state.committed).toBe(1);
  expect(state.keys).toHaveLength(2);
  expect(state.keys[0]).toBe(state.keys[1]);
  expect(state.details).toEqual([]);
  expect(state.subscriptions).toEqual([]);
  expect(await page.evaluate(() => Object.values(sessionStorage))).not.toContain('824019');
  await page.getByRole('button', { name: 'Recusar oferta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Encerrar treino', exact: true }).click();
  await expect(page.getByText('Estado: Cancelado.', { exact: false })).toBeVisible();
  expect(
    await page.evaluate(() =>
      Object.keys(sessionStorage).filter((key) => key.startsWith('bora:training-intent:')),
    ),
  ).toEqual([]);
});

test('direção só começa por clique, pausa um GPS em voo e exige confirmação persistida antes de retomar', async ({
  page,
}) => {
  const state = await trainingFixture(page);
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  const drive = page.getByRole('button', { name: 'Dirigir até a loja', exact: true });
  await expect(drive).toBeEnabled();
  expect(state.gps).toEqual([]);
  state.holdPersistence();
  state.holdGps();
  await drive.click();
  await expect.poll(() => state.gps.length).toBe(1);
  await page.waitForTimeout(2_100);
  expect(state.gps).toHaveLength(1);
  await page.getByRole('button', { name: 'Pausar direção', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Confirmar posição enviada', exact: true }),
  ).toBeEnabled();
  await expect(page.getByRole('button', { name: 'Retomar direção', exact: true })).toBeDisabled();
  state.releaseGps();
  await page.getByRole('button', { name: 'Confirmar posição enviada', exact: true }).click();
  await expect.poll(() => state.gps.length).toBe(2);
  expect(state.gps[1]).toEqual(state.gps[0]);
  await expect(page.locator('.position-receipt')).toContainText('Aguardando confirmação');
  await expect(page.getByRole('button', { name: 'Retomar direção', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Enviar posição', exact: true })).toBeDisabled();
  state.persist();
  await page.getByRole('button', { name: 'Atualizar trabalho', exact: true }).click();
  await expect(page.locator('.position-receipt')).toHaveText('Posição confirmada pelo servidor.');
  await expect(page.getByRole('button', { name: 'Retomar direção', exact: true })).toBeEnabled();
  state.automaticPersistence();
  state.holdRoute();
  await page.getByRole('button', { name: 'Retomar direção', exact: true }).click();
  await expect.poll(() => state.routePending).toBe(true);
  await page.getByRole('button', { name: 'Pausar direção', exact: true }).click();
  state.releaseRoute();
  await page.waitForTimeout(2_100);
  expect(state.gps).toHaveLength(3);
  await page.goto('/loja');
  await page.waitForTimeout(2_100);
  expect(state.gps).toHaveLength(3);
});

test('aba oculta e perda de conexão pausam a direção sem retomada automática', async ({ page }) => {
  const state = await trainingFixture(page);
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Dirigir até a loja', exact: true })).toBeEnabled();
  state.holdRoute();
  await page.getByRole('button', { name: 'Dirigir até a loja', exact: true }).click();
  await expect.poll(() => state.routePending).toBe(true);
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(page.getByRole('button', { name: 'Retomar direção', exact: true })).toBeVisible();
  state.releaseRoute();
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.waitForTimeout(2_100);
  expect(state.gps).toHaveLength(1);
  expect(state.gps[0]).toEqual({ latitude: -23.5799, longitude: -46.6671 });
  state.holdRoute();
  await page.getByRole('button', { name: 'Retomar direção', exact: true }).click();
  await expect.poll(() => state.routePending).toBe(true);
  await page.evaluate(() => window.dispatchEvent(new Event('offline')));
  await expect(page.getByRole('button', { name: 'Retomar direção', exact: true })).toBeVisible();
  state.releaseRoute();
  await page.evaluate(() => window.dispatchEvent(new Event('online')));
  await page.waitForTimeout(2_100);
  expect(state.gps).toHaveLength(2);
  expect(state.gps[1]).toEqual(state.gps[0]);
});

test('dois treinos seguidos mantêm código, pedido e linha do tempo da intenção atual', async ({
  page,
}) => {
  const state = await trainingFixture(page);
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  const first = state.receipt!;
  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toBeVisible();
  state.arrive();
  await page.getByRole('button', { name: 'Atualizar trabalho', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmar coleta', exact: true }).click();
  await page.getByLabel('Código de confirmação', { exact: true }).fill(first.confirmationCode);
  await page.getByRole('button', { name: 'Confirmar entrega', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Última entrega', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Preparar novo treino', exact: true }).click();
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  const second = state.receipt!;
  expect(second.orderId).not.toBe(first.orderId);
  expect(second.confirmationCode).not.toBe(first.confirmationCode);
  await expect(page.locator('.training-code .confirmation-code')).toHaveText(
    second.confirmationCode,
  );
  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Entrega em andamento' })).toBeVisible();
  await expect.poll(() => state.details.some((url) => url.endsWith(second.orderId))).toBe(true);
  await page.getByRole('button', { name: 'Encerrar treino', exact: true }).click();
  await expect(page.getByText('Estado: Cancelado.', { exact: false })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Última entrega', exact: true })).toHaveCount(0);
  await expect(page.locator('.training-code .confirmation-code')).toHaveText(
    second.confirmationCode,
  );
});

test('consulta inicial atrasada não reenvia um ponto anterior ao já confirmado na tela', async ({
  page,
}) => {
  const state = await trainingFixture(page);
  await page.getByRole('button', { name: 'Criar pedido de treino', exact: true }).click();
  await page.getByRole('button', { name: 'Aceitar oferta', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Dirigir até a loja', exact: true })).toBeEnabled();
  state.holdNextWork();
  state.holdRoute();
  await page.getByRole('button', { name: 'Dirigir até a loja', exact: true }).click();
  await expect.poll(() => state.workPending).toBe(true);
  const newer = { latitude: -23.57, longitude: -46.66 };
  state.moveConfirmed(newer);
  await page.getByRole('button', { name: 'Atualizar trabalho', exact: true }).click();
  await expect(page.getByRole('definition').filter({ hasText: '-23.57, -46.66' })).toBeVisible();
  state.releaseWork();
  await expect.poll(() => state.gps.length).toBe(1);
  expect(state.gps[0]).toEqual(newer);
  await page.getByRole('button', { name: 'Pausar direção', exact: true }).click();
  state.releaseRoute();
});
