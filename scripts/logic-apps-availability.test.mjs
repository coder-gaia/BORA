import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { AVAILABILITY } from './availability-policy.mjs';
import {
  LOGIC_APPS_AVAILABILITY,
  createLogicAppDefinition,
  createLogicAppsTemplate,
  serializeLogicAppsTemplate,
} from './logic-apps-availability.mjs';

// Evaluate the actual generated clock guards, rather than a second scheduling policy.
// This intentionally supports only the WDL functions used by those guard expressions.
function evaluateGuard(source, { now, parameters, variables = {} }) {
  const text = source.replace(/^@/u, '');
  let offset = 0;
  const localParts = (instant) =>
    Object.fromEntries(
      new Intl.DateTimeFormat('en-CA', {
        timeZone: 'America/Sao_Paulo',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23',
      })
        .formatToParts(new Date(instant))
        .map(({ type, value }) => [type, value]),
    );
  const functions = {
    utcNow: () => now,
    parameters: (name) => parameters[name],
    variables: (name) => variables[name],
    convertTimeZone: (instant, from, to, format) => {
      assert.equal(from, 'UTC');
      assert.equal(to, 'E. South America Standard Time');
      const p = localParts(instant);
      return format === 'HHmm'
        ? `${p.hour}${p.minute}`
        : `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}:${p.second}`;
    },
    dayOfWeek: (local) => new Date(`${local}Z`).getUTCDay(),
    int: (value) => Number(value),
    createArray: (...values) => values,
    contains: (array, value) => array.includes(value),
    greaterOrEquals: (a, b) => a >= b,
    less: (a, b) => a < b,
    and: (...values) => values.every(Boolean),
    not: (value) => !value,
  };
  const space = () => {
    while (/\s/u.test(text[offset] ?? '') && offset < text.length) offset++;
  };
  function value() {
    space();
    if (text[offset] === "'") {
      offset++;
      let result = '';
      while (offset < text.length) {
        if (text[offset] === "'") {
          if (text[offset + 1] === "'") {
            result += "'";
            offset += 2;
            continue;
          }
          offset++;
          return result;
        }
        result += text[offset++];
      }
      throw new Error('Literal WDL incompleto.');
    }
    const number = /^-?\d+/u.exec(text.slice(offset));
    if (number) {
      offset += number[0].length;
      return Number(number[0]);
    }
    const name = /^[A-Za-z][A-Za-z0-9_]*/u.exec(text.slice(offset))?.[0];
    assert.ok(name, `Expressão inesperada: ${text.slice(offset)}`);
    offset += name.length;
    space();
    assert.equal(text[offset++], '(');
    const args = [];
    space();
    while (text[offset] !== ')') {
      args.push(value());
      space();
      if (text[offset] !== ',') break;
      offset++;
    }
    assert.equal(text[offset++], ')');
    assert.ok(functions[name], `Função WDL não coberta: ${name}`);
    return functions[name](...args);
  }
  const result = value();
  space();
  assert.equal(offset, text.length);
  return result;
}

function* walkActions(actions, ancestors = []) {
  for (const [name, action] of Object.entries(actions)) {
    yield { name, action, ancestors };
    if (action.actions) yield* walkActions(action.actions, [...ancestors, { name, action }]);
    if (action.else?.actions)
      yield* walkActions(action.else.actions, [...ancestors, { name, action }]);
  }
}

function parametersFor(definition) {
  return Object.fromEntries(
    Object.entries(definition.parameters).map(([name, p]) => [name, p.defaultValue]),
  );
}

test('as recorrências nativas usam a política compartilhada e três tentativas explícitas', () => {
  assert.equal(AVAILABILITY.startMinute, 9 * 60 + 20);
  assert.equal(AVAILABILITY.stopMinute, 22 * 60 + 30);
  assert.equal(AVAILABILITY.publicStartMinute, 10 * 60);
  assert.equal(AVAILABILITY.publicStopMinute, 22 * 60);
  for (const mode of ['start', 'stop']) {
    const d = createLogicAppDefinition(mode);
    const trigger = d.triggers.Native_schedule;
    assert.equal(trigger.type, 'Recurrence');
    assert.equal(trigger.recurrence.timeZone, 'E. South America Standard Time');
    assert.equal(trigger.recurrence.startTime, '2020-01-01T00:00:00');
    assert.equal(trigger.runtimeConfiguration.concurrency.runs, 1);
    assert.equal(trigger.recurrence.frequency, mode === 'start' ? 'Week' : 'Day');
    assert.deepEqual(trigger.recurrence.schedule.hours, [mode === 'start' ? 9 : 22]);
    assert.deepEqual(
      trigger.recurrence.schedule.minutes,
      mode === 'start' ? [20, 30, 40] : [30, 40, 50],
    );
    assert.deepEqual(
      trigger.recurrence.schedule.weekDays,
      mode === 'start' ? ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'] : undefined,
    );
  }
});

