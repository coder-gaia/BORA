#!/usr/bin/env python3
"""Produz os dados viários versionados usando a ferramenta fixada no Dockerfile."""

import argparse
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET


SOURCE_FILE = "sudeste-261001.osm.pbf"
SOURCE_URL = f"https://download.geofabrik.de/south-america/brazil/{SOURCE_FILE}"
SOURCE_SIZE = 859984241
SOURCE_MD5 = "6730c6e9739a176bb6da3b911fb4de0e"
SOURCE_SHA256 = "1463147c21162a1460cc8bb91f4bf557368fec4ab24ecfd382bdb92ebbda7f69"
BASE_IMAGE = "debian:trixie-20261005-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f"
DEBIAN_SNAPSHOT = "20261018T000000Z"
BBOX = [-46.77, -23.66, -46.48, -23.43]
FIXTURE_BBOX = [-46.661, -23.570, -46.647, -23.557]
FILTERS = ["w/highway", "w/route=ferry,shuttle_train", "r/type=restriction"]
FORMAT = "pbf,pbf_compression=zlib,add_metadata=false"
ROOT = Path(__file__).resolve().parents[2]
OSM_ROOT = ROOT / "infra/osm"
WORK = OSM_ROOT / "work"
FIXTURE_ROOT = ROOT / "apps/api/src/test/resources/routing"
METRICS = {}
COMMANDS = {}


def digest(path, algorithm="sha256"):
    result = hashlib.new(algorithm)
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def publish_file(source, destination):
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    shutil.copyfile(source, temporary)
    temporary.replace(destination)


def download_source():
    path = WORK / SOURCE_FILE
    if path.exists():
        return path
    temporary = path.with_suffix(path.suffix + ".part")
    offset = temporary.stat().st_size if temporary.exists() else 0
    headers = {"User-Agent": "Bora OSM preparation/1.0"}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(SOURCE_URL, headers=headers)
    print("Baixando a fonte mensal da Geofabrik para o cache local...", flush=True)
    with urllib.request.urlopen(request, timeout=60) as response:
        if offset and response.status != 206:
            offset = 0
        with temporary.open("ab" if offset else "wb") as output:
            written = checkpoint = offset
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                written += len(chunk)
                if written - checkpoint >= 64 * 1024 * 1024:
                    print(f"Fonte: {written}/{SOURCE_SIZE} bytes.", flush=True)
                    checkpoint = written
            output.flush()
            os.fsync(output.fileno())
    if temporary.stat().st_size != SOURCE_SIZE:
        raise ValueError("Tamanho da fonte diferente do esperado; o arquivo parcial foi preservado.")
    if digest(temporary, "md5") != SOURCE_MD5 or digest(temporary) != SOURCE_SHA256:
        raise ValueError("Checksum da fonte diferente do fixado; nenhum produto foi publicado.")
    temporary.replace(path)
    return path


def run_osmium(name, arguments, capture=False):
    metrics_path = WORK / f"{name}.time.json"
    command = [
        "/usr/bin/time", "--format={\"elapsedSeconds\": %e, \"peakRssKiB\": %M}",
        "--output", str(metrics_path), "osmium", *arguments,
    ]
    print(f"Executando: {name}.", flush=True)
    result = subprocess.run(command, check=True, text=True, stdout=subprocess.PIPE if capture else None)
    METRICS[name] = json.loads(metrics_path.read_text(encoding="utf-8"))
    COMMANDS[name] = ["osmium", *[str(value).replace(str(ROOT) + "/", "") for value in arguments]]
    return result.stdout


def extract(source, destination, bbox, name):
    run_osmium(name, [
        "extract", str(source), "--bbox=" + ",".join(map(str, bbox)),
        "--strategy=smart", "--option=types=restriction", "--no-progress",
        "--generator=bora-osm/1", "--output-format=" + FORMAT,
        "--output=" + str(destination), "--overwrite",
    ])


