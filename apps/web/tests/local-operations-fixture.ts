import { execFile, spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { request as requests, type APIRequestContext } from '@playwright/test';
import type { components } from '../src/generated/api';

type Point = components['schemas']['GeoPoint'];
type Order = components['schemas']['OrderView'];
type Courier = components['schemas']['CourierView'];
type Offer = components['schemas']['CourierOffer'];
type ActorView = { id: string; index: number; position: Point };
type Scenario = { stores: ActorView[]; couriers: ActorView[] };
type SelectedCourier = { id: string; status: 'AVAILABLE'; latitude: number; longitude: number };
type LeaseState = { pid: number; preexistingOrders: number; couriers: SelectedCourier[] };
type Container = {
  Id: string;
  Config: { Labels: Record<string, string>; Env: string[] };
  State: { Running: boolean; Paused: boolean; Health?: { Status: string } };
  NetworkSettings: { Ports: Record<string, { HostIp: string; HostPort: string }[] | null> };
  Mounts: { Destination: string; Type: string; Name?: string }[];
};
const execute = promisify(execFile);
const project = process.env.BORA_OPS_FIXTURE_PROJECT || 'bora-sim-soak';
const context =
  process.env.BORA_OPS_FIXTURE_DOCKER_CONTEXT ||
  (process.platform === 'win32' ? 'desktop-linux' : 'default');
const apiOrigin = 'http://127.0.0.1:18081';
const run = () => crypto.randomUUID();
const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
function check(value: unknown, message: string): asserts value {
  if (!value) throw new Error(message);
}
function loopback(origin: string) {
  const url = new URL(origin);
  check(
    url.protocol === 'http:' &&
      ['localhost', '127.0.0.1'].includes(url.hostname) &&
      !url.username &&
      !url.password,
    'This fixture requires an HTTP loopback target.',
  );
}
async function eventually<T>(
  read: () => Promise<T>,
  accept: (value: T) => boolean,
  timeout: number,
  message: string,
): Promise<T> {
  const deadline = performance.now() + timeout;
  do {
    const value = await read();
    if (accept(value)) return value;
    await delay(250);
  } while (performance.now() < deadline);
  throw new Error(message);
}
function meters(a: Point, b: Point) {
  const rad = Math.PI / 180;
  const x =
    Math.sin(((b.latitude - a.latitude) * rad) / 2) ** 2 +
    Math.cos(a.latitude * rad) *
      Math.cos(b.latitude * rad) *
      Math.sin(((b.longitude - a.longitude) * rad) / 2) ** 2;
  return 6_371_000 * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
}

class Actor {
  constructor(readonly http: APIRequestContext) {}
  async get<T>(path: string): Promise<T> {
    const response = await this.http.get(path, { timeout: 10_000 }).catch(() => {
      throw new Error(`Fixture GET ${path} could not finish. Request headers are omitted.`);
    });
    check(response.status() === 200, `Fixture GET ${path} returned ${response.status()}.`);
    return (await response.json()) as T;
  }
  async post<T>(
    path: string,
    body: unknown,
    expected: number,
    headers: Record<string, string> = {},
  ): Promise<T> {
    const csrf = await this.get<{ headerName: string; token: string }>('/api/v1/auth/csrf');
    const response = await this.http
      .post(path, {
        data: body,
        timeout: 10_000,
        headers: { [csrf.headerName]: csrf.token, ...headers },
      })
      .catch(() => {
        throw new Error(`Fixture POST ${path} could not finish. Request headers are omitted.`);
      });
    check(
      response.status() === expected,
      `Fixture POST ${path} returned ${response.status()}, expected ${expected}.`,
    );
    return expected === 204 ? (undefined as T) : ((await response.json()) as T);
  }
}

/** Explicit local harness. No execution occurs merely by importing this module. */
export class LocalOperationsFixture {
  private db!: Container;
  private simulator!: Container;
  private api!: Container;
  private database = '';
  private username = '';
  private key = '';
  private stoppedSimulator = false;
  private actors: Actor[] = [];
  private lease?: ChildProcessWithoutNullStreams;
  private leaseState?: LeaseState;
  private heartbeat?: ReturnType<typeof setInterval>;
  private watchdog?: ReturnType<typeof setTimeout>;
  private monitor?: ReturnType<typeof setInterval>;
  private monitorBusy = false;
  private asynchronousFailure?: Error;
  private closing = false;
  private store?: Actor;
  private oldActor?: Actor;
  private newActor?: Actor;
  private position?: Point;
  private creation?: { key: string; dropoff: Point };
  private selected?: LeaseState['couriers'];
  readonly runId = run();
  orderId = '';
  publicCode = '';
  oldCourierId = '';
  newCourierId = '';
  readonly evidence: Record<string, unknown> = { intentionId: this.runId };

  private async docker(...args: string[]) {
    const result = await execute('docker', ['--context', context, ...args], {
      timeout: 45_000,
      maxBuffer: 1_048_576,
      env: { ...process.env, DOCKER_CONTEXT: context, DOCKER_HOST: undefined },
    }).catch(() => {
      throw new Error(`Local Docker ${args[0]} failed. Raw inspection output is omitted.`);
    });
    return result.stdout.trim();
  }
  private async inspect(id: string) {
    const values = JSON.parse(await this.docker('inspect', id)) as Container[];
    check(values.length === 1, 'Fixture container inspection was not unique.');
    return values[0]!;
  }
  private validate(container: Container, service: string) {
    check(
      container.Config.Labels['com.docker.compose.project'] === project &&
        container.Config.Labels['com.docker.compose.service'] === service,
      `Fixture target is not local ${project}/${service}.`,
    );
  }
  private async service(name: string) {
    const ids = (
      await this.docker(
        'ps',
        '--all',
        '--quiet',
        '--filter',
        `label=com.docker.compose.project=${project}`,
        '--filter',
        `label=com.docker.compose.service=${name}`,
      )
    )
      .split(/\s+/)
      .filter(Boolean);
    check(ids.length === 1, `Expected exactly one ${project}/${name} container.`);
    const value = await this.inspect(ids[0]);
    this.validate(value, name);
    return value;
  }
  private connectionArgs() {
    return [
      '--context',
      context,
      'exec',
      '--interactive',
      this.db.Id,
      'psql',
      '--no-psqlrc',
      '--quiet',
      '--tuples-only',
      '--no-align',
      '--set',
      'ON_ERROR_STOP=1',
      '--username',
      this.username,
      '--dbname',
      this.database,
    ];
  }
  private sql(source: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const child = spawn('docker', this.connectionArgs(), {
        stdio: 'pipe',
        env: { ...process.env, DOCKER_CONTEXT: context, DOCKER_HOST: undefined },
      });
      let stdout = '',
        stderr = '';
      let failure: Error | undefined;
      let settled = false;
      let termination: ReturnType<typeof setTimeout> | undefined;
      const finish = (code: number | null) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        clearTimeout(termination);
        if (failure) reject(failure);
        else if (code === 0) resolve(stdout.trim());
        else reject(new Error(`Local fixture SQL exited ${code}. ${stderr.slice(0, 500)}`));
      };
      const stop = (message: string) => {
        if (settled) return;
        failure ??= new Error(message);
        child.stdin.destroy();
        child.kill();
        // Normally close follows kill/error. This bounds a broken Docker client too.
        termination ??= setTimeout(() => finish(null), 1_000);
      };
      const timeout = setTimeout(() => stop('Local fixture SQL timed out.'), 15_000);
      child.stdout.on('data', (chunk) => {
        stdout += String(chunk);
      });
      child.stderr.on('data', (chunk) => {
        stderr += String(chunk);
      });
      child.once('error', () => stop('Local fixture SQL process could not start.'));
      child.stdin.on('error', () => stop('Local fixture SQL input closed unexpectedly.'));
      child.once('close', finish);
      child.stdin.end(source);
    });
  }
  private async bootstrap(role: 'STORE' | 'COURIER', index: number) {
    const actor = new Actor(await requests.newContext({ baseURL: apiOrigin }));
    this.actors.push(actor);
    await actor.post('/api/v1/auth/simulator', { role, index }, 200, {
      'X-Bora-Simulator-Key': this.key,
    });
    return actor;
  }
  private async openLease() {
    const source = await readFile(
      fileURLToPath(new URL('./ops-fixture-lease.sql', import.meta.url)),
      'utf8',
    );
    const application = `bora-m12-ops-fixture:${this.runId}`;
    const child = spawn(
      'docker',
      [...this.connectionArgs(), '--set', `fixture_name=${application}`],
      {
        stdio: 'pipe',
        env: { ...process.env, DOCKER_CONTEXT: context, DOCKER_HOST: undefined },
      },
    );
    this.lease = child;
    child.stdin.on('error', () => {
      if (!this.closing)
        this.asynchronousFailure = new Error('The fixture lease input closed unexpectedly.');
    });
    this.leaseState = await new Promise<LeaseState>((resolve, reject) => {
      let pending = '',
        error = '';
      const timeout = setTimeout(
        () => reject(new Error('Fixture lease could not acquire old order locks.')),
        20_000,
      );
      child.stderr.on('data', (chunk) => {
        error += String(chunk);
      });
      child.stdout.on('data', (chunk) => {
        pending += String(chunk);
        const lines = pending.split(/\r?\n/);
        pending = lines.pop()!;
        for (const line of lines)
          if (line.startsWith('BORA_OPS_FIXTURE_READY ')) {
            clearTimeout(timeout);
            try {
              resolve(JSON.parse(line.slice('BORA_OPS_FIXTURE_READY '.length)) as LeaseState);
            } catch {
              reject(new Error('The local fixture lease returned an invalid receipt.'));
            }
          }
      });
      child.once('error', (failure) => {
        clearTimeout(timeout);
        reject(failure);
      });
      child.once('close', (code) => {
        clearTimeout(timeout);
        if (!this.closing)
          this.asynchronousFailure ??= new Error(
            `Fixture lease exited ${code}. ${error.slice(0, 500)}`,
          );
        reject(this.asynchronousFailure);
      });
      child.stdin.write(source);
    });
    check(
      Number.isSafeInteger(this.leaseState.pid) && this.leaseState.pid > 0,
      'Unexpected fixture backend PID.',
    );
    check(
      this.leaseState.couriers.length === 2,
      'The fixture needs two AVAILABLE simulated couriers without work.',
    );
    this.selected = this.leaseState.couriers;
    this.evidence.lease = {
      pid: this.leaseState.pid,
      preexistingOrders: this.leaseState.preexistingOrders,
      couriers: this.selected.map((value) => value.id),
    };
    this.heartbeat = setInterval(
      () => child.stdin.write("SELECT 'BORA_OPS_FIXTURE_HEARTBEAT';\n"),
      15_000,
    );
    this.watchdog = setTimeout(() => {
      this.asynchronousFailure = new Error('The 150s fixture lease budget expired.');
      child.stdin.end('ROLLBACK;\n');
    }, 150_000);
    this.monitor = setInterval(() => {
      if (this.monitorBusy || this.closing) return;
      this.monitorBusy = true;
      void this.assertNoBlockedApplication()
        .catch((failure: unknown) => {
          if (this.closing) return;
          this.asynchronousFailure =
            failure instanceof Error ? failure : new Error('Lease blocking check failed.');
          child.stdin.end('ROLLBACK;\n');
        })
        .finally(() => {
          this.monitorBusy = false;
        });
    }, 2_000);
    await this.assertNoBlockedApplication();
  }
  private async assertNoBlockedApplication() {
    if (!this.leaseState) return;
    const blocked = JSON.parse(
      await this.sql(`SELECT COALESCE(json_agg(json_build_object('pid',pid,
      'application',application_name,'wait',wait_event)),'[]'::json) FROM pg_stat_activity
      WHERE pid<>${this.leaseState.pid} AND ${this.leaseState.pid}=ANY(pg_blocking_pids(pid));`),
    ) as unknown[];
    check(
      blocked.length === 0,
      'The order lease blocked an application connection; release instead of consuming Hikari slots.',
    );
  }
  assertHealthy() {
    if (this.asynchronousFailure) throw this.asynchronousFailure;
    check(
      this.lease && this.lease.exitCode === null && this.leaseState,
      'The fixture isolation lease is no longer active.',
    );
  }

  static async start(baseURL: string) {
    check(
      process.env.BORA_TEST_OPS_FIXTURE === 'true',
      'Enable this fixture explicitly with BORA_TEST_OPS_FIXTURE=true.',
    );
    loopback(baseURL);
    const fixture = new LocalOperationsFixture();
    try {
      await fixture.prepare();
      return fixture;
    } catch (error) {
      try {
        await fixture.close();
      } catch (cleanup) {
        throw new AggregateError([error, cleanup], 'Fixture setup and cleanup both failed.', {
          cause: cleanup,
        });
      }
      throw error;
    }
  }
  private async prepare() {
    check(
      ['bora-sim-soak', 'bora-m13-e2e'].includes(project),
      'Fixture project must be an explicitly supported local E2E project.',
    );
    const endpoint = JSON.parse(
      await this.docker(
        'context',
        'inspect',
        context,
        '--format',
        '{{json .Endpoints.docker.Host}}',
      ),
    ) as string;
    check(
      process.platform === 'win32'
        ? endpoint.toLowerCase() === 'npipe:////./pipe/dockerdesktoplinuxengine'
        : endpoint === 'unix:///var/run/docker.sock',
      'Fixture Docker endpoint must be the local Desktop pipe or the local Linux socket.',
    );
    const os = JSON.parse(
      await this.docker('info', '--format', '{{json .OperatingSystem}}'),
    ) as string;
    const type = JSON.parse(await this.docker('info', '--format', '{{json .OSType}}')) as string;
    check(
      type === 'linux' && (process.platform !== 'win32' || /Docker Desktop/i.test(os)),
      'Fixture Docker daemon must be local Linux (Docker Desktop on Windows).',
    );
    [this.db, this.simulator, this.api] = await Promise.all([
      this.service('db'),
      this.service('simulator'),
      this.service('api1'),
    ]);
    check(
      this.db.State.Running &&
        this.api.State.Running &&
        this.simulator.State.Running &&
        !this.simulator.State.Paused &&
        this.simulator.State.Health?.Status === 'healthy',
      'Start from the healthy local 120-courier baseline.',
    );
    const port = this.db.NetworkSettings.Ports['5432/tcp'];
    check(
      port?.length === 1 && port[0].HostIp === '127.0.0.1' && port[0].HostPort === '15433',
      'Fixture database must be bound only on 127.0.0.1:15433.',
    );
    const apiPort = this.api.NetworkSettings.Ports['8080/tcp'];
    check(
      apiPort?.length === 1 && apiPort[0].HostIp === '127.0.0.1' && apiPort[0].HostPort === '18081',
      'The inspected local API must match 127.0.0.1:18081.',
    );
    const expectedVolume = process.env.BORA_OPS_FIXTURE_DB_VOLUME;
    check(
      expectedVolume && /^bora-m(?:12|13)-[a-zA-Z0-9_.-]+$/.test(expectedVolume),
      'Name the reviewed M12/M13 fixture volume explicitly with BORA_OPS_FIXTURE_DB_VOLUME.',
    );
    const data = this.db.Mounts.filter((mount) => mount.Destination === '/var/lib/postgresql');
    check(
      data.length === 1 && data[0].Type === 'volume' && data[0].Name === expectedVolume,
      'The local database volume does not match the explicit fixture volume.',
    );
    const env = (container: Container, name: string) =>
      container.Config.Env.find((value) => value.startsWith(`${name}=`))?.slice(name.length + 1);
    this.username = env(this.db, 'POSTGRES_USER') || '';
    this.database = env(this.db, 'POSTGRES_DB') || '';
    check(
      /^[a-zA-Z0-9_]+$/.test(this.username) && /^[a-zA-Z0-9_]+$/.test(this.database),
      'Unexpected local PostgreSQL database or user.',
    );
    check(
      env(this.api, 'SPRING_DATASOURCE_URL') === `jdbc:postgresql://db:5432/${this.database}`,
      'The local API datasource does not target the inspected fixture database service.',
    );
    check(
      env(this.simulator, 'SIM_COURIER_COUNT') === '120',
      'This fixture requires the 120-courier local simulator configuration.',
    );
    this.key = env(this.api, 'BORA_SIMULATOR_KEY') || '';
    check(
      this.key.length >= 32 && !/\s/.test(this.key),
      'The local simulator key is not configured.',
    );
    const scenarioClient = await requests.newContext({ baseURL: apiOrigin });
    let scenario: Scenario;
    try {
      const response = await scenarioClient
        .get('/api/v1/simulator/scenario', { headers: { 'X-Bora-Simulator-Key': this.key } })
        .catch(() => {
          throw new Error('Local simulator catalog request failed. Internal headers are omitted.');
        });
      check(response.status() === 200, 'Could not read the local simulator catalog.');
      scenario = (await response.json()) as Scenario;
    } finally {
      await scenarioClient.dispose();
    }
    const before = await this.inspect(this.simulator.Id);
    this.validate(before, 'simulator');
    check(before.State.Running && !before.State.Paused, 'The local simulator changed before stop.');
    this.stoppedSimulator = true; // Also recover if the stop command times out after stopping it.
    await this.docker('stop', '--time', '30', this.simulator.Id);
    const stale = await eventually(
      async () =>
        JSON.parse(
          await this.sql(`SELECT json_build_object(
      'fresh',count(*) FILTER(WHERE location_updated_at>=clock_timestamp()-interval '30 seconds'),
      'latest',max(location_updated_at),'now',clock_timestamp()) FROM couriers;`),
        ) as { fresh: number; latest: string; now: string },
      (value) => value.fresh === 0,
      50_000,
      'GPS did not become stale after stopping the local simulator.',
    );
    this.evidence.staleness = stale;
    await this.openLease();
    const descriptors = this.selected!.map((selected) =>
      scenario.couriers.find((actor) => actor.id === selected.id),
    );
    check(
      descriptors.every(Boolean),
      'Selected couriers do not belong to the 120-courier simulator catalog.',
    );
    this.oldCourierId = descriptors[0]!.id;
    this.newCourierId = descriptors[1]!.id;
    this.store = await this.bootstrap('STORE', 0);
    this.oldActor = await this.bootstrap('COURIER', descriptors[0]!.index);
    this.newActor = await this.bootstrap('COURIER', descriptors[1]!.index);
    const store = await this.store.get<{ location: Point }>('/api/v1/stores/me');
    const origins = scenario.stores
      .map((value) => value.position)
      .filter(
        (value) => meters(value, store.location) >= 300 && meters(value, store.location) <= 900,
      );
    check(origins.length > 0, 'The fixture needs a reachable point 300..900m from pickup.');
    this.position = origins[0]!;
    await this.oldActor.post(
      '/api/v1/simulator/route',
      { origin: this.position, destination: store.location },
      200,
      { 'X-Bora-Simulator-Key': this.key },
    );
    const destination = scenario.stores.find(
      (value) =>
        meters(value.position, store.location) >= 500 &&
        meters(value.position, store.location) <= 2_000,
    )?.position;
    check(destination, 'No routeable fixture destination was available.');
    await this.oldActor.post('/api/v1/courier/availability', { online: false }, 200);
    await this.newActor.post('/api/v1/courier/availability', { online: false }, 200);
    await this.oldActor.post(
      '/api/v1/courier/availability',
      { online: true, position: this.position },
      200,
    );
    this.creation = { key: this.runId, dropoff: { ...destination } };
    const order = await this.store.post<Order>(
      '/api/v1/orders',
      { dropoff: this.creation.dropoff },
      201,
      { 'Idempotency-Key': this.creation.key },
    );
    this.orderId = order.id;
    this.publicCode = order.publicCode;
    const offer = await this.offer(this.oldActor);
    await this.oldActor.post(`/api/v1/offers/${offer.id}/accept`, undefined, 204);
    const assigned = await this.order();
    check(
      assigned.status === 'ASSIGNED' && assigned.courierId === this.oldCourierId,
      'The fixture did not reach the real first ASSIGNED stage.',
    );
    this.assertHealthy();
    this.evidence.databaseVolume = expectedVolume;
    this.evidence.initial = {
      orderId: this.orderId,
      courierId: assigned.courierId,
      version: assigned.version,
    };
  }
  private async offer(actor: Actor) {
    const inbox = await eventually(
      () => actor.get<{ offer: Offer | null }>('/api/v1/courier/offer'),
      (value) => value.offer !== null,
      20_000,
      'The real dispatch worker did not create the fixture offer.',
    );
    check(
      inbox.offer?.orderId === this.orderId,
      'An unrelated order reached a fixture actor; isolation failed.',
    );
    return inbox.offer;
  }
  async order() {
    check(this.store && this.orderId, 'Fixture order has not been created.');
    return this.store.get<Order>(`/api/v1/orders/${this.orderId}`);
  }
  async assertOldAvailable() {
    this.assertHealthy();
    check(this.oldActor, 'Missing fixture actor.');
    const courier = await this.oldActor.get<Courier>('/api/v1/courier/me');
    check(courier.status === 'AVAILABLE', 'The old courier was not released to AVAILABLE.');
    this.evidence.oldAvailable = { id: courier.id, status: courier.status };
    return courier;
  }
  async acceptWithSecondActor() {
    await this.assertOldAvailable();
    check(this.newActor && this.position, 'Missing second fixture actor.');
    const searching = await this.order();
    check(
      searching.status === 'SEARCHING' && searching.courierId === null,
      'Redistribution did not reset the same order to SEARCHING.',
    );
    await this.newActor.post(
      '/api/v1/courier/availability',
      { online: true, position: this.position },
      200,
    );
    const offer = await this.offer(this.newActor);
    await this.newActor.post(`/api/v1/offers/${offer.id}/accept`, undefined, 204);
    const assigned = await this.order();
    check(
      assigned.status === 'ASSIGNED' &&
        assigned.courierId === this.newCourierId &&
        assigned.courierId !== this.oldCourierId,
      'A different real courier was not assigned.',
    );
    await this.assertOldAvailable();
    this.evidence.reassigned = {
      orderId: assigned.id,
      courierId: assigned.courierId,
      version: assigned.version,
    };
    return assigned;
  }
  async close() {
    if (this.closing) return;
    this.closing = true;
    clearInterval(this.monitor);
    clearInterval(this.heartbeat);
    clearTimeout(this.watchdog);
    const failures: unknown[] = this.asynchronousFailure ? [this.asynchronousFailure] : [];
    if (this.store && this.creation && !this.orderId) {
      try {
        // A lost 201 may already have committed. Recover exactly the recorded intent;
        // never invent another key, destination or unrelated order to clean up.
        const recovered = await this.store.post<Order>(
          '/api/v1/orders',
          { dropoff: this.creation.dropoff },
          201,
          { 'Idempotency-Key': this.creation.key },
        );
        this.orderId = recovered.id;
        this.publicCode = recovered.publicCode;
        this.evidence.recoveredCreation = { orderId: recovered.id, version: recovered.version };
      } catch (error) {
        failures.push(error);
      }
    }
    if (this.store && this.orderId) {
      try {
        const order = await this.order();
        if (['SEARCHING', 'ASSIGNED'].includes(order.status)) {
          await this.store.post(
            `/api/v1/orders/${this.orderId}/cancel`,
            { expectedVersion: order.version },
            200,
          );
        } else
          check(
            order.status === 'CANCELED' || order.status === 'DELIVERED',
            'The stationary fixture unexpectedly advanced beyond store cancellation. Do not force its state.',
          );
      } catch (error) {
        failures.push(error);
      }
    }
    for (const [index, actor] of [this.oldActor, this.newActor].entries()) {
      const original = this.selected?.[index];
      if (!actor || !original) continue;
      try {
        const work = await actor.get<{ order: { id: string } | null }>('/api/v1/courier/work');
        check(
          !work.order,
          'Do not overwrite a courier that still owns work during fixture cleanup.',
        );
        await actor.post('/api/v1/courier/availability', { online: false }, 200);
        await actor.post(
          '/api/v1/courier/availability',
          {
            online: true,
            position: { latitude: original.latitude, longitude: original.longitude },
          },
          200,
        );
      } catch (error) {
        failures.push(error);
      }
    }
    if (this.lease) {
      try {
        const lease = this.lease;
        if (!lease.stdin.writableEnded) lease.stdin.end('ROLLBACK;\n');
        await Promise.race([
          new Promise<void>((resolve, reject) => {
            if (lease.exitCode !== null) {
              resolve();
              return;
            }
            lease.once('close', (code) =>
              code === 0 ? resolve() : reject(new Error(`Lease close exited ${code}.`)),
            );
          }),
          delay(10_000).then(() => {
            throw new Error('Lease rollback did not close within 10s.');
          }),
        ]);
      } catch (error) {
        failures.push(error);
        if (this.leaseState) {
          try {
            await this.sql(`SELECT pg_terminate_backend(pid) FROM pg_stat_activity
              WHERE pid=${this.leaseState.pid} AND application_name='bora-m12-ops-fixture:${this.runId}';`);
          } catch (termination) {
            failures.push(termination);
          }
        }
      }
    }
    if (this.stoppedSimulator) {
      try {
        const current = await this.inspect(this.simulator.Id);
        this.validate(current, 'simulator');
        check(current.Id === this.simulator.Id, 'Do not start a replacement simulator container.');
        await this.docker('start', this.simulator.Id);
        const restored = await eventually(
          () => this.inspect(this.simulator.Id),
          (value) => value.State.Running && value.State.Health?.Status === 'healthy',
          90_000,
          'The original local simulator did not return healthy.',
        );
        this.evidence.restoredSimulator = {
          id: restored.Id,
          health: restored.State.Health?.Status,
        };
      } catch (error) {
        failures.push(error);
      }
    }
    for (const actor of this.actors) {
      try {
        await actor.http.dispose();
      } catch (error) {
        failures.push(error);
      }
    }
    this.key = '';
    if (failures.length)
      throw new AggregateError(failures, 'Local operations fixture cleanup requires review.');
  }
}
