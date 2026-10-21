import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const AVAILABILITY = Object.freeze({
  timeZone: 'America/Sao_Paulo',
  promotionEndsAt: '2026-10-26T01:00:00.000Z',
  startMinute: 8 * 60 + 45,
  stopMinute: 22 * 60,
});

const formatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: AVAILABILITY.timeZone,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/** A política usa o instante da execução, inclusive quando o agendador atrasa. */
export function availabilityAt(now = new Date()) {
  if (!(now instanceof Date) || !Number.isFinite(now.getTime())) {
    throw new TypeError('O instante deve ser uma data válida.');
  }

  const parts = Object.fromEntries(
    formatter.formatToParts(now).map(({ type, value }) => [type, value]),
  );
  const localTime = `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}`;
  const minute = Number(parts.hour) * 60 + Number(parts.minute);
  const promotion = now.getTime() < Date.parse(AVAILABILITY.promotionEndsAt);
  const weekday = !['Sat', 'Sun'].includes(parts.weekday);
  const inWindow =
    weekday && minute >= AVAILABILITY.startMinute && minute < AVAILABILITY.stopMinute;

  return {
    evaluatedAt: now.toISOString(),
    timeZone: AVAILABILITY.timeZone,
    localTime,
    phase: promotion ? 'promotion' : 'scheduled',
    desired: promotion || inWindow ? 'running' : 'deallocated',
    reason: promotion
      ? 'Divulgação: manter ligado até 25/10/2026 às 22h em São Paulo.'
      : inWindow
        ? 'Dia útil: pré-aquecimento às 08h45 e disponibilidade das 09h às 22h.'
        : 'Fora do horário: desalocar para suspender a cobrança de computação.',
  };
}

/** Uma VM somente parada ainda cobra computação e precisa ser desalocada. */
export function reconciliationAt(powerState, now = new Date()) {
  const policy = availabilityAt(now);
  const state = powerState?.replace(/^PowerState\//u, '');
  const stable = ['running', 'stopped', 'deallocated'];
  const transitioning = ['starting', 'stopping', 'deallocating'];
  if (![...stable, ...transitioning].includes(state)) {
    throw new TypeError(`Estado de energia desconhecido: ${String(powerState)}.`);
  }

  let action = 'none';
  if (transitioning.includes(state)) {
    action = 'wait';
  } else if (policy.desired === 'running' && state !== 'running') {
    action = 'start';
  } else if (policy.desired === 'deallocated' && state !== 'deallocated') {
    action = 'deallocate';
  }
  return { ...policy, current: state, action };
}

function main(args) {
  if (args.length !== 2 || args[0] !== '--power-state') {
    throw new TypeError(
      'Uso: node scripts/availability-policy.mjs --power-state PowerState/running',
    );
  }
  // Não se aceita uma data pela CLI: o workflow sempre avalia o relógio atual.
  const plan = reconciliationAt(args[1]);
  console.log(JSON.stringify(plan));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