def road_nodes(path):
    """Lê coordenadas de nós reais e vias candidatas sem reescrever o PBF."""
    nodes, barriers, ways = {}, set(), []
    candidate_highways = {"primary", "secondary", "tertiary", "unclassified", "residential", "living_street"}
    restricted = {"no", "private", "permit", "restricted", "military", "emergency", "agricultural", "forestry"}
    command = ["osmium", "cat", str(path), "--output-format=xml,add_metadata=false"]
    process = subprocess.Popen(command, stdout=subprocess.PIPE)
    try:
        parser = ET.iterparse(process.stdout, events=("start", "end"))
        _, root = next(parser)
        for event, element in parser:
            if event != "end" or element.tag not in {"node", "way", "relation"}:
                continue
            tags = {tag.attrib["k"]: tag.attrib["v"] for tag in element.findall("tag")}
            if element.tag == "node":
                node_id = int(element.attrib["id"])
                nodes[node_id] = {
                    "latitude": float(element.attrib["lat"]),
                    "longitude": float(element.attrib["lon"]),
                }
                if "barrier" in tags or any(
                    tags.get(key) in restricted
                    for key in ("access", "vehicle", "motor_vehicle", "motorcycle")
                ):
                    barriers.add(node_id)
            elif (
                element.tag == "way"
                and tags.get("highway") in candidate_highways
                and tags.get("area") != "yes"
            ):
                access = next(
                    (tags[key] for key in ("motorcycle", "motor_vehicle", "vehicle", "access") if key in tags),
                    "yes",
                )
                direction = next(
                    (tags[key] for key in ("oneway:motorcycle", "oneway:motor_vehicle", "oneway:vehicle", "oneway") if key in tags),
                    "yes" if tags.get("junction") == "roundabout" else "no",
                )
                references = [int(node.attrib["ref"]) for node in element.findall("nd")]
                if (
                    access not in restricted
                    and direction in {"yes", "true", "1", "-1", "no", "false", "0"}
                    and len(references) > 1
                ):
                    if direction == "-1":
                        references.reverse()
                    ways.append({"id": int(element.attrib["id"]), "nodes": references})
            root.clear()
        if process.wait() != 0:
            raise ValueError("Falha ao ler os nós usados no aquecimento.")
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
            process.wait()
    ways = [
        way for way in ways
        if not barriers.intersection(way["nodes"])
        and all(node in nodes for node in way["nodes"])
    ]
    return nodes, ways


def distance(first, second):
    lat1, lat2 = map(math.radians, (first["latitude"], second["latitude"]))
    dlat = lat2 - lat1
    dlon = math.radians(second["longitude"] - first["longitude"])
    haversine = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(haversine)))


def choose_pairs(nodes, ways, bbox, count=2):
    def inside(node):
        return bbox[0] < node["longitude"] < bbox[2] and bbox[1] < node["latitude"] < bbox[3]

    candidates = []
    for way in ways:
        references = [node_id for node_id in way["nodes"] if inside(nodes[node_id])]
        if len(references) < 2:
            continue
        origin, destination = references[0], references[-1]
        separation = distance(nodes[origin], nodes[destination])
        if 80 <= separation <= 2000:
            candidates.append((separation, way["id"], origin, destination))
    candidates.sort(reverse=True)
    pairs, evidence = [], []
    for _, way_id, origin, destination in candidates:
        if any(distance(nodes[origin], pair["origin"]) < 150 for pair in pairs):
            continue
        pairs.append({"origin": nodes[origin], "destination": nodes[destination]})
        evidence.append({"wayId": way_id, "originNodeId": origin, "destinationNodeId": destination})
        if len(pairs) == count:
            return pairs, evidence
    raise ValueError("Não há pares suficientes sobre vias reais no recorte; reveja a área da fixture.")


