import { useEffect, useRef, useState } from 'react';
import { CourierCanvas } from '@/lib/courier-canvas';
import {
  initialPublicMapStyle,
  mountPublicMapLayers,
  publicMapBackground,
  publicMapStyle,
} from '@/lib/public-map-style';
import {
  CourierMotion,
  type MapFrame,
  type MapViewport,
  type CouriersGeoJSON,
} from '@/lib/courier-motion';
import type { MapConnectionState } from '@/lib/public-map-feed';

export type InteractivePublicMapProps = {
  initialBounds: MapViewport;
  initialDisplayed: CouriersGeoJSON;
  currentDisplay: () => CouriersGeoJSON;
  pause: boolean;
  onTogglePause: () => void;
  onReady: (canvas: HTMLCanvasElement) => void;
  onFailure: () => void;
  onBack: (displayed: CouriersGeoJSON) => void;
};
export function InteractivePublicMap({
  initialBounds,
  initialDisplayed,
  currentDisplay,
  pause,
  onTogglePause,
  onReady,
  onFailure,
  onBack,
}: InteractivePublicMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const motion = useRef(new CourierMotion());
  const paused = useRef(pause);
  const canvasLayer = useRef<CourierCanvas | null>(null);
  const wake = useRef(() => {});
  const [connection, setConnection] = useState<MapConnectionState>('connecting');
  const [count, setCount] = useState<number | null>(null);

  useEffect(() => {
    paused.current = pause;
    if (pause) canvasLayer.current?.freeze();
    else wake.current();
  }, [pause]);

  useEffect(() => {
    let alive = true;
    const styleRequest = new AbortController();
    let clean = () => {};
    let secondFrame = 0;
    const timer = window.requestAnimationFrame(() => {
      secondFrame = window.requestAnimationFrame(() => {
        void (async () => {
          try {
            const [
              { Map, NavigationControl, AttributionControl, setWorkerUrl },
              { default: workerUrl },
              style,
              { PublicMapFeed },
            ] = await Promise.all([
              import('maplibre-gl'),
              import('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'),
              fetch('https://tiles.openfreemap.org/styles/positron', {
                signal: AbortSignal.any([styleRequest.signal, AbortSignal.timeout(8_000)]),
              }).then((response) => {
                if (!response.ok) throw new Error('Map unavailable');
                return response.json();
              }),
              import('@/lib/public-map-feed'),
              import('maplibre-gl/dist/maplibre-gl.css'),
            ]);
            if (!alive || !container.current) return;
            setWorkerUrl(workerUrl);
            container.current.style.backgroundColor = publicMapBackground(style) ?? '';
            const fullStyle = publicMapStyle(style);
            const map = new Map({
              container: container.current,
              style: initialPublicMapStyle(fullStyle),
              center: [-46.645, -23.556],
              zoom: 12.4,
              bounds: initialBounds
                ? [
                    [initialBounds.west, initialBounds.south],
                    [initialBounds.east, initialBounds.north],
                  ]
                : undefined,
              maxBounds: [
                [-46.85, -23.75],
                [-46.35, -23.35],
              ],
              attributionControl: false,
              cooperativeGestures: true,
              maxTileCacheSize: 128,
              pixelRatio: Math.min(window.devicePixelRatio, 2),
              locale: {
                'Map.Title': 'Mapa de São Paulo',
                'NavigationControl.ZoomIn': 'Aproximar',
                'NavigationControl.ZoomOut': 'Afastar',
                'NavigationControl.ResetBearing': 'Orientar para o norte',
                'AttributionControl.ToggleAttribution': 'Créditos do mapa',
                'CooperativeGesturesHandler.WindowsHelpText':
                  'Use Ctrl e a roda do mouse para ajustar o zoom',
                'CooperativeGesturesHandler.MacHelpText':
                  'Use Command e a roda do mouse para ajustar o zoom',
                'CooperativeGesturesHandler.MobileHelpText': 'Use dois dedos para mover o mapa',
              },
            });
            const stopStyle = mountPublicMapLayers(map, fullStyle, () => {
              if (alive) {
                window.clearTimeout(loadTimeout);
                couriers?.freeze(currentDisplay());
                onReady(map.getCanvas());
              }
            });
            clean = () => {
              stopStyle();
              map.remove();
            };
            map.addControl(new NavigationControl({ showCompass: false }), 'bottom-right');
            map.addControl(new AttributionControl({ compact: false }), 'bottom-left');
            map
              .getCanvas()
              .setAttribute('aria-label', 'Mapa de entregadores simulados em São Paulo');
            map.getCanvas().setAttribute('aria-describedby', 'public-map-help');
            const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
            let frame = 0;
            let schedulePaint = () => {};
            let loaded = false;
            let liveFrame = false;
            let couriers: CourierCanvas | null = null;
            const loadTimeout = window.setTimeout(() => {
              if (alive) onFailure();
            }, 12_000);
            map.on('error', () => {
              if (alive && !loaded) onFailure();
            });
            const feed = new PublicMapFeed((value: MapFrame) => {
              if (!alive) return;
              if (motion.current.receive(value, performance.now())) {
                liveFrame = true;
                setCount(value.points.length);
                schedulePaint();
              }
            }, setConnection);
            const viewport = () => {
              const bounds = map.getBounds();
              feed.setViewport({
                west: bounds.getWest(),
                south: bounds.getSouth(),
                east: bounds.getEast(),
                north: bounds.getNorth(),
                scope: 'PUBLIC',
              });
            };
            map.on('moveend', viewport);
            map.on('load', () => {
              if (!alive) return;
              loaded = true;
              couriers = new CourierCanvas(map);
              canvasLayer.current = couriers;
              couriers.freeze(initialDisplayed);
              viewport();
              feed.start();
              const paint = (now: number) => {
                frame = 0;
                if (paused.current || document.visibilityState !== 'visible') return;
                if (liveFrame) {
                  couriers?.update(motion.current);
                  couriers?.resume();
                }
                couriers?.draw(now, !reduced.matches);
                if (!reduced.matches && motion.current.isAnimating(now)) {
                  frame = requestAnimationFrame(paint);
                }
              };
              schedulePaint = () => {
                if (!frame) frame = requestAnimationFrame(paint);
              };
              wake.current = schedulePaint;
              document.addEventListener('visibilitychange', schedulePaint);
              reduced.addEventListener('change', schedulePaint);
              schedulePaint();
            });
            clean = () => {
              window.clearTimeout(loadTimeout);
              cancelAnimationFrame(frame);
              feed.stop();
              document.removeEventListener('visibilitychange', schedulePaint);
              reduced.removeEventListener('change', schedulePaint);
              wake.current = () => {};
              stopStyle();
              canvasLayer.current = null;
              couriers?.remove();
              map.remove();
            };
          } catch {
            if (alive) onFailure();
          }
        })();
      });
    });
    return () => {
      alive = false;
      window.cancelAnimationFrame(timer);
      window.cancelAnimationFrame(secondFrame);
      styleRequest.abort();
      clean();
    };
  }, [initialBounds, initialDisplayed, currentDisplay, onReady, onFailure]);

  return (
    <section className="public-map" aria-label="São Paulo ao vivo">
      <div ref={container} className="public-map-canvas" />
      <p className="sr-only" id="public-map-help">
        Use as setas para mover o mapa, mais e menos para ajustar o zoom. A demonstração abaixo
        também funciona sem o mapa.
      </p>
      <div className="public-map-top">
        <span className="public-map-live" role="status">
          {connection === 'connected' ? 'Ao vivo' : 'Conectando'}
        </span>
        <button
          type="button"
          className="public-map-motion"
          aria-pressed={pause}
          onClick={() => {
            paused.current = !paused.current;
            onTogglePause();
            wake.current();
          }}
        >
          {pause ? 'Retomar movimento' : 'Pausar movimento'}
        </button>
      </div>
      <button
        type="button"
        className="public-map-back public-map-motion"
        onClick={() => onBack(canvasLayer.current?.snapshot() ?? initialDisplayed)}
      >
        Voltar à prévia
      </button>
      <div className="public-map-summary">
        <strong>
          {count === null ? 'Consultando a cidade...' : `${count} entregadores nesta área`}
        </strong>
        <ul className="public-map-legend" aria-label="Legenda do mapa">
          <li>
            <i aria-hidden="true" />
            Disponível
          </li>
          <li>
            <i aria-hidden="true" />
            Em entrega
          </li>
          <li className="legend-offered">
            <i aria-hidden="true" />
            Com oferta
          </li>
        </ul>
      </div>
    </section>
  );
}
