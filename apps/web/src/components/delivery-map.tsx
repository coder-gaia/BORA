import { useEffect, useRef, useState } from 'react';
import type { GeoJSONSource, Map as MapLibre, Marker as MapMarker } from 'maplibre-gl';
import { Crosshair, MapPin } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { publicMapStyle } from '@/lib/public-map-style';

export type DeliveryPoint = { latitude: number; longitude: number };
type RouteGeometry = { type: string; coordinates: number[][] };
type Props = {
  pickup: DeliveryPoint;
  dropoff: DeliveryPoint;
  courier?: DeliveryPoint | null;
  geometry?: RouteGeometry | null;
  onDestination?: (point: DeliveryPoint) => void;
};

const bounds: [[number, number], [number, number]] = [
  [-46.85, -23.75],
  [-46.35, -23.35],
];
const emptyRoute = { type: 'FeatureCollection' as const, features: [] };

export function DeliveryMap(props: Props) {
  const element = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibre | null>(null);
  const markers = useRef<{ pickup?: MapMarker; dropoff?: MapMarker; courier?: MapMarker }>({});
  const latest = useRef(props);
  useEffect(() => {
    latest.current = props;
  });
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [notice, setNotice] = useState('');
  const picking = Boolean(props.onDestination);

  function fit() {
    const current = map.current;
    if (!current) return;
    const { pickup, dropoff, courier } = latest.current;
    const points = [pickup, dropoff, ...(courier ? [courier] : [])];
    current.fitBounds(
      [
        [
          Math.min(...points.map((point) => point.longitude)),
          Math.min(...points.map((point) => point.latitude)),
        ],
        [
          Math.max(...points.map((point) => point.longitude)),
          Math.max(...points.map((point) => point.latitude)),
        ],
      ],
      { padding: 64, maxZoom: 15, duration: 0 },
    );
  }

  useEffect(() => {
    let alive = true;
    const abort = new AbortController();
    let clean = () => {};
    void (async () => {
      try {
        const [
          { Map, Marker, NavigationControl, AttributionControl, setWorkerUrl },
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
        if (!alive || !element.current) return;
        setWorkerUrl(worker);
        const current = new Map({
          container: element.current,
          style: publicMapStyle(style),
          center: [latest.current.dropoff.longitude, latest.current.dropoff.latitude],
          zoom: 13,
          maxBounds: bounds,
          attributionControl: false,
          cooperativeGestures: true,
          pixelRatio: Math.min(devicePixelRatio, 2),
          maxTileCacheSize: 96,
          locale: {
            'Map.Title': picking ? 'Escolher o destino em São Paulo' : 'Mapa desta entrega',
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
          current.remove();
          map.current = null;
          markers.current = {};
        };
        current.addControl(new NavigationControl({ showCompass: false }), 'bottom-right');
        current.addControl(new AttributionControl({ compact: true }), 'bottom-left');
        current
          .getCanvas()
          .setAttribute(
            'aria-label',
            picking ? 'Mapa para escolher o destino' : 'Mapa do trajeto da entrega',
          );
        const addMarker = (kind: 'pickup' | 'dropoff' | 'courier', point: DeliveryPoint) => {
          const marker = document.createElement('span');
          marker.className = `delivery-map-marker marker-${kind}`;
          marker.setAttribute(
            'aria-label',
            kind === 'pickup' ? 'Loja' : kind === 'dropoff' ? 'Destino' : 'Entregador',
          );
          marker.textContent = kind === 'pickup' ? 'L' : kind === 'dropoff' ? 'D' : 'E';
          return new Marker({ element: marker })
            .setLngLat([point.longitude, point.latitude])
            .addTo(current);
        };
        current.on('load', () => {
          if (!alive) return;
          const value = latest.current;
          markers.current.pickup = addMarker('pickup', value.pickup);
          markers.current.dropoff = addMarker('dropoff', value.dropoff);
          if (value.courier) markers.current.courier = addMarker('courier', value.courier);
          current.addSource('delivery-route', { type: 'geojson', data: emptyRoute });
          current.addLayer({
            id: 'delivery-route-line',
            type: 'line',
            source: 'delivery-route',
            paint: { 'line-color': '#24241f', 'line-width': 4, 'line-opacity': 0.8 },
            layout: { 'line-cap': 'round', 'line-join': 'round' },
          });
          fit();
          setState('ready');
        });
        current.on('click', (event) => {
          if (!alive || !latest.current.onDestination) return;
          latest.current.onDestination({
            latitude: Number(event.lngLat.lat.toFixed(6)),
            longitude: Number(event.lngLat.lng.toFixed(6)),
          });
          setNotice('Destino atualizado no mapa. Confira as coordenadas antes de criar.');
        });
        current.on('error', () => {
          if (alive)
            setNotice(
              'Parte do mapa está indisponível. As coordenadas e o andamento continuam acessíveis.',
            );
        });
      } catch {
        if (alive) setState('failed');
      }
    })();
    return () => {
      alive = false;
      abort.abort();
      clean();
    };
  }, [picking]);

  useEffect(() => {
    const current = map.current;
    if (state !== 'ready' || !current) return;
    markers.current.pickup?.setLngLat([props.pickup.longitude, props.pickup.latitude]);
    markers.current.dropoff?.setLngLat([props.dropoff.longitude, props.dropoff.latitude]);
    const controller = new AbortController();
    if (props.courier && !markers.current.courier) {
      void import('maplibre-gl').then(({ Marker }) => {
        if (controller.signal.aborted || map.current !== current || !latest.current.courier) return;
        const marker = document.createElement('span');
        marker.className = 'delivery-map-marker marker-courier';
        marker.setAttribute('aria-label', 'Entregador');
        marker.textContent = 'E';
        const point = latest.current.courier;
        markers.current.courier = new Marker({ element: marker })
          .setLngLat([point.longitude, point.latitude])
          .addTo(current);
      });
    } else if (props.courier)
      markers.current.courier?.setLngLat([props.courier.longitude, props.courier.latitude]);
    else {
      markers.current.courier?.remove();
      delete markers.current.courier;
    }
    const source = current.getSource('delivery-route') as GeoJSONSource | undefined;
    source?.setData(
      props.geometry?.type === 'LineString' && props.geometry.coordinates.length > 1
        ? {
            type: 'Feature',
            properties: {},
            geometry: { type: 'LineString', coordinates: props.geometry.coordinates },
          }
        : emptyRoute,
    );
    return () => controller.abort();
  }, [state, props.pickup, props.dropoff, props.courier, props.geometry]);

  return (
    <div className="delivery-map-block">
      <div className="delivery-map-surface" ref={element} />
      {state === 'loading' && <p className="demo-description">Carregando mapa da entrega...</p>}
      {state === 'failed' && (
        <p className="demo-description">
          O mapa não carregou. Use as coordenadas e a linha do tempo para continuar.
        </p>
      )}
      <div className="delivery-map-tools">
        <span className="delivery-map-legend">
          <i className="legend-pickup" /> Loja <i className="legend-dropoff" /> Destino{' '}
          {!picking && (
            <>
              <i className="legend-courier" /> Entregador
            </>
          )}
        </span>
        <Button type="button" variant="outline" disabled={state !== 'ready'} onClick={fit}>
          <Crosshair aria-hidden="true" /> Enquadrar entrega
        </Button>
      </div>
      {picking && (
        <>
          <p className="demo-description">
            Clique no mapa para escolher o destino. Pelo teclado, mova o mapa com as setas e use o
            botão abaixo, ou preencha as coordenadas.
          </p>
          <Button
            type="button"
            variant="outline"
            disabled={state !== 'ready'}
            onClick={() => {
              const center = map.current?.getCenter();
              if (!center) return;
              props.onDestination?.({
                latitude: Number(center.lat.toFixed(6)),
                longitude: Number(center.lng.toFixed(6)),
              });
              setNotice('Centro do mapa definido como destino.');
            }}
          >
            <MapPin aria-hidden="true" /> Usar centro do mapa como destino
          </Button>
        </>
      )}
      <p className="delivery-map-notice" aria-live="polite">
        {notice}
      </p>
    </div>
  );
}