def choose_production_pairs(nodes, ways):
    # Os alvos só orientam a busca; as coordenadas publicadas vêm dos nós OSM.
    targets = [
        ((-23.56141, -46.65588), (-23.54369, -46.64266)),
        ((-23.56604, -46.69173), (-23.53772, -46.67464)),
        ((-23.58618, -46.63412), (-23.56141, -46.65588)),
        ((-23.55052, -46.63429), (-23.55783, -46.60042)),
        ((-23.54369, -46.64266), (-23.52700, -46.61850)),
    ]
    eligible = {}
    for way in ways:
        for node_id in way["nodes"]:
            eligible.setdefault(node_id, way["id"])

    def closest(target):
        point = {"latitude": target[0], "longitude": target[1]}
        node_id = min(eligible, key=lambda value: (distance(point, nodes[value]), value))
        if distance(point, nodes[node_id]) > 250:
            raise ValueError("Não há nó viário adequado perto de um alvo de produção.")
        return node_id, eligible[node_id]

    pairs, evidence = [], []
    for first, second in targets:
        origin, origin_way = closest(first)
        destination, destination_way = closest(second)
        separation = distance(nodes[origin], nodes[destination])
        if origin_way == destination_way or not 1000 <= separation <= 5000:
            raise ValueError("Um par de produção não cobre vias distintas separadas por 1 a 5 km.")
        pairs.append({"origin": nodes[origin], "destination": nodes[destination]})
        evidence.append({
            "originNodeId": origin,
            "destinationNodeId": destination,
            "originWayId": origin_way,
            "destinationWayId": destination_way,
        })
    return pairs, evidence


