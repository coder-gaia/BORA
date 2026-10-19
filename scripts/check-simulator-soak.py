"""Observa o simulador e as invariantes persistidas, sem alterar dados do negócio."""

import argparse
import ctypes
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
SQL = """
SELECT json_build_object(
  'couriers', (SELECT count(*) FROM couriers WHERE simulated),
  'online', (SELECT count(*) FROM couriers WHERE simulated AND status <> 'OFFLINE'),
  'fresh', (SELECT count(*) FROM couriers WHERE simulated AND status <> 'OFFLINE'
    AND location_updated_at >= clock_timestamp() - interval '30 seconds'
    AND location_updated_at <= clock_timestamp() + interval '5 seconds'),
  'created', (SELECT count(*) FROM orders o JOIN stores s ON s.id=o.store_id
    WHERE o.visitor_context_id IS NULL AND s.simulated),
  'delivered', (SELECT count(*) FROM orders o JOIN stores s ON s.id=o.store_id
    WHERE o.visitor_context_id IS NULL AND s.simulated AND o.status='DELIVERED'),
  'busyWithoutOrder', (SELECT count(*) FROM couriers c WHERE c.simulated AND c.status='BUSY'
    AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.courier_id=c.id
      AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP'))),
  'orderWithoutBusyCourier', (SELECT count(*) FROM orders o JOIN couriers c ON c.id=o.courier_id
    WHERE c.simulated AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') AND c.status<>'BUSY'),
  'offeredWithoutPending', (SELECT count(*) FROM couriers c WHERE c.simulated AND c.status='OFFERED'
    AND NOT EXISTS (SELECT 1 FROM offers f WHERE f.courier_id=c.id AND f.status='PENDING')),
  'pendingWithoutOffered', (SELECT count(*) FROM offers f JOIN couriers c ON c.id=f.courier_id
    WHERE c.simulated AND f.status='PENDING' AND c.status<>'OFFERED'),
  'pendingWithoutSearching', (SELECT count(*) FROM offers f JOIN orders o ON o.id=f.order_id
    JOIN couriers c ON c.id=f.courier_id WHERE c.simulated AND f.status='PENDING' AND o.status<>'SEARCHING'),
  'multipleOrders', (SELECT count(*) FROM (SELECT o.courier_id FROM orders o
    JOIN couriers c ON c.id=o.courier_id WHERE c.simulated
    AND o.status IN ('ASSIGNED','AT_STORE','PICKED_UP') GROUP BY o.courier_id HAVING count(*)>1) duplicates),
  'privateAssignedToSimulator', (SELECT count(*) FROM orders o JOIN couriers c ON c.id=o.courier_id
    WHERE c.simulated AND o.visitor_context_id IS NOT NULL),
  'publicWithConfirmation', (SELECT count(*) FROM orders o JOIN stores s ON s.id=o.store_id
    WHERE s.simulated AND o.visitor_context_id IS NULL AND o.confirmation_code_hash IS NOT NULL)
);
"""
INVARIANTS = (
    "busyWithoutOrder", "orderWithoutBusyCourier", "offeredWithoutPending",
    "pendingWithoutOffered", "pendingWithoutSearching", "multipleOrders",
    "privateAssignedToSimulator", "publicWithConfirmation",
)
FATAL_COUNTERS = (
    "fatalActorFailures", "invalidApiResponses", "unexpectedHttpErrors",
    "visitorAssignmentsUnsupported", "http500", "http502", "http503", "http504",
    "networkFailures", "clockRefreshFailures", "apiUnavailable",
)
API_FAILURE_LOGS = (
    "Unexpected error occurred in scheduled task",
    " ERROR ",
    "A gravação das posições falhou",
    "Não foi possível atualizar o progresso do pedido",
    "Delivery progress failed;",
    "As posições foram gravadas, mas o aviso falhou",
    "deadlock detected",
    "Connection is not available, request timed out",
)


def run(*args, include_stderr=False):
    result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT if include_stderr else subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace", timeout=30)
    if result.returncode:
        # A saída de comandos de configuração pode conter dados sensíveis; não a persistimos.
        raise RuntimeError(f"O comando {args[0]} falhou com código {result.returncode}.")
    return result.stdout.strip()


