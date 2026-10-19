import type { MapViewport } from './courier-motion';
export type BasemapMetadata = {
  width: number;
  height: number;
  bounds: { west: number; south: number; east: number; north: number };
};
const mx = (longitude: number) => (longitude + 180) / 360;
const my = (latitude: number) =>
  (1 - Math.log(Math.tan(Math.PI / 4 + (latitude * Math.PI) / 360)) / Math.PI) / 2;
const longitude = (x: number) => x * 360 - 180;
const latitude = (y: number) => (Math.atan(Math.sinh(Math.PI * (1 - 2 * y))) * 180) / Math.PI;
export function previewProjection(meta: BasemapMetadata, width: number, height: number) {
  const scale = Math.max(width / meta.width, height / meta.height);
  const offsetX = (width - meta.width * scale) / 2,
    offsetY = (height - meta.height * scale) / 2;
  const west = mx(meta.bounds.west),
    east = mx(meta.bounds.east),
    north = my(meta.bounds.north),
    south = my(meta.bounds.south);
  return {
    project: (lon: number, lat: number) => ({
      x: offsetX + ((mx(lon) - west) / (east - west)) * meta.width * scale,
      y: offsetY + ((my(lat) - north) / (south - north)) * meta.height * scale,
    }),
    viewport: {
      west: longitude(west + (-offsetX / (meta.width * scale)) * (east - west)),
      east: longitude(west + ((width - offsetX) / (meta.width * scale)) * (east - west)),
      north: latitude(north + (-offsetY / (meta.height * scale)) * (south - north)),
      south: latitude(north + ((height - offsetY) / (meta.height * scale)) * (south - north)),
      scope: 'PUBLIC',
    } satisfies MapViewport,
  };
}
