import type { Map as MapLibre } from 'maplibre-gl';
import type { CourierMotion, CourierStatus, CouriersGeoJSON } from '@/lib/courier-motion';

type ProjectedPoint = {
  id: string;
  worldStart: [number, number];
  worldTarget: [number, number];
  status: CourierStatus;
  start: { x: number; y: number };
  target: { x: number; y: number };
};

// One canvas. Camera projection runs only on snapshots or camera changes.
export class CourierCanvas {
  private readonly map: MapLibre;
  private readonly canvas = document.createElement('canvas');
  private readonly context: CanvasRenderingContext2D;
  private readonly resize: ResizeObserver;
  private motion: CourierMotion | null = null;
  private points: ProjectedPoint[] = [];
  private frozen: CouriersGeoJSON | null = null;
  private lastDrawnPoints: ProjectedPoint[] = [];
  private lastDrawnProgress = 1;
  private receivedAt = -Infinity;
  private width = 0;
  private height = 0;
  private readonly ratio = Math.min(window.devicePixelRatio, 2);
  private lastNow = 0;
  private animate = true;

  constructor(map: MapLibre) {
    this.map = map;
    const context = this.canvas.getContext('2d');
    if (!context) throw new Error('Canvas unavailable');
    this.context = context;
    this.canvas.className = 'public-map-couriers';
    this.canvas.setAttribute('aria-hidden', 'true');
    map.getContainer().append(this.canvas);
    this.resize = new ResizeObserver(this.project);
    this.resize.observe(map.getContainer());
    map.on('move', this.project);
  }

  update(motion: CourierMotion) {
    if (this.receivedAt === motion.frameTime) return;
    this.motion = motion;
    this.receivedAt = motion.frameTime;
    this.project();
  }

  private project = () => {
    if (this.frozen) {
      this.points = this.frozen.features.map((feature) => ({
        id: feature.id,
        status: feature.properties.status,
        worldStart: feature.geometry.coordinates,
        worldTarget: feature.geometry.coordinates,
        start: this.map.project(feature.geometry.coordinates),
        target: this.map.project(feature.geometry.coordinates),
      }));
    } else if (this.motion) {
      this.points = this.motion.segments().map(({ point, start, target }) => ({
        id: point[0],
        worldStart: start,
        worldTarget: target,
        status: point[3],
        start: this.map.project(start),
        target: this.map.project(target),
      }));
    }
    this.draw(this.lastNow, this.animate);
  };

  draw(now: number, animate: boolean) {
    this.lastNow = now;
    this.animate = animate;
    const container = this.map.getContainer();
    const width = container.clientWidth;
    const height = container.clientHeight;
    if (width !== this.width || height !== this.height) {
      this.width = width;
      this.height = height;
      this.canvas.width = Math.round(width * this.ratio);
      this.canvas.height = Math.round(height * this.ratio);
      this.context.setTransform(this.ratio, 0, 0, this.ratio, 0, 0);
    }
    const context = this.context;
    context.clearRect(0, 0, width, height);
    const progress =
      !this.frozen && animate ? Math.min(1, Math.max(0, (now - this.receivedAt) / 1_000)) : 1;
    this.lastDrawnPoints = this.points;
    this.lastDrawnProgress = progress;
    const radius = Math.max(3, Math.min(6, 3 + (this.map.getZoom() - 10) * 0.6));
    for (const status of ['AVAILABLE', 'BUSY', 'OFFERED'] satisfies CourierStatus[]) {
      context.beginPath();
      for (const point of this.points) {
        if (point.status !== status) continue;
        const x = point.start.x + (point.target.x - point.start.x) * progress;
        const y = point.start.y + (point.target.y - point.start.y) * progress;
        if (x < -radius || y < -radius || x > width + radius || y > height + radius) continue;
        context.moveTo(x + radius, y);
        context.arc(x, y, radius, 0, Math.PI * 2);
      }
      context.fillStyle = status === 'BUSY' ? '#3f3f46' : '#8b8c87';
      context.strokeStyle = status === 'OFFERED' ? '#ffc21a' : '#ffffff';
      context.lineWidth = status === 'OFFERED' ? 2 : 1.5;
      context.fill();
      context.stroke();
    }
  }

  snapshot(): CouriersGeoJSON {
    const progress = this.lastDrawnProgress;
    return {
      type: 'FeatureCollection',
      features: this.lastDrawnPoints.map((point) => ({
        type: 'Feature',
        id: point.id,
        geometry: {
          type: 'Point',
          coordinates: [
            point.worldStart[0] + (point.worldTarget[0] - point.worldStart[0]) * progress,
            point.worldStart[1] + (point.worldTarget[1] - point.worldStart[1]) * progress,
          ],
        },
        properties: { status: point.status },
      })),
    };
  }

  freeze(features = this.snapshot()) {
    this.frozen = features;
    this.project();
  }

  resume() {
    if (!this.frozen) return;
    this.frozen = null;
    this.project();
  }

  remove() {
    this.map.off('move', this.project);
    this.resize.disconnect();
    this.canvas.remove();
  }
}
