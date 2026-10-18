import { setTimeout as delay } from 'node:timers/promises';
import { parseArgs } from 'node:util';

const help = `Verifica requisições HTTP durante um deploy ou rollback.

Uso:
  node scripts/check-http-continuity.mjs --url http://localhost:18080 --duration 60 --interval 50 --timeout 2000

Opções:
  --url       Origem HTTP ou HTTPS, sem credenciais, caminho ou parâmetros.
  --duration  Duração em segundos. Padrão: 60.
  --interval  Intervalo mínimo entre inícios de requisição, em ms. Padrão: 50.
  --timeout   Prazo de cada requisição completa, em ms. Padrão: 2000.
  --help      Mostra esta ajuda.

Cada rota tem seu próprio loop, com no máximo uma requisição em andamento.
As rotas / e /actuator/health/readiness devem responder HTTP 200.
A readiness também deve retornar JSON com status igual a UP.
O p95 inclui todas as requisições concluídas, com sucesso ou falha.
SIGINT interrompe a medição, cancela as requisições e imprime o relatório.
O código de saída é 1 se houver falha, interrupção ou nenhuma amostra.`;

function readOptions() {
  let values;

  try {
    ({ values } = parseArgs({
      options: {
        url: { type: 'string' },
        duration: { type: 'string', default: '60' },
        interval: { type: 'string', default: '50' },
        timeout: { type: 'string', default: '2000' },
        help: { type: 'boolean', default: false },
      },
      allowPositionals: false,
    }));
  } catch {
    throw new Error('Opções inválidas. Consulte --help.');
  }

  if (values.help) {
    return { help: true };
  }

  let url;
  try {
    url = new URL(values.url);
  } catch {
    throw new Error('Informe --url com uma origem HTTP ou HTTPS válida.');
  }

  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.pathname !== '/' ||
    url.search ||
    url.hash
  ) {
    throw new Error('A URL deve ser HTTP ou HTTPS, sem credenciais, caminho ou parâmetros.');
  }

  const durationSeconds = Number(values.duration);
  const intervalMs = Number(values.interval);
  const timeoutMs = Number(values.timeout);
  const maximumTimerMs = 2_147_483_647;

  if (
    !Number.isFinite(durationSeconds) ||
    durationSeconds <= 0 ||
    durationSeconds * 1_000 > maximumTimerMs
  ) {
    throw new Error('A duração deve ser positiva e de até 2147483,647 segundos.');
  }

  for (const [name, value] of [
    ['interval', intervalMs],
    ['timeout', timeoutMs],
  ]) {
    if (!Number.isInteger(value) || value <= 0 || value > maximumTimerMs) {
      throw new Error(
        `A opção --${name} deve ser um inteiro positivo de até ${maximumTimerMs} ms.`,
      );
    }
  }

  return { url: url.origin, durationSeconds, intervalMs, timeoutMs };
}

function round(value) {
  return Math.round(value * 100) / 100;
}

function summarize(samples) {
  const latencies = samples.map((sample) => sample.latencyMs).sort((left, right) => left - right);
  const successes = samples.filter((sample) => sample.success).length;

  return {
    requests: samples.length,
    successes,
    failures: samples.length - successes,
    p95LatencyMs: latencies.length
      ? round(latencies[Math.ceil(latencies.length * 0.95) - 1])
      : null,
  };
}

async function request(url, path, timeoutMs, signal) {
  const startedAt = performance.now();
  const timeoutSignal = AbortSignal.timeout(timeoutMs);
  const requestSignal = AbortSignal.any([signal, timeoutSignal]);
  let failure;

  try {
    const response = await fetch(new URL(path, url), {
      signal: requestSignal,
      redirect: 'manual',
      cache: 'no-store',
      headers: { Accept: path === '/' ? 'text/html' : 'application/json' },
    });

    if (response.status !== 200) {
      failure = { reason: 'http_status', statusCode: response.status };
      await response.body?.cancel();
    } else if (path === '/') {
      await response.arrayBuffer();
    } else {
      let payload;
      try {
        payload = await response.json();
      } catch (error) {
        if (requestSignal.aborted) {
          throw error;
        }
        failure = { reason: 'invalid_json' };
      }

      if (!failure && payload?.status !== 'UP') {
        failure = { reason: 'not_ready' };
      }
    }
  } catch (error) {
    failure = {
      reason: signal.aborted ? 'interrupted' : timeoutSignal.aborted ? 'timeout' : 'request_error',
    };
    if (failure.reason === 'request_error') {
      failure.errorName = error.name;
      failure.errorCode = error.cause?.code;
      failure.errorMessage = error.cause?.message ?? error.message;
    }
  }

  return {
    success: !failure,
    latencyMs: performance.now() - startedAt,
    ...(failure ? { failure } : {}),
  };
}

async function measureEndpoint(path, options, deadline, signal) {
  const samples = [];
  const failureExamples = [];

  while (!signal.aborted && performance.now() < deadline) {
    const startedAt = performance.now();
    const sample = await request(options.url, path, options.timeoutMs, signal);
    samples.push(sample);

    if (sample.failure && failureExamples.length < 5) {
      failureExamples.push({
        at: new Date().toISOString(),
        latencyMs: round(sample.latencyMs),
        ...sample.failure,
      });
    }

    const remainingMs = deadline - performance.now();
    const intervalMs = options.intervalMs - (performance.now() - startedAt);
    const waitMs = Math.min(remainingMs, intervalMs);

    if (waitMs > 0 && !signal.aborted) {
      try {
        await delay(Math.ceil(waitMs), undefined, { signal });
      } catch (error) {
        if (!signal.aborted) {
          throw error;
        }
      }
    }
  }

  return { path, samples, failureExamples };
}

async function main() {
  let options;
  try {
    options = readOptions();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
    return;
  }

  if (options.help) {
    console.log(help);
    return;
  }

  const controller = new AbortController();
  const interrupt = () => controller.abort();
  process.once('SIGINT', interrupt);
  const startedAt = new Date().toISOString();
  const startedAtMs = performance.now();
  const deadline = startedAtMs + options.durationSeconds * 1_000;
  let results;

  try {
    results = await Promise.all(
      ['/', '/actuator/health/readiness'].map((path) =>
        measureEndpoint(path, options, deadline, controller.signal),
      ),
    );
  } finally {
    process.removeListener('SIGINT', interrupt);
  }

  const totals = summarize(results.flatMap((result) => result.samples));
  const report = {
    url: options.url,
    startedAt,
    finishedAt: new Date().toISOString(),
    requestedDurationSeconds: options.durationSeconds,
    elapsedMs: round(performance.now() - startedAtMs),
    intervalMs: options.intervalMs,
    timeoutMs: options.timeoutMs,
    interrupted: controller.signal.aborted,
    totals,
    endpoints: results.map(({ path, samples, failureExamples }) => ({
      path,
      ...summarize(samples),
      failureExamples,
    })),
  };

  console.log(JSON.stringify(report, null, 2));
  process.exitCode = totals.failures || !totals.requests || report.interrupted ? 1 : 0;
}

await main();
