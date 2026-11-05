import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AVAILABILITY } from './availability-policy.mjs';

export const LOGIC_APPS_AVAILABILITY = Object.freeze({
  windowsTimeZone: 'E. South America Standard Time',
  recurrenceAnchor: '2020-01-01T00:00:00',
  retentionDays: 7,
  powerPollCount: 12,
  powerPollTimeout: 'PT3M',
  readinessPollCount: 8,
  readinessPollTimeout: 'PT2M',
  pollSeconds: 15,
  fallbackMinutes: [0, 10, 20],
});

const templatePath = fileURLToPath(
  new URL('../infra/availability/logic-apps.json', import.meta.url),
);
const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'];
const stableStates = ['PowerState/running', 'PowerState/stopped', 'PowerState/deallocated'];
const knownStates = [
  ...stableStates,
  'PowerState/starting',
  'PowerState/stopping',
  'PowerState/deallocating',
];
const expression = (value) => `@${value}`;
const after = (name, statuses = ['Succeeded']) => ({ [name]: statuses });
const hhmm = (minute) => Math.floor(minute / 60) * 100 + (minute % 60);

export function currentWindowExpression(mode) {
  if (!['start', 'stop'].includes(mode)) throw new TypeError('Operação desconhecida.');
  const local = "convertTimeZone(utcNow(),'UTC',parameters('timeZone'))";
  const clock = "int(convertTimeZone(utcNow(),'UTC',parameters('timeZone'),'HHmm'))";
  const running = `and(contains(createArray(1,2,3,4,5),dayOfWeek(${local})),greaterOrEquals(${clock},parameters('startHhmm')),less(${clock},parameters('stopHhmm')))`;
  return mode === 'start' ? running : `not(${running})`;
}

function armHttp(method, suffix) {
  return {
    type: 'Http',
    inputs: {
      method,
      uri: `@concat('https://management.azure.com',parameters('vmResourceId'),'${suffix}?api-version=',parameters('computeApiVersion'))`,
      authentication: { type: 'ManagedServiceIdentity', audience: 'https://management.azure.com/' },
      retryPolicy: { type: 'none' },
    },
    // ARM's 202 Location may point outside the VM RBAC scope. Poll instanceView explicitly.
    operationOptions: 'DisableAsyncPattern',
    runAfter: {},
  };
}

function powerReadActions(prefix, dependency) {
  const read = `${prefix}_instance_view`;
  const filter = `${prefix}_power_status`;
  const store = `${prefix}_remember_power_state`;
  return {
    [read]: { ...armHttp('GET', '/instanceView'), runAfter: dependency ? after(dependency) : {} },
    [filter]: {
      type: 'Query',
      inputs: {
        from: `@coalesce(body('${read}')?['statuses'],json('[]'))`,
        where: "@startsWith(coalesce(item()?['code'],''),'PowerState/')",
      },
      runAfter: after(read),
    },
    [store]: {
      type: 'SetVariable',
      inputs: {
        name: 'powerState',
        value: `@coalesce(body('${filter}')?[0]?['code'],'unknown')`,
      },
      runAfter: after(filter),
    },
  };
}

function delayUnlessDone(condition, dependency) {
  return {
    type: 'If',
    expression: expression(condition),
    actions: {
      [`${dependency}_wait`]: {
        type: 'Wait',
        inputs: { interval: { count: LOGIC_APPS_AVAILABILITY.pollSeconds, unit: 'Second' } },
        runAfter: {},
      },
    },
    else: { actions: {} },
    runAfter: after(dependency),
  };
}

function readinessActions(window) {
  return {
    Wait_for_public_readiness: {
      type: 'Until',
      expression: expression(`or(variables('ready'),not(${window}))`),
      limit: {
        count: LOGIC_APPS_AVAILABILITY.readinessPollCount,
        timeout: LOGIC_APPS_AVAILABILITY.readinessPollTimeout,
      },
      operationOptions: 'FailWhenLimitsReached',
      actions: {
        Probe_public_readiness: {
          type: 'Http',
          inputs: {
            method: 'GET',
            uri: "@parameters('readinessUrl')",
            retryPolicy: { type: 'none' },
          },
          operationOptions: 'DisableAsyncPattern',
          runAfter: {},
        },
        Record_probe_result: {
          type: 'If',
          expression: "@equals(outputs('Probe_public_readiness')?['statusCode'],200)",
          actions: {
            Record_json_health: {
              type: 'SetVariable',
              inputs: {
                name: 'ready',
                value: "@equals(body('Probe_public_readiness')?['status'],'UP')",
              },
              runAfter: {},
            },
          },
          else: {
            actions: {
              Record_unavailable: {
                type: 'SetVariable',
                inputs: { name: 'ready', value: false },
                runAfter: {},
              },
            },
          },
          runAfter: after('Probe_public_readiness', ['Succeeded', 'Failed', 'TimedOut']),
        },
        Delay_before_readiness_retry: delayUnlessDone(
          `and(not(variables('ready')),${window})`,
          'Record_probe_result',
        ),
      },
      runAfter: {},
    },
    Confirm_public_readiness: {
      type: 'If',
      expression: "@variables('ready')",
      actions: {
        Public_readiness_confirmed: {
          type: 'Compose',
          inputs: { status: 'UP', checkedAt: '@utcNow()', url: "@parameters('readinessUrl')" },
          runAfter: {},
        },
      },
      else: {
        actions: {
          Readiness_window_expired: {
            type: 'Compose',
            inputs: 'A janela terminou antes da confirmação de saúde; não foi confirmado UP.',
            runAfter: {},
          },
        },
      },
      runAfter: after('Wait_for_public_readiness'),
    },
  };
}

