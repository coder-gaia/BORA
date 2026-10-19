import { useCallback, useEffect, useRef, useState, type ComponentType } from 'react';
import { CourierMotion, type CouriersGeoJSON, type MapViewport } from '@/lib/courier-motion';
import { PublicMapFeed, type MapConnectionState } from '@/lib/public-map-feed';
import { previewProjection } from '@/lib/preview-projection';
import metadata from '@/generated/public-basemap.json';
import type { InteractivePublicMapProps } from './interactive-public-map';
import './preview-map.css';

type Launch = { bounds: MapViewport; displayed: CouriersGeoJSON };
const empty: CouriersGeoJSON = { type: 'FeatureCollection', features: [] };

export function PublicMap() {
  const [interactive, setInteractive] = useState<ComponentType<InteractivePublicMapProps> | null>(
    null,
  );
  const [launch, setLaunch] = useState<Launch | null>(null);
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(false);
  const [pause, setPause] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [returnedPoints, setReturnedPoints] = useState<CouriersGeoJSON>(empty);
  const exploreButton = useRef<HTMLButtonElement>(null);
  const shown = useRef<CouriersGeoJSON>(empty);
  const remember = useCallback((value: CouriersGeoJSON) => {
    shown.current = value;
  }, []);
  const currentDisplay = useCallback(() => shown.current, []);
  const generation = useRef(0);
  const focusFrame = useRef(0);
  useEffect(
    () => () => {
      generation.current++;
      cancelAnimationFrame(focusFrame.current);
    },
    [],
  );
  const togglePause = useCallback(() => setPause((value) => !value), []);
  const focusExplore = useCallback(() => {
    cancelAnimationFrame(focusFrame.current);
    focusFrame.current = requestAnimationFrame(() =>
      exploreButton.current?.focus({ preventScroll: true }),
    );
  }, []);
  const failed = useCallback(() => {
    generation.current++;
    setInteractive(null);
    setReady(false);
    setLoading(false);
    setError('Não foi possível abrir o mapa interativo. A prévia continua disponível.');
    focusExplore();
  }, [focusExplore]);
  const loaded = useCallback((canvas: HTMLCanvasElement) => {
    setReady(true);
    setLoading(false);
    cancelAnimationFrame(focusFrame.current);
    focusFrame.current = requestAnimationFrame(() => canvas.focus({ preventScroll: true }));
  }, []);
  const back = useCallback(
    (displayed: CouriersGeoJSON) => {
      generation.current++;
      shown.current = displayed;
      setReturnedPoints(displayed);
      setInteractive(null);
      setLaunch(null);
      setReady(false);
      setLoading(false);
      setError(null);
      focusExplore();
    },
    [focusExplore],
  );
  async function explore(value: Launch) {
    if (loading) return;
    const current = ++generation.current;
    setLaunch(value);
    setLoading(true);
    setError(null);
    try {
      const module = await import('./interactive-public-map');
      if (generation.current === current) setInteractive(() => module.InteractivePublicMap);
    } catch {
      if (generation.current === current) failed();
    }
  }
  const Interactive = interactive;
  return (
    <div className="public-map-stage">
      {!ready && (
        <PreviewMap
          onExplore={(value) => void explore(value)}
          pause={pause}
          onTogglePause={togglePause}
          loading={loading}
          error={error}
          exploreButton={exploreButton}
          initialDisplayed={returnedPoints}
          onDisplayed={remember}
        />
      )}
      {Interactive && launch && (
        <div
          className={`interactive-map-layer ${ready ? 'is-ready' : 'is-loading'}`}
          aria-hidden={!ready}
          inert={!ready}
        >
          <Interactive
            initialBounds={launch.bounds}
            initialDisplayed={launch.displayed}
            currentDisplay={currentDisplay}
            pause={pause}
            onTogglePause={togglePause}
            onReady={loaded}
            onFailure={failed}
            onBack={back}
          />
        </div>
      )}
    </div>
  );
}