test('os guards WDL reais respeitam fronteiras, UTC e fins de semana', () => {
  const cases = [
    ['2026-11-06T09:19:59-03:00', false],
    ['2026-11-06T09:20:00-03:00', true],
    ['2026-11-06T09:30:00-03:00', true],
    ['2026-11-06T09:40:00-03:00', true],
    ['2026-11-06T22:00:00-03:00', true],
    ['2026-11-06T22:29:59-03:00', true],
    ['2026-11-06T22:30:00-03:00', false],
    ['2026-11-07T00:30:00Z', true], // Sexta 21h30 em São Paulo.
    ['2026-11-07T01:30:00Z', false],
    ['2026-11-07T10:00:00-03:00', false],
    ['2026-11-08T15:00:00-03:00', false],
    ['2026-11-09T09:19:59-03:00', false],
    ['2026-11-09T09:20:00-03:00', true],
    ['2026-11-09T10:00:00-03:00', true],
  ];
  for (const mode of ['start', 'stop']) {
    const d = createLogicAppDefinition(mode);
    for (const [now, on] of cases) {
      const result = evaluateGuard(d.actions.Check_current_time_window.expression, {
        now,
        parameters: parametersFor(d),
      });
      assert.equal(result, mode === 'start' ? on : !on, `${mode}: ${now}`);
    }
  }
});

test('um POST atrasado revalida o relógio e nunca altera uma transição em andamento', () => {
  for (const mode of ['start', 'stop']) {
    const d = createLogicAppDefinition(mode);
    const post = [...walkActions(d.actions)].find(
      ({ action }) => action.type === 'Http' && action.inputs.method === 'POST',
    );
    assert.ok(post);
    const parent = post.ancestors.at(-1);
    assert.equal(parent.name, 'Check_time_immediately_before_POST');
    assert.ok(parent.action.expression.includes('utcNow()'));
    assert.ok(!parent.action.expression.includes('triggerOutputs'));
    const allowed = mode === 'start' ? '2026-11-09T09:20:00-03:00' : '2026-11-09T22:30:00-03:00';
    const expired = mode === 'start' ? '2026-11-09T22:30:00-03:00' : '2026-11-10T10:00:00-03:00';
    for (const state of [
      'PowerState/starting',
      'PowerState/stopping',
      'PowerState/deallocating',
      'unknown',
    ]) {
      assert.equal(
        evaluateGuard(parent.action.expression, {
          now: allowed,
          parameters: parametersFor(d),
          variables: { powerState: state },
        }),
        false,
      );
    }
    for (const state of ['PowerState/running', 'PowerState/stopped', 'PowerState/deallocated']) {
      assert.equal(
        evaluateGuard(parent.action.expression, {
          now: allowed,
          parameters: parametersFor(d),
          variables: { powerState: state },
        }),
        true,
      );
      assert.equal(
        evaluateGuard(parent.action.expression, {
          now: expired,
          parameters: parametersFor(d),
          variables: { powerState: state },
        }),
        false,
      );
    }
    assert.equal(post.action.operationOptions, 'DisableAsyncPattern');
    assert.deepEqual(post.action.inputs.retryPolicy, { type: 'none' });
  }
});

test('o template começa desabilitado e atribui o papel existente somente à VM', () => {
  const t = createLogicAppsTemplate();
  const workflows = t.resources.filter((r) => r.type === 'Microsoft.Logic/workflows');
  const roles = t.resources.filter((r) => r.type === 'Microsoft.Authorization/roleAssignments');
  assert.equal(t.resources.length, 4);
  assert.equal(workflows.length, 2);
  assert.equal(roles.length, 2);
  for (const workflow of workflows) {
    assert.equal(workflow.properties.state, 'Disabled');
    assert.deepEqual(workflow.identity, { type: 'SystemAssigned' });
    assert.deepEqual(workflow.properties.runtimeConfiguration.lifetime, { unit: 'day', count: 7 });
  }
  for (const role of roles) {
    assert.equal(role.scope, "[parameters('vmResourceId')]");
    assert.equal(role.properties.roleDefinitionId, "[parameters('roleDefinitionId')]");
    assert.equal(role.properties.principalType, 'ServicePrincipal');
    assert.equal(role.dependsOn.length, 1);
  }
  const raw = JSON.stringify(t);
  assert.ok(
    !/Microsoft\.Web\/connections|Microsoft\.Storage|Microsoft\.OperationalInsights|roleDefinitions/iu.test(
      raw,
    ),
  );
  assert.ok(!/\/subscriptions\/[a-f\d]{8}-/iu.test(raw));
});