export function createLogicAppDefinition(mode) {
  const window = currentWindowExpression(mode);
  const target = mode === 'start' ? 'PowerState/running' : 'PowerState/deallocated';
  const operation = mode === 'start' ? 'start' : 'deallocate';
  const minute = mode === 'start' ? AVAILABILITY.startMinute : AVAILABILITY.stopMinute;
  const scheduleMinutes = LOGIC_APPS_AVAILABILITY.fallbackMinutes.map(
    (offset) => (minute % 60) + offset,
  );
  if (scheduleMinutes.some((value) => value > 59)) {
    throw new RangeError('Os horários de recuperação devem permanecer na mesma hora.');
  }
  const vars = [{ name: 'powerState', type: 'string', value: 'unknown' }];
  if (mode === 'start') vars.push({ name: 'ready', type: 'boolean', value: false });
  return {
    $schema:
      'https://schema.management.azure.com/providers/Microsoft.Logic/schemas/2016-06-01/workflowdefinition.json#',
    contentVersion: '1.0.0.0',
    parameters: {
      vmResourceId: { type: 'String' },
      readinessUrl: { type: 'String' },
      timeZone: { type: 'String', defaultValue: LOGIC_APPS_AVAILABILITY.windowsTimeZone },
      startHhmm: { type: 'Int', defaultValue: hhmm(AVAILABILITY.startMinute) },
      stopHhmm: { type: 'Int', defaultValue: hhmm(AVAILABILITY.stopMinute) },
      computeApiVersion: { type: 'String', defaultValue: '2025-11-01' },
    },
    triggers: {
      Native_schedule: {
        type: 'Recurrence',
        recurrence: {
          frequency: mode === 'start' ? 'Week' : 'Day',
          interval: 1,
          timeZone: LOGIC_APPS_AVAILABILITY.windowsTimeZone,
          startTime: LOGIC_APPS_AVAILABILITY.recurrenceAnchor,
          schedule: {
            ...(mode === 'start' ? { weekDays: weekdays } : {}),
            hours: [Math.floor(minute / 60)],
            minutes: scheduleMinutes,
          },
        },
        runtimeConfiguration: { concurrency: { runs: 1 } },
      },
    },
    actions: {
      Initialize_control_state: {
        type: 'InitializeVariable',
        inputs: { variables: vars },
        runAfter: {},
      },
      Check_current_time_window: {
        type: 'If',
        expression: expression(window),
        actions: {
          ...powerReadActions('Initial'),
          Reject_unknown_power_state: {
            type: 'If',
            expression: expression(
              `not(contains(createArray(${knownStates.map((state) => `'${state}'`).join(',')}),variables('powerState')))`,
            ),
            actions: {
              Unknown_power_state: {
                type: 'Terminate',
                inputs: {
                  runStatus: 'Failed',
                  runError: {
                    code: 'UnknownPowerState',
                    message:
                      'A Azure não informou um estado de energia conhecido; nenhuma alteração foi solicitada.',
                  },
                },
                runAfter: {},
              },
            },
            else: { actions: {} },
            runAfter: after('Initial_remember_power_state'),
          },
          Change_power_only_if_needed: {
            type: 'If',
            expression: expression(`not(equals(variables('powerState'),'${target}'))`),
            actions: {
              Check_time_immediately_before_POST: {
                type: 'If',
                expression: expression(
                  `and(${window},contains(createArray(${stableStates.map((state) => `'${state}'`).join(',')}),variables('powerState')))`,
                ),
                actions: { Request_VM_power_change: armHttp('POST', `/${operation}`) },
                else: { actions: {} },
                runAfter: {},
              },
              Wait_for_confirmed_power_state: {
                type: 'Until',
                expression: expression(
                  `or(equals(variables('powerState'),'${target}'),not(${window}))`,
                ),
                limit: {
                  count: LOGIC_APPS_AVAILABILITY.powerPollCount,
                  timeout: LOGIC_APPS_AVAILABILITY.powerPollTimeout,
                },
                operationOptions: 'FailWhenLimitsReached',
                actions: {
                  ...powerReadActions('Poll'),
                  Delay_before_power_retry: delayUnlessDone(
                    `and(not(equals(variables('powerState'),'${target}')),${window})`,
                    'Poll_remember_power_state',
                  ),
                },
                runAfter: after('Check_time_immediately_before_POST'),
              },
            },
            else: { actions: {} },
            runAfter: after('Reject_unknown_power_state'),
          },
          Confirm_target_before_followup: {
            type: 'If',
            expression: expression(`and(equals(variables('powerState'),'${target}'),${window})`),
            actions:
              mode === 'start'
                ? readinessActions(window)
                : {
                    Deallocation_confirmed: {
                      type: 'Compose',
                      inputs: { powerState: target, checkedAt: '@utcNow()' },
                      runAfter: {},
                    },
                  },
            else: {
              actions: {
                Current_window_has_changed: {
                  type: 'Compose',
                  inputs:
                    'A janela atual mudou durante a execução. Nenhuma nova alteração de energia será solicitada.',
                  runAfter: {},
                },
              },
            },
            runAfter: after('Change_power_only_if_needed'),
          },
        },
        else: {
          actions: {
            Ignore_outdated_schedule: {
              type: 'Compose',
              inputs: 'Execução fora da janela atual desta operação; nenhum recurso foi alterado.',
              runAfter: {},
            },
          },
        },
        runAfter: after('Initialize_control_state'),
      },
    },
    outputs: {},
  };
}

