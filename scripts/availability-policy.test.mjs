import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import { AVAILABILITY, availabilityAt, reconciliationAt } from './availability-policy.mjs';

const at = (instant) => new Date(instant);

test('a divulgação fica ligada em qualquer horário antes do domingo às 22h de São Paulo', () => {
  for (const instant of [
    '2026-10-21T03:00:00Z',
    '2026-10-23T01:00:00Z',
    '2026-10-24T15:00:00Z',
    '2026-10-25T03:00:00Z',
    '2026-10-26T00:59:59.999Z',
  ]) {
    const policy = availabilityAt(at(instant));
    assert.equal(policy.phase, 'promotion', instant);
    assert.equal(policy.desired, 'running', instant);
    assert.equal(reconciliationAt('PowerState/running', at(instant)).action, 'none');
    assert.equal(reconciliationAt('PowerState/deallocated', at(instant)).action, 'start');
  }
});

test('o fim da divulgação coincide exatamente com domingo 25/10 às 22h', () => {
  const policy = availabilityAt(at(AVAILABILITY.promotionEndsAt));
  assert.equal(policy.localTime, '2026-10-25T22:00:00');
  assert.equal(policy.timeZone, 'America/Sao_Paulo');
  assert.equal(policy.phase, 'scheduled');
  assert.equal(policy.desired, 'deallocated');
  assert.equal(
    reconciliationAt('PowerState/running', at(AVAILABILITY.promotionEndsAt)).action,
    'deallocate',
  );
});

test('segunda-feira só inicia o pré-aquecimento às 08h45', () => {
  assert.equal(availabilityAt(at('2026-10-26T11:44:59.999Z')).desired, 'deallocated');
  assert.equal(availabilityAt(at('2026-10-26T11:45:00Z')).desired, 'running');
  assert.equal(availabilityAt(at('2026-10-26T12:00:00Z')).desired, 'running');
});

test('cada dia útil permanece ligado até 21h59 e desaloca às 22h', () => {
  for (const day of [26, 27, 28, 29, 30]) {
    const opening = `2026-10-${day}T08:45:00-03:00`;
    const closing = `2026-10-${day}T22:00:00-03:00`;
    assert.equal(availabilityAt(at(opening)).desired, 'running', opening);
    assert.equal(availabilityAt(new Date(at(closing).getTime() - 1)).desired, 'running', closing);
    assert.equal(availabilityAt(at(closing)).desired, 'deallocated', closing);
  }
});

test('sábado e domingo ficam desalocados mesmo durante o horário comercial', () => {
  for (const instant of [
    '2026-10-31T08:45:00-03:00',
    '2026-10-31T12:00:00-03:00',
    '2026-10-31T21:59:59-03:00',
    '2026-11-01T08:45:00-03:00',
    '2026-11-01T12:00:00-03:00',
    '2026-11-01T21:59:59-03:00',
  ]) {
    assert.equal(availabilityAt(at(instant)).desired, 'deallocated', instant);
  }
});

test('a virada do dia UTC não antecipa a virada do dia em São Paulo', () => {
  const policy = availabilityAt(at('2026-10-31T00:30:00Z'));
  assert.equal(policy.localTime, '2026-10-30T21:30:00');
  assert.equal(policy.desired, 'running');
  assert.equal(availabilityAt(at('2026-10-31T01:00:00Z')).desired, 'deallocated');
});

test('usa o instante atual ao reconciliar e não a intenção de um cron atrasado', () => {
  assert.equal(reconciliationAt('PowerState/running', at('2026-10-26T12:00:00Z')).action, 'none');
  assert.equal(
    reconciliationAt('PowerState/deallocated', at('2026-10-27T01:00:00Z')).action,
    'none',
  );
  assert.equal(
    reconciliationAt('PowerState/deallocated', at('2026-10-27T11:47:00Z')).action,
    'start',
  );
});

test('as operações são idempotentes e uma VM parada é desalocada fora da janela', () => {
  const on = at('2026-10-26T15:00:00Z');
  const off = at('2026-10-27T02:00:00Z');
  assert.equal(reconciliationAt('PowerState/running', on).action, 'none');
  assert.equal(reconciliationAt('PowerState/stopped', on).action, 'start');
  assert.equal(reconciliationAt('PowerState/deallocated', on).action, 'start');
  assert.equal(reconciliationAt('PowerState/running', off).action, 'deallocate');
  assert.equal(reconciliationAt('PowerState/stopped', off).action, 'deallocate');
  assert.equal(reconciliationAt('PowerState/deallocated', off).action, 'none');
});

test('não disputa uma operação de energia que já está em andamento', () => {
  for (const state of ['starting', 'stopping', 'deallocating']) {
    for (const instant of ['2026-10-26T15:00:00Z', '2026-10-27T02:00:00Z']) {
      assert.equal(reconciliationAt(`PowerState/${state}`, at(instant)).action, 'wait');
    }
  }
});

test('datas inválidas e estados desconhecidos impedem qualquer operação', () => {
  for (const instant of [new Date('invalid'), '2026-10-26', null, 0]) {
    assert.throws(() => availabilityAt(instant), /data válida/u);
  }
  for (const state of ['', undefined, 'unknown', 'PowerState/unknown']) {
    assert.throws(() => reconciliationAt(state), /Estado de energia desconhecido/u);
  }
});

test('a CLI não permite substituir o relógio e rejeita estados inesperados', () => {
  const script = fileURLToPath(new URL('./availability-policy.mjs', import.meta.url));
  for (const args of [
    [],
    ['--now', '2026-10-26T01:00:00Z'],
    ['--power-state', 'PowerState/unknown'],
    ['--power-state', 'PowerState/running', '--now', '2026-10-26T01:00:00Z'],
  ]) {
    const result = spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
    assert.equal(result.status, 1, result.stderr);
    assert.equal(result.stdout, '');
  }
});