def source_hash():
    digest = hashlib.sha256()
    paths = []
    for directory in (ROOT / "apps/api", ROOT / "apps/simulator"):
        paths.extend(path for path in directory.rglob("*") if path.is_file()
                     and "target" not in path.parts and "node_modules" not in path.parts)
    for path in sorted(paths):
        digest.update(path.relative_to(ROOT).as_posix().encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return digest.hexdigest()


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def log_failures(output):
    # Somente quantidades são persistidas; os logs brutos podem conter dados do negócio.
    return {pattern: output.count(pattern) for pattern in API_FAILURE_LOGS}


def violations(database, simulator, expected):
    if not isinstance(database, dict) or not isinstance(simulator, dict):
        raise ValueError("A observação precisa conter objetos de diagnóstico.")
    for key in (*INVARIANTS, "couriers", "online", "fresh", "created", "delivered"):
        if type(database.get(key)) is not int or database[key] < 0:
            raise ValueError(f"Contagem ausente ou inválida: database.{key}.")
    counters = simulator.get("counters")
    if not isinstance(counters, dict) or any(type(value) is not int or value < 0 for value in counters.values()):
        raise ValueError("Os contadores do simulador estão ausentes ou inválidos.")
    problems = [f"{key}={database[key]}" for key in INVARIANTS if database[key] != 0]
    for key in ("couriers", "online", "fresh"):
        if database[key] != expected:
            problems.append(f"database.{key}={database[key]} (esperado {expected})")
    if simulator.get("status") != "UP" or simulator.get("couriersOnline") != expected:
        problems.append("O simulador não informa todos os entregadores online.")
    for key in FATAL_COUNTERS:
        if counters.get(key, 0):
            problems.append(f"simulator.{key}={counters[key]}")
    for key, value in counters.items():
        if key.startswith("http5") and value and key not in FATAL_COUNTERS:
            problems.append(f"simulator.{key}={value}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", default="bora-sim-soak")
    parser.add_argument("--context", default="desktop-linux")
    parser.add_argument("--duration", type=int, default=3600)
    parser.add_argument("--couriers", type=int, default=120)
    parser.add_argument("--cpuset", default="0,1")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.duration < 60 or not 1 <= args.couriers <= 120:
        parser.error("Use duração >=60 segundos e 1 a 120 entregadores.")
    docker = ["docker", "--context", args.context]
    endpoint = json.loads(run("docker", "context", "inspect", args.context))[0]["Endpoints"]["docker"]["Host"]
    if not endpoint.startswith(("npipe://", "unix://")):
        parser.error("Este ensaio exige um Docker local via socket ou named pipe.")
    services = {}
    for name in ("api1", "api2", "db", "simulator", "caddy", "web"):
        value = run(*docker, "ps", "--quiet", "--filter", f"label=com.docker.compose.project={args.project}",
                    "--filter", f"label=com.docker.compose.service={name}")
        if not value or "\n" in value:
            raise RuntimeError(f"Esperado exatamente um container em execução para {name}.")
        services[name] = value

    def metadata():
        result = {}
        template = ('{"image":"{{.Image}}","cpuset":"{{.HostConfig.CpusetCpus}}",'
                    '"memoryLimitBytes":{{.HostConfig.Memory}},"oom":{{.State.OOMKilled}},'
                    '"restarts":{{.RestartCount}},"status":"{{.State.Status}}",'
                    '"startedAt":"{{.State.StartedAt}}"}')
        for name, container in services.items():
            result[name] = json.loads(run(*docker, "inspect", "--format", template, container))
        return result

    def snapshot():
        database = json.loads(run(*docker, "exec", services["db"], "sh", "-c",
            'PGPASSWORD="$POSTGRES_PASSWORD" psql --no-psqlrc --quiet --tuples-only --no-align '
            '--username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set ON_ERROR_STOP=1 --command="$1"',
            "bora-soak", SQL))
        simulator = json.loads(run(*docker, "exec", services["simulator"], "curl", "--fail", "--silent",
                                   "http://127.0.0.1:8090/health"))
        return {"observedAt": utc(), "database": database, "simulator": simulator}

    before = metadata()
    if any(value["cpuset"] != args.cpuset for value in before.values()):
        raise RuntimeError("Todos os serviços devem compartilhar o cpuset declarado no relatório.")
    report = {"startedAt": utc(), "requestedSeconds": args.duration, "expectedCouriers": args.couriers,
              "sharedCpuSet": args.cpuset, "sourceSha256": source_hash(), "containersBefore": before,
              "docker": json.loads(run(*docker, "info", "--format",
                  '{"architecture":"{{.Architecture}}","operatingSystem":"{{.OperatingSystem}}",'
                  '"hostVisibleCpus":{{.NCPU}},"hostMemoryBytes":{{.MemTotal}}}')),
              "samples": [], "failures": [], "qualified": False}
    awake = False
    started = time.monotonic()
    previous = started
    try:
        if os.name == "nt":
            awake = bool(ctypes.windll.kernel32.SetThreadExecutionState(0x80000001))
            if not awake:
                raise RuntimeError("Não foi possível manter a execução ativa durante o ensaio.")
        while True:
            now = time.monotonic()
            if now - previous > 20:
                raise RuntimeError("Intervalo de observação >20s; possível suspensão ou pausa do Docker.")
            previous = now
            sample = snapshot()
            sample["elapsedSeconds"] = round(now - started, 3)
            report["samples"].append(sample)
            problems = violations(sample["database"], sample["simulator"], args.couriers)
            if problems:
                raise RuntimeError("; ".join(problems))
            if len(report["samples"]) % 12 == 1:
                sample["resources"] = [json.loads(line) for line in run(*docker, "stats", "--no-stream",
                    "--format", '{{json .}}', *services.values()).splitlines()]
                print(json.dumps({"elapsedSeconds": sample["elapsedSeconds"],
                    "online": sample["database"]["online"], "fresh": sample["database"]["fresh"],
                    "created": sample["database"]["created"], "delivered": sample["database"]["delivered"]}), flush=True)
            if now - started >= args.duration:
                break
            time.sleep(max(0, min(5, args.duration - (time.monotonic() - started))))
        after = metadata()
        report["containersAfter"] = after
        if source_hash() != report["sourceSha256"]:
            raise RuntimeError("As fontes da API ou do simulador mudaram durante o ensaio.")
        if after != before:
            raise RuntimeError("Um serviço mudou de imagem, configuração ou estado durante o ensaio.")
        if any(value["oom"] or value["restarts"] for value in after.values()):
            raise RuntimeError("Um serviço sofreu OOM ou reinício.")
        report["apiLogDiagnostics"] = {
            name: log_failures(run(*docker, "logs", "--since", report["startedAt"], services[name],
                                   include_stderr=True)) for name in ("api1", "api2")
        }
        if any(count for values in report["apiLogDiagnostics"].values() for count in values.values()):
            raise RuntimeError("Os logs das APIs registraram erros; consulte apiLogDiagnostics.")
        first, last = report["samples"][0], report["samples"][-1]
        report["ordersCreated"] = last["database"]["created"] - first["database"]["created"]
        report["ordersDelivered"] = last["database"]["delivered"] - first["database"]["delivered"]
        if report["ordersCreated"] <= 0 or report["ordersDelivered"] <= 0:
            raise RuntimeError("O intervalo precisa conter criação e conclusão reais de pedidos.")
        report["qualified"] = args.duration >= 3600 and args.couriers == 120
    except (RuntimeError, subprocess.TimeoutExpired, ValueError, KeyboardInterrupt) as error:
        report["failures"].append(str(error) or type(error).__name__)
    finally:
        report["finishedAt"] = utc()
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        samples = report["samples"]
        report["observations"] = len(samples)
        if samples:
            report["minimumOnline"] = min(sample["database"]["online"] for sample in samples)
            report["minimumFresh"] = min(sample["database"]["fresh"] for sample in samples)
            report["maximumInvariantViolations"] = {
                key: max(sample["database"][key] for sample in samples) for key in INVARIANTS
            }
        # Todas as observações são verificadas; a série salva contém um ponto por minuto e o último.
        report["samples"] = [sample for index, sample in enumerate(samples)
                             if index % 12 == 0 or index == len(samples) - 1]
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if awake:
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)
    print(json.dumps({"qualified": report["qualified"], "elapsedSeconds": report["elapsedSeconds"],
                      "failures": report["failures"], "report": str(args.report)}, ensure_ascii=False), flush=True)
    return 1 if report["failures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