export function createLogicAppsTemplate() {
  return {
    $schema: 'https://schema.management.azure.com/schemas/2019-04-01/deploymentTemplate.json#',
    contentVersion: '1.0.0.0',
    parameters: {
      namePrefix: {
        type: 'string',
        defaultValue: 'bora-availability',
        minLength: 3,
        maxLength: 60,
      },
      location: { type: 'string', defaultValue: '[resourceGroup().location]' },
      vmResourceId: {
        type: 'string',
        minLength: 1,
        metadata: {
          description:
            'ID ARM completo da única VM que as identidades gerenciadas poderão controlar.',
        },
      },
      roleDefinitionId: {
        type: 'string',
        minLength: 1,
        metadata: {
          description:
            'ID ARM do papel existente, limitado a read, instanceView/read, start/action e deallocate/action da VM.',
        },
      },
      readinessUrl: {
        type: 'string',
        defaultValue: 'https://bora.alexandregaia.me/actuator/health/readiness',
      },
    },
    resources: [
      ...['start', 'stop'].map((mode) => ({
        type: 'Microsoft.Logic/workflows',
        apiVersion: '2019-05-01',
        name: `[concat(parameters('namePrefix'), '-${mode}')]`,
        location: "[parameters('location')]",
        identity: { type: 'SystemAssigned' },
        tags: { project: 'bora', purpose: 'availability' },
        properties: {
          state: 'Disabled',
          runtimeConfiguration: {
            lifetime: { unit: 'day', count: LOGIC_APPS_AVAILABILITY.retentionDays },
          },
          definition: createLogicAppDefinition(mode),
          parameters: {
            vmResourceId: { value: "[parameters('vmResourceId')]" },
            readinessUrl: { value: "[parameters('readinessUrl')]" },
          },
        },
      })),
      ...['start', 'stop'].map((mode) => ({
        type: 'Microsoft.Authorization/roleAssignments',
        apiVersion: '2022-04-01',
        name: `[guid(parameters('vmResourceId'), resourceId('Microsoft.Logic/workflows', concat(parameters('namePrefix'), '-${mode}')), parameters('roleDefinitionId'))]`,
        scope: "[parameters('vmResourceId')]",
        dependsOn: [
          `[resourceId('Microsoft.Logic/workflows', concat(parameters('namePrefix'), '-${mode}'))]`,
        ],
        properties: {
          roleDefinitionId: "[parameters('roleDefinitionId')]",
          principalId: `[reference(resourceId('Microsoft.Logic/workflows', concat(parameters('namePrefix'), '-${mode}')), '2019-05-01', 'Full').identity.principalId]`,
          principalType: 'ServicePrincipal',
        },
      })),
    ],
    outputs: Object.fromEntries(
      ['start', 'stop'].flatMap((mode) => [
        [
          `${mode}WorkflowId`,
          {
            type: 'string',
            value: `[resourceId('Microsoft.Logic/workflows', concat(parameters('namePrefix'), '-${mode}'))]`,
          },
        ],
        [
          `${mode}PrincipalId`,
          {
            type: 'string',
            value: `[reference(resourceId('Microsoft.Logic/workflows', concat(parameters('namePrefix'), '-${mode}')), '2019-05-01', 'Full').identity.principalId]`,
          },
        ],
      ]),
    ),
  };
}

export function serializeLogicAppsTemplate() {
  return `${JSON.stringify(createLogicAppsTemplate(), null, 2)}\n`;
}

function main(args) {
  if (args.length !== 1 || !['--write', '--check'].includes(args[0])) {
    throw new TypeError('Uso: node scripts/logic-apps-availability.mjs --write|--check');
  }
  const expected = serializeLogicAppsTemplate();
  if (args[0] === '--write') {
    writeFileSync(templatePath, expected);
    console.log('Template atualizado em infra/availability/logic-apps.json.');
  } else {
    if (readFileSync(templatePath, 'utf8') !== expected) {
      throw new Error(
        'Template desatualizado. Execute o gerador com --write e revise a diferença.',
      );
    }
    console.log('Template coerente com a política de disponibilidade.');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
