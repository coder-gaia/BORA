import assert from 'node:assert/strict';
import { test } from 'node:test';
import { CourierCanvas } from '../src/lib/courier-canvas.ts';
import { CourierMotion } from '../src/lib/courier-motion.ts';
function setup(t) {
  const previous = {
    document: globalThis.document,
    window: globalThis.window,
    ResizeObserver: globalThis.ResizeObserver,
  };
  let scale = 1;
  const arcs = [];
  const context = {
    setTransform() {},
    clearRect() {
      arcs.length = 0;
    },
    beginPath() {},
    moveTo() {},
    arc(x, y) {
      arcs.push([x, y]);
    },
    fill() {},
    stroke() {},
  };
  const surface = {
    getContext: () => context,
    setAttribute() {},
    remove() {},
    width: 300,
    height: 150,
  };
  globalThis.document = { createElement: () => surface };
  globalThis.window = { devicePixelRatio: 1 };
  globalThis.ResizeObserver = class {
    observe() {}
    disconnect() {}
  };
  const listeners = new Map();
  const map = {
    getContainer: () => ({ clientWidth: 1000, clientHeight: 1000, append() {} }),
    project: ([x, y]) => ({ x: 500 + x * scale, y: 500 + y * scale }),
    getZoom: () => 12.4,
    on: (name, callback) => listeners.set(name, callback),
    off: (name) => listeners.delete(name),
  };
  const canvas = new CourierCanvas(map);
  t.after(() => {
    canvas.remove();
    Object.assign(globalThis, previous);
  });
  return {
    canvas,
    arcs,
    move: (nextScale) => {
      scale = nextScale;
      listeners.get('move')();
    },
  };
}
const frame = (time, coordinate) => ({
  serverTime: new Date(time).toISOString(),
  points: [['courier-a', coordinate, coordinate, 'AVAILABLE', 0]],
});
const coordinates = (canvas) => canvas.snapshot().features[0].geometry.coordinates;

test('pause freezes the actual last draw even if newer live motion arrives before the pause', (t) => {
  const { canvas, arcs, move } = setup(t);
  const motion = new CourierMotion();
  motion.receive(frame(1000, 0), 0);
  motion.receive(frame(2000, 10), 1000);
  canvas.update(motion);
  canvas.draw(1500, true);
  assert.deepEqual(coordinates(canvas), [5, 5]);
  motion.receive(frame(3000, 20), 1700);
  canvas.freeze();
  canvas.update(motion);
  canvas.draw(2200, true);
  move(2);
  assert.deepEqual(coordinates(canvas), [5, 5]);
  assert.deepEqual(arcs, [[510, 510]]);
  canvas.resume();
  canvas.draw(2700, true);
  assert.deepEqual(coordinates(canvas), [20, 20]);
});

test('a paused handoff paints the supplied geographic sample and reprojects it on camera changes', (t) => {
  const { canvas, arcs, move } = setup(t);
  const frozen = {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        id: 'previous',
        geometry: { type: 'Point', coordinates: [7, 9] },
        properties: { status: 'BUSY' },
      },
    ],
  };
  canvas.freeze(frozen);
  assert.deepEqual(arcs, [[507, 509]]);
  move(3);
  assert.deepEqual(arcs, [[521, 527]]);
  assert.deepEqual(canvas.snapshot(), frozen);
});