test('os nomes de ações são únicos em todo o workflow, incluindo escopos e branches', () => {
  for (const mode of ['start', 'stop']) {
    const names = [...walkActions(createLogicAppDefinition(mode).actions)].map(({ name }) => name);
    assert.equal(names.length, new Set(names).size, `${mode}: nomes de ação duplicados`);
  }
});

test('ARM recebe somente GET e POST no alvo parametrizado, sem polling de operação regional', () => {
  for (const mode of ['start', 'stop']) {
    const d = createLogicAppDefinition(mode);
    for (const { action } of walkActions(d.actions)) {
      if (action.type !== 'Http' || action.inputs.uri === "@parameters('readinessUrl')") continue;
      assert.ok(['GET', 'POST'].includes(action.inputs.method));
      assert.ok(action.inputs.uri.includes("parameters('vmResourceId')"));
      assert.ok(!/operationResults|locations\/|runCommand|delete/iu.test(action.inputs.uri));
      assert.deepEqual(action.inputs.authentication, {
        type: 'ManagedServiceIdentity',
        audience: 'https://management.azure.com/',
      });
      assert.equal(action.operationOptions, 'DisableAsyncPattern');
      assert.deepEqual(action.inputs.retryPolicy, { type: 'none' });
    }
  }
});

test('status de energia é filtrado e um estado desconhecido falha antes do POST', () => {
  const d = createLogicAppDefinition('start');
  const actions = d.actions.Check_current_time_window.actions;
  assert.equal(actions.Initial_power_status.type, 'Query');
  assert.ok(actions.Initial_power_status.inputs.where.includes("'PowerState/'"));
  assert.ok(actions.Initial_remember_power_state.inputs.value.includes("?[0]?['code']"));
  const guard = actions.Reject_unknown_power_state;
  assert.equal(
    evaluateGuard(guard.expression, {
      now: '2026-11-09T13:00:00Z',
      parameters: parametersFor(d),
      variables: { powerState: 'unknown' },
    }),
    true,
  );
  assert.equal(guard.actions.Unknown_power_state.inputs.runStatus, 'Failed');
  assert.deepEqual(actions.Change_power_only_if_needed.runAfter, {
    Reject_unknown_power_state: ['Succeeded'],
  });
});

test('os polls são limitados e um aceite ARM não produz confirmação de saúde', () => {
  const d = createLogicAppDefinition('start');
  const all = [...walkActions(d.actions)];
  const power = all.find(({ name }) => name === 'Wait_for_confirmed_power_state').action;
  const ready = all.find(({ name }) => name === 'Wait_for_public_readiness').action;
  assert.deepEqual(power.limit, { count: 12, timeout: 'PT3M' });
  assert.deepEqual(ready.limit, { count: 8, timeout: 'PT2M' });
  assert.equal(power.operationOptions, 'FailWhenLimitsReached');
  assert.equal(ready.operationOptions, 'FailWhenLimitsReached');
  assert.equal(ready.actions.Probe_public_readiness.inputs.method, 'GET');
  const record = ready.actions.Record_probe_result;
  assert.equal(record.expression, "@equals(outputs('Probe_public_readiness')?['statusCode'],200)");
  assert.equal(
    record.actions.Record_json_health.inputs.value,
    "@equals(body('Probe_public_readiness')?['status'],'UP')",
  );
  assert.equal(record.else.actions.Record_unavailable.inputs.value, false);
  assert.deepEqual(record.runAfter.Probe_public_readiness, ['Succeeded', 'Failed', 'TimedOut']);
  assert.equal(LOGIC_APPS_AVAILABILITY.pollSeconds, 15);
});

test('o JSON versionado corresponde exatamente ao gerador e à política atual', () => {
  const actual = readFileSync(
    new URL('../infra/availability/logic-apps.json', import.meta.url),
    'utf8',
  );
  assert.equal(actual, serializeLogicAppsTemplate());
  assert.deepEqual(JSON.parse(actual), createLogicAppsTemplate());
});
