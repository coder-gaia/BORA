import { useEffect, useRef, useState } from 'react';
import type { GeoJSONSource, Map as LibreMap, MapGeoJSONFeature } from 'maplibre-gl';
import { Crosshair, Layers, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import type { DemoUserView } from '@/lib/api';
import type { CourierPoint, CouriersGeoJSON, MapFrame } from '@/lib/courier-motion';
import { OperationsMapFeed, type MapConnectionState } from '@/lib/operations-map-feed';
import { publicMapStyle } from '@/lib/public-map-style';

const empty: CouriersGeoJSON = { type: 'FeatureCollection', features: [] };
const labels = { AVAILABLE: 'Disponível', OFFERED: 'Com oferta', BUSY: 'Em entrega' };
const pageSize = 10;
function geojson(frame: MapFrame): CouriersGeoJSON {
  return {
    type: 'FeatureCollection',
    features: frame.points.map(([id, lon, lat, status]) => ({
      type: 'Feature',
      id,
      geometry: { type: 'Point', coordinates: [lon, lat] },
      properties: { status },
    })),
  };
}

export function OperationsMap({
  user,
  onUnauthorized,
}: {
  user: DemoUserView;
  onUnauthorized: () => void;
}) {
  const surface = useRef<HTMLDivElement>(null);
  const map = useRef<LibreMap | null>(null);
  const latest = useRef<MapFrame | null>(null);
  const unauthorized = useRef(onUnauthorized);
  useEffect(() => {
    unauthorized.current = onUnauthorized;
  }, [onUnauthorized]);
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [connection, setConnection] = useState<MapConnectionState>('connecting');
  const [count, setCount] = useState(0);
  const [notice, setNotice] = useState('');
  const [list, setList] = useState<{ points: CourierPoint[]; serverTime: string } | null>(null);
  const [page, setPage] = useState(0);
  const [listOpen, setListOpen] = useState(false);
  const expand = useRef<() => void>(() => {});
  useEffect(() => {
    let alive = true;
    let clean = () => {};
    let feed: OperationsMapFeed | undefined;
    let observer: ResizeObserver | undefined;
    const visible = () => {
      if (document.visibilityState === 'visible') feed?.start();
      else {
        feed?.stop();
        latest.current = null;
        if (alive) {
          setCount(0);
          setList(null);
        }
        (map.current?.getSource('operations-couriers') as GeoJSONSource | undefined)?.setData(
          empty,
        );
      }
    };
    let canvasKey: ((event: KeyboardEvent) => void) | undefined;
    const abort = new AbortController();
    const clear = () => {
      latest.current = null;
      const source = map.current?.getSource('operations-couriers') as GeoJSONSource | undefined;
      source?.setData(empty);
      if (alive) {
        setCount(0);
        setList(null);
      }
    };
    void (async () => {
      try {
        const [
          { Map, NavigationControl, AttributionControl, ScaleControl, setWorkerUrl },
          { default: worker },
          style,
        ] = await Promise.all([
          import('maplibre-gl'),
          import('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'),
          fetch('https://tiles.openfreemap.org/styles/positron', {
            signal: AbortSignal.any([abort.signal, AbortSignal.timeout(10_000)]),
          }).then((response) => {
            if (!response.ok) throw new Error('Map unavailable');
            return response.json();
          }),
          import('maplibre-gl/dist/maplibre-gl.css'),
        ]);
        if (!alive || !surface.current) return;
        setWorkerUrl(worker);
        const current = new Map({
          container: surface.current,
          style: publicMapStyle(style),
          center: [-46.645, -23.556],
          zoom: 12.2,
          maxBounds: [
            [-46.85, -23.75],
            [-46.35, -23.35],
          ],
          attributionControl: false,
          cooperativeGestures: true,
          pixelRatio: Math.min(devicePixelRatio, 2),
          maxTileCacheSize: 96,
          locale: {
            'Map.Title': 'Mapa da operação em São Paulo',
            'NavigationControl.ZoomIn': 'Aproximar',
            'NavigationControl.ZoomOut': 'Afastar',
            'AttributionControl.ToggleAttribution': 'Créditos do mapa',
            'CooperativeGesturesHandler.WindowsHelpText':
              'Use Ctrl e a roda do mouse para ajustar o zoom',
            'CooperativeGesturesHandler.MacHelpText':
              'Use Command e a roda do mouse para ajustar o zoom',
            'CooperativeGesturesHandler.MobileHelpText': 'Use dois dedos para mover o mapa',
          },
        });
        map.current = current;
        clean = () => {
          observer?.disconnect();
          document.removeEventListener('visibilitychange', visible);
          if (canvasKey) current.getCanvas().removeEventListener('keydown', canvasKey);
          feed?.stop();
          latest.current = null;
          expand.current = () => {};
          current.remove();
          map.current = null;
        };
        current.addControl(new NavigationControl({ showCompass: false }), 'bottom-right');
        current.addControl(new AttributionControl({ compact: true }), 'bottom-left');
        current.addControl(new ScaleControl({ unit: 'metric', maxWidth: 90 }), 'bottom-left');
        const viewport = () => {
          const bounds = current.getBounds();
          if (!bounds || !feed) return;
          feed.setViewport({
            west: bounds.getWest(),
            south: bounds.getSouth(),
            east: bounds.getEast(),
            north: bounds.getNorth(),
            scope: 'OPERATIONS',
          });
        };
        const expandCluster = async (feature: MapGeoJSONFeature) => {
          if (feature.geometry.type !== 'Point') return;
          const source = current.getSource('operations-couriers') as GeoJSONSource | undefined;
          if (!source) return;
          const zoom = await source.getClusterExpansionZoom(Number(feature.properties.cluster_id));
          if (alive && map.current === current) {
            current.easeTo({
              center: feature.geometry.coordinates as [number, number],
              zoom,
              duration: 0,
            });
            const size = Number(feature.properties.point_count);
            setNotice(`Mapa aproximado. ${size} entregadores neste grupo.`);
          }
        };
        expand.current = () => {
          if (!alive || !current.isStyleLoaded()) return;
          const center = current.project(current.getCenter());
          const clusters = current
            .queryRenderedFeatures({ layers: ['operations-clusters'] })
            .filter((feature) => feature.geometry.type === 'Point')
            .sort((a, b) => {
              const distance = (feature: MapGeoJSONFeature) => {
                if (feature.geometry.type !== 'Point') return Infinity;
                const point = current.project(feature.geometry.coordinates as [number, number]);
                return Math.hypot(point.x - center.x, point.y - center.y);
              };
              return distance(a) - distance(b);
            });
          if (clusters[0])
            void expandCluster(clusters[0]).catch(() => {
              if (alive) setNotice('O grupo mudou. Tente aproximar novamente.');
            });
          else setNotice('Nenhum grupo nessa área. Você pode localizar um entregador pela lista.');
        };
        const key = (event: KeyboardEvent) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            expand.current();
          }
        };
        current
          .getCanvas()
          .setAttribute(
            'aria-label',
            'Mapa operacional. Use as setas e zoom. Enter aproxima o grupo central.',
          );
        canvasKey = key;
        current.getCanvas().addEventListener('keydown', key);
        observer = new ResizeObserver(() => {
          if (alive) current.resize();
        });
        observer.observe(surface.current);
        current.on('load', () => {
          if (!alive) return;
          current.addSource('operations-couriers', {
            type: 'geojson',
            data: empty,
            cluster: true,
            clusterRadius: 40,
            clusterMaxZoom: 14,
          });
          current.addLayer({
            id: 'operations-clusters',
            type: 'circle',
            source: 'operations-couriers',
            filter: ['has', 'point_count'],
            paint: {
              'circle-color': '#ffc21a',
              'circle-radius': 23,
              'circle-stroke-color': '#18181b',
              'circle-stroke-width': 1.5,
            },
          });
          current.addLayer({
            id: 'operations-cluster-count',
            type: 'symbol',
            source: 'operations-couriers',
            filter: ['has', 'point_count'],
            layout: {
              'text-field': ['get', 'point_count_abbreviated'],
              'text-font': ['Noto Sans Regular'],
              'text-size': 13,
            },
            paint: { 'text-color': '#18181b' },
          });
          current.addLayer({
            id: 'operations-points',
            type: 'circle',
            source: 'operations-couriers',
            filter: ['!', ['has', 'point_count']],
            paint: {
              'circle-radius': 5.5,
              'circle-color': [
                'match',
                ['get', 'status'],
                'BUSY',
                '#3f3f46',
                'OFFERED',
                '#ffc21a',
                '#8b8c87',
              ],
              'circle-stroke-color': '#ffffff',
              'circle-stroke-width': 1.5,
            },
          });
          feed = new OperationsMapFeed(
            user,
            (frame) => {
              if (!alive) return;
              latest.current = frame;
              (current.getSource('operations-couriers') as GeoJSONSource).setData(geojson(frame));
              setCount(frame.points.length);
            },
            (value) => {
              if (alive) setConnection(value);
            },
            clear,
            () => {
              if (alive) unauthorized.current();
            },
          );
          viewport();
          document.addEventListener('visibilitychange', visible);
          if (document.visibilityState === 'visible') feed.start();
          current.on('moveend', viewport);
          current.on('click', 'operations-clusters', (event) => {
            if (event.features?.[0])
              void expandCluster(event.features[0]).catch(() => {
                if (alive) setNotice('O grupo mudou. Tente aproximar novamente.');
              });
          });
          setState('ready');
        });
        current.on('error', () => {
          if (alive)
            setNotice(
              'Parte do mapa está indisponível. A lista e os indicadores continuam acessíveis.',
            );
        });
      } catch {
        clean();
        clean = () => {};
        if (alive) setState('failed');
      }
    })();
    return () => {
      alive = false;
      abort.abort();
      clean();
    };
  }, [user]);
  function capture() {
    const frame = latest.current;
    setList(frame ? { points: [...frame.points], serverTime: frame.serverTime } : null);
    setPage(0);
  }
  return (
    <section className="operations-map-block" aria-labelledby="operations-map-title">
      <div className="operations-map-heading">
        <div>
          <h4 id="operations-map-title">Entregadores na cidade</h4>
          <p>{count} nesta área. Grupos reúnem entregadores próximos.</p>
        </div>
        <span className="operations-feed-state">
          {connection === 'connected'
            ? 'Ao vivo'
            : connection === 'forbidden'
              ? 'Sessão indisponível'
              : 'Conectando atualizações'}
        </span>
      </div>
      <div className="operations-map-surface" ref={surface} />
      {state === 'loading' && <p role="status">Carregando o mapa da operação...</p>}
      {state === 'failed' && (
        <p role="status">
          O mapa não carregou. Use os indicadores e abra os pedidos para continuar.
        </p>
      )}
      {notice && (
        <p role="status" className="demo-description">
          {notice}
        </p>
      )}
      <div className="operations-map-tools">
        <p className="operations-map-legend">
          <span>
            <i data-status="AVAILABLE" /> Disponível
          </span>
          <span>
            <i data-status="OFFERED" /> Com oferta
          </span>
          <span>
            <i data-status="BUSY" /> Em entrega
          </span>
        </p>
        <Button variant="outline" disabled={state !== 'ready'} onClick={() => expand.current()}>
          <Layers aria-hidden="true" /> Aproximar grupo central
        </Button>
      </div>
      <details
        className="operations-courier-list"
        onToggle={(event) => {
          setListOpen(event.currentTarget.open);
          if (event.currentTarget.open) capture();
        }}
      >
        <summary>Entregadores nesta área em lista</summary>
        {listOpen && (
          <>
            <div className="operations-list-heading">
              <p>Lista preservada enquanto você consulta.</p>
              <Button variant="outline" onClick={capture}>
                <RefreshCw aria-hidden="true" /> Atualizar lista
              </Button>
            </div>
            {list && (
              <p>Posições obtidas às {new Date(list.serverTime).toLocaleTimeString('pt-BR')}.</p>
            )}
            {!list?.points.length && <p>Nenhum entregador disponível nesta área.</p>}
            <ul>
              {list?.points
                .slice(page * pageSize, (page + 1) * pageSize)
                .map(([id, lon, lat, status]) => (
                  <li key={id}>
                    <span>
                      Entregador {id.slice(-6).toUpperCase()} <small>{labels[status]}</small>
                    </span>
                    <Button
                      variant="outline"
                      disabled={state !== 'ready'}
                      onClick={() =>
                        map.current?.easeTo({ center: [lon, lat], zoom: 15, duration: 0 })
                      }
                    >
                      <Crosshair aria-hidden="true" /> Localizar
                    </Button>
                  </li>
                ))}
            </ul>
            {(list?.points.length ?? 0) > pageSize && (
              <nav className="pagination" aria-label="Páginas de entregadores">
                <Button
                  variant="outline"
                  disabled={page === 0}
                  onClick={() => setPage((value) => value - 1)}
                >
                  Anterior
                </Button>
                <span>
                  Página {page + 1} de {Math.ceil((list?.points.length ?? 0) / pageSize)}
                </span>
                <Button
                  variant="outline"
                  disabled={(page + 1) * pageSize >= (list?.points.length ?? 0)}
                  onClick={() => setPage((value) => value + 1)}
                >
                  Próxima
                </Button>
              </nav>
            )}
          </>
        )}
      </details>
    </section>
  );
}