def build_manifest(path, bbox, source_info, pairs, evidence, fixture=False):
    result = {
        "datasetFile": path.name,
        "sha256": digest(path),
        "sizeBytes": path.stat().st_size,
        "bbox": bbox,
        "source": source_info,
        "provenance": {
            "provider": "Geofabrik GmbH",
            "providerUrl": "https://download.geofabrik.de/south-america/brazil/sudeste.html",
            "tool": "osmium-tool 1.18.0-1",
            "baseImage": BASE_IMAGE,
            "debianSnapshot": DEBIAN_SNAPSHOT,
            "extractStrategy": "smart",
            "completeRelationTypes": ["restriction"],
            "filters": FILTERS,
            "objectMetadata": False,
            "pbfCompression": "zlib",
            "warmupNodes": evidence,
        },
        "license": {
            "identifier": "ODbL-1.0",
            "url": "https://opendatacommons.org/licenses/odbl/1-0/",
            "attribution": "© OpenStreetMap contributors",
            "attributionUrl": "https://www.openstreetmap.org/copyright",
        },
        "warmupPairs": pairs,
    }
    if fixture:
        result["minNetworkSize"] = 0
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Fonte mensal já baixada; o SHA256 fixado continua obrigatório.")
    parser.add_argument("--source-sha256", default=SOURCE_SHA256, help="SHA256 esperado da fonte mensal fixada.")
    parser.add_argument("--check", action="store_true", help="Reconstrói e compara com os produtos existentes, sem alterá-los.")
    arguments = parser.parse_args()
    if arguments.source_sha256 != SOURCE_SHA256 or not re.fullmatch(r"[a-f0-9]{64}", SOURCE_SHA256):
        parser.error("Informe o SHA256 da fonte mensal fixada no script.")
    version = subprocess.check_output(["dpkg-query", "--show", "--showformat=${Version}", "osmium-tool"], text=True)
    if version != "1.18.0-1":
        raise ValueError("Execute com a imagem construída pelo Dockerfile desta pasta.")
    (OSM_ROOT / "data").mkdir(exist_ok=True)
    FIXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    source = arguments.source.resolve() if arguments.source else download_source()
    if (
        source.stat().st_size != SOURCE_SIZE
        or digest(source) != arguments.source_sha256
        or digest(source, "md5") != SOURCE_MD5
    ):
        raise ValueError("A fonte não corresponde ao tamanho e aos checksums fixados.")
    source_details = json.loads(run_osmium("source-info", ["fileinfo", "--json", str(source)], capture=True))
    source_info = {
        "datasetFile": SOURCE_FILE,
        "url": SOURCE_URL,
        "sha256": SOURCE_SHA256,
        "md5": SOURCE_MD5,
        "sizeBytes": SOURCE_SIZE,
        "dataTimestamp": source_details.get("header", {}).get("option", {}).get("osmosis_replication_timestamp"),
    }
    complete = WORK / "centro-complete.osm.pbf"
    dataset = WORK / "centro-sp-261001.osm.pbf"
    fixture = WORK / "sp-fixture.osm.pbf"
    extract(source, complete, BBOX, "extract-production")
    run_osmium("filter-production", [
        "tags-filter", str(complete), *FILTERS, "--no-progress", "--generator=bora-osm/1",
        "--output-format=" + FORMAT, "--output=" + str(dataset), "--overwrite",
    ])
    extract(dataset, fixture, FIXTURE_BBOX, "extract-fixture")
    for name, path, limit in (("production", dataset, 30 * 1024 * 1024), ("fixture", fixture, 1024 * 1024)):
        if path.stat().st_size > limit:
            raise ValueError(f"O produto {name} excedeu o limite de {limit} bytes.")
        run_osmium(f"check-{name}", ["check-refs", "--check-relations", "--no-progress", str(path)])
        details = run_osmium(f"info-{name}", ["fileinfo", "--extended", "--json", str(path)], capture=True)
        (WORK / f"{name}.fileinfo.json").write_text(details, encoding="utf-8")
    nodes, ways = road_nodes(fixture)
    pairs, evidence = choose_pairs(nodes, ways, FIXTURE_BBOX)
    fixture_manifest = build_manifest(fixture, FIXTURE_BBOX, source_info, pairs, evidence, fixture=True)
    production_nodes, production_ways = road_nodes(dataset)
    production_pairs, production_evidence = choose_production_pairs(production_nodes, production_ways)
    production_manifest = build_manifest(dataset, BBOX, source_info, production_pairs, production_evidence)
    fixture_manifest["provenance"]["parentDataset"] = {
        "datasetFile": dataset.name,
        "sha256": production_manifest["sha256"],
    }
    products = [
        (dataset, OSM_ROOT / "data" / dataset.name, OSM_ROOT / "data/manifest.json", production_manifest),
        (fixture, FIXTURE_ROOT / fixture.name, FIXTURE_ROOT / "sp-fixture-manifest.json", fixture_manifest),
    ]
    # Só publica produtos depois das verificações; cache e intermediários ficam fora do Git.
    for generated, destination, manifest_path, manifest in products:
        if arguments.check:
            if digest(destination) != manifest["sha256"] or json.loads(manifest_path.read_text(encoding="utf-8")) != manifest:
                raise ValueError(f"Reconstrução diferente do produto existente: {destination.name}.")
        else:
            publish_file(generated, destination)
            write_json(manifest_path, manifest)
    write_json(WORK / "processing-report.json", {"metrics": METRICS, "commands": COMMANDS})
    print(json.dumps({
        "production": {key: production_manifest[key] for key in ("datasetFile", "sha256", "sizeBytes")},
        "fixture": {key: fixture_manifest[key] for key in ("datasetFile", "sha256", "sizeBytes")},
        "peakRssKiB": max(value["peakRssKiB"] for value in METRICS.values()),
        "reproduced": arguments.check,
        "warmupPairs": pairs,
    }, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    try:
        WORK.mkdir(parents=True, exist_ok=True)
        with (WORK / ".prepare.lock").open("a") as execution_lock:
            try:
                fcntl.flock(execution_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise ValueError(
                    "Outra preparação OSM está em execução; aguarde sua conclusão e tente novamente."
                ) from error
            main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Falha ao preparar dados OSM: {error}", file=sys.stderr)
        raise SystemExit(1) from error
