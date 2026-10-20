import { useCallback, useEffect, useRef, useState } from 'react';
import { offerWindow } from '@/lib/route-motion';

export function useOfferWindow(
  createdAt: string,
  expiresAt: string,
  serverTime: string,
  receivedAt: number,
  onExpired: () => void,
) {
  const [now, setNow] = useState(() => performance.now());
  const notified = useRef(false);
  const callback = useRef(onExpired);
  useEffect(() => {
    callback.current = onExpired;
  });
  const expireOnce = useCallback(() => {
    if (!notified.current) {
      notified.current = true;
      callback.current();
    }
  }, []);
  const value = offerWindow(createdAt, expiresAt, serverTime, receivedAt, now);
  useEffect(() => {
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const timer = window.setInterval(
      () => setNow(performance.now()),
      reduced.matches ? 1_000 : 100,
    );
    const visible = () => {
      if (document.visibilityState === 'visible') setNow(performance.now());
    };
    document.addEventListener('visibilitychange', visible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', visible);
    };
  }, []);
  useEffect(() => {
    if (value.seconds === 0) expireOnce();
  }, [value.seconds, expireOnce]);
  function canDecide() {
    const current = offerWindow(createdAt, expiresAt, serverTime, receivedAt, performance.now());
    if (current.seconds === 0) expireOnce();
    return current.seconds > 0;
  }
  return { ...value, canDecide };
}