type PreviewProps = {
  onExplore: (value: Launch) => void;
  pause: boolean;
  onTogglePause: () => void;
  loading: boolean;
  error: string | null;
  exploreButton: React.RefObject<HTMLButtonElement | null>;
  initialDisplayed: CouriersGeoJSON;
  onDisplayed: (value: CouriersGeoJSON) => void;
};
function PreviewMap({
  onExplore,
  pause,
  onTogglePause,
  loading,
  error,
  exploreButton,
  initialDisplayed,
  onDisplayed,
}: PreviewProps) {
  const container = useRef<HTMLElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  const motion = useRef(new CourierMotion());
  const displayed = useRef(initialDisplayed);
  const paused = useRef(pause);
  const wake = useRef<(force?: boolean) => void>(() => {});
  const [count, setCount] = useState<number | null>(null);
  const [connection, setConnection] = useState<MapConnectionState>('connecting');
  const [imageFailed, setImageFailed] = useState(false);
  useEffect(() => {
    // The prerendered image may fail before React attaches its error handler.
    let alive = true;
    const image = container.current?.querySelector('img');
    if (image?.complete && image.naturalWidth === 0) {
      queueMicrotask(() => {
        if (alive) setImageFailed(true);
      });
    }
    return () => {
      alive = false;
    };
  }, []);
  useEffect(() => {
    paused.current = pause;
    if (!pause) wake.current(true);
  }, [pause]);
  useEffect(() => {
    const element = container.current!;
    const surface = canvas.current!;
    const context = surface.getContext('2d');
    if (!context) return;
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    let frame = 0;
    let liveFrame = false;
    const feed = new PublicMapFeed((value) => {
      if (motion.current.receive(value, performance.now())) {
        liveFrame = true;
        setCount(value.points.length);
        schedule();
      }
    }, setConnection);
    function draw(now: number) {
      frame = 0;
      if (document.visibilityState !== 'visible') return;
      const width = element.clientWidth,
        height = element.clientHeight;
      if (!width || !height) return;
      const ratio = Math.min(devicePixelRatio, 2);
      if (
        surface.width !== Math.round(width * ratio) ||
        surface.height !== Math.round(height * ratio)
      ) {
        surface.width = Math.round(width * ratio);
        surface.height = Math.round(height * ratio);
      }
      context!.setTransform(ratio, 0, 0, ratio, 0, 0);
      context!.clearRect(0, 0, width, height);
      // Paused redraws only reproject the exact geographic sample painted previously.
      if (!paused.current && liveFrame)
        displayed.current = motion.current.sample(now, !reduced.matches);
      onDisplayed(displayed.current);
      const projection = previewProjection(metadata, width, height);
      for (const point of displayed.current.features) {
        const [lon, lat] = point.geometry.coordinates;
        const position = projection.project(lon, lat);
        const radius = 4.5;
        if (position.x < 0 || position.x > width || position.y < 0 || position.y > height) continue;
        context!.beginPath();
        context!.arc(position.x, position.y, radius, 0, Math.PI * 2);
        context!.fillStyle = point.properties.status === 'BUSY' ? '#3f3f46' : '#8b8c87';
        context!.strokeStyle = point.properties.status === 'OFFERED' ? '#ffc21a' : '#fff';
        context!.lineWidth = point.properties.status === 'OFFERED' ? 2 : 1.5;
        context!.fill();
        context!.stroke();
      }
      if (!paused.current && !reduced.matches && motion.current.isAnimating(now))
        frame = requestAnimationFrame(draw);
    }
    function schedule(force = false) {
      if ((!paused.current || force) && !frame) frame = requestAnimationFrame(draw);
    }
    function resize() {
      if (element.clientWidth && element.clientHeight)
        feed.setViewport(
          previewProjection(metadata, element.clientWidth, element.clientHeight).viewport,
        );
      schedule(true);
    }
    const refresh = () => schedule(true);
    const observer = new ResizeObserver(resize);
    observer.observe(element);
    resize();
    feed.start();
    wake.current = schedule;
    document.addEventListener('visibilitychange', refresh);
    reduced.addEventListener('change', refresh);
    return () => {
      observer.disconnect();
      feed.stop();
      cancelAnimationFrame(frame);
      wake.current = () => {};
      document.removeEventListener('visibilitychange', refresh);
      reduced.removeEventListener('change', refresh);
    };
  }, [onDisplayed]);
  return (
    <section
      ref={container}
      className="public-map preview-map"
      aria-label="Prévia ao vivo de São Paulo sobre base fixa"
    >
      <img
        className="preview-basemap"
        src={metadata.assetUrl}
        width={metadata.rasterWidth}
        height={metadata.rasterHeight}
        alt="Base cartográfica real da região central de São Paulo, com ruas e bairros em uma área fixa"
        fetchPriority="high"
        onError={() => setImageFailed(true)}
      />
      <canvas ref={canvas} className="public-map-couriers" aria-hidden="true" />
      <div className="public-map-top">
        <span className="public-map-live" role="status">
          {connection === 'connected' ? 'Prévia ao vivo' : 'Conectando'}
        </span>
        <button
          type="button"
          className="public-map-motion"
          aria-pressed={pause}
          onClick={() => {
            paused.current = !pause;
            onTogglePause();
          }}
        >
          {pause ? 'Retomar movimento' : 'Pausar movimento'}
        </button>
      </div>
      <div className="preview-explore">
        <button
          ref={exploreButton}
          type="button"
          disabled={loading}
          onClick={() => {
            const element = container.current!;
            onExplore({
              bounds: previewProjection(metadata, element.clientWidth, element.clientHeight)
                .viewport,
              displayed: displayed.current,
            });
          }}
        >
          {loading ? 'Abrindo mapa...' : 'Explorar mapa'} <span aria-hidden="true">↗</span>
        </button>
        <span>Mover e ajustar zoom</span>
      </div>
      {(error || imageFailed) && (
        <p className="preview-map-notice" role="status">
          {error ??
            'A base do mapa está indisponível. Você ainda pode explorar o mapa ou usar a demonstração.'}
        </p>
      )}
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
      <div className="preview-attribution">
        <a href="https://openfreemap.org/">OpenFreeMap</a> ·{' '}
        <a href="https://openmaptiles.org/">OpenMapTiles</a> · ©{' '}
        <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>
      </div>
    </section>
  );
}
