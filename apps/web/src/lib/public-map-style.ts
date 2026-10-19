import type { Map as MapLibre, StyleSpecification } from 'maplibre-gl';

export function publicMapBackground(style: StyleSpecification): string | null {
  const backgrounds = style.layers.filter((layer) => layer.type === 'background');
  const base = backgrounds.length === 1 ? backgrounds[0] : undefined;
  if (
    !base ||
    base.type !== 'background' ||
    base.minzoom !== undefined ||
    base.maxzoom !== undefined ||
    base.layout?.visibility === 'none' ||
    base.paint?.['background-pattern'] !== undefined ||
    (base.paint?.['background-opacity'] ?? 1) !== 1 ||
    typeof base.paint?.['background-color'] !== 'string'
  )
    return null;
  return base.paint['background-color'];
}

// The public map is bounded to central Sao Paulo. Keep the Positron city layers,
// without airport/shield sprites and administrative borders outside this purpose.
export function publicMapStyle(style: StyleSpecification): StyleSpecification {
  const background = publicMapBackground(style);
  const ignored = new Set([
    'landcover_ice_shelf',
    'landcover_glacier',
    'boundary_2',
    'boundary_3',
    'boundary_disputed',
  ]);
  return {
    ...style,
    sprite: undefined,
    layers: style.layers
      .filter(
        (layer) =>
          !ignored.has(layer.id) &&
          !(background !== null && layer.type === 'background') &&
          !layer.id.startsWith('aeroway') &&
          !layer.id.endsWith('_dashline') &&
          !(layer.type === 'symbol' && layer.layout?.['icon-image']),
      )
      .map((layer) => {
        if (layer.type === 'fill')
          return {
            ...layer,
            paint: { ...layer.paint, 'fill-antialias': false },
          };
        if (layer.type === 'symbol') {
          return {
            ...layer,
            layout: { ...layer.layout, 'text-font': ['Noto Sans Regular'] },
          };
        }
        if (
          layer.type === 'line' &&
          Array.isArray(layer.paint?.['line-dasharray']) &&
          layer.paint['line-dasharray'].length === 2 &&
          layer.paint['line-dasharray'][1] === 0
        ) {
          const paint = { ...layer.paint };
          delete paint['line-dasharray'];
          return { ...layer, paint };
        }
        return layer;
      }),
  };
}

export function initialPublicMapStyle(style: StyleSpecification): StyleSpecification {
  return {
    ...style,
    layers: style.layers.filter((layer) => layer.type === 'background' || layer.type === 'fill'),
  };
}

// Let each useful map paint finish before adding the next group of urban detail.
// No timer or interaction gate: normal loading always includes all streets and labels.
export function mountPublicMapLayers(
  map: MapLibre,
  style: StyleSpecification,
  onReady: () => void = () => {},
) {
  const groups = [
    style.layers.filter((layer) => layer.type === 'line'),
    style.layers.filter((layer) => !['background', 'fill', 'line'].includes(layer.type)),
  ];
  let frame = 0;
  let phase = 0;
  let stopped = false;
  const advance = () => {
    if (stopped || phase >= groups.length) return;
    frame = requestAnimationFrame(() => {
      if (stopped) return;
      const group = groups[phase++]!;
      for (const layer of group) {
        const index = style.layers.findIndex((candidate) => candidate.id === layer.id);
        const before = style.layers
          .slice(index + 1)
          .find((candidate) => map.getLayer(candidate.id));
        map.addLayer(layer, before?.id);
      }
      if (phase < groups.length) map.once('idle', advance);
      else map.once('idle', ready);
    });
  };
  const ready = () => {
    if (!stopped) onReady();
  };
  map.once('idle', advance);
  return () => {
    stopped = true;
    cancelAnimationFrame(frame);
    map.off('idle', advance);
    map.off('idle', ready);
  };
}
