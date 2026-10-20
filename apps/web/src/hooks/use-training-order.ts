import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, type TrainingOrderReceipt } from '@/lib/api';
import { readTrainingIntent, saveTrainingIntent } from '@/lib/training-intent';

type ObservedOrder = { id: string; status: TrainingOrderReceipt['status']; version: number };
export function useTrainingOrder(
  userId: string,
  onChanged: () => Promise<void>,
  observedOrder?: ObservedOrder | null,
) {
  const queries = useQueryClient();
  const [storedReceipt, setReceipt] = useState<TrainingOrderReceipt | null>(null);
  const receipt =
    storedReceipt &&
    observedOrder?.id === storedReceipt.orderId &&
    observedOrder.version >= storedReceipt.version
      ? { ...storedReceipt, status: observedOrder.status, version: observedOrder.version }
      : storedReceipt;
  const [recoverable, setRecoverable] = useState(() => Boolean(readTrainingIntent(userId)));
  const alive = useRef(true);
  const intent = useRef<string | null>(readTrainingIntent(userId));
  const request = useRef<AbortController | null>(null);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      request.current?.abort();
    };
  }, []);
  function revoked(error: Error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      saveTrainingIntent(userId, null);
      intent.current = null;
      if (alive.current) {
        setReceipt(null);
        setRecoverable(false);
        void queries.invalidateQueries({ queryKey: ['session'] });
      }
    }
  }
  const create = useMutation({
    mutationFn: ({ key, signal }: { key: string; signal: AbortSignal }) =>
      api.trainingOrder(key, signal),
    async onSuccess(value, { key }) {
      if (!alive.current || intent.current !== key) return;
      setReceipt((current) =>
        current?.orderId === value.orderId && current.version > value.version
          ? { ...value, status: current.status, version: current.version }
          : value,
      );
      setRecoverable(true);
      await onChanged();
    },
    onError: revoked,
  });
  const cancel = useMutation({
    mutationFn: ({
      id,
      version,
      signal,
    }: {
      id: string;
      version: number;
      key: string | null;
      signal: AbortSignal;
    }) => api.cancelTrainingOrder(id, version, signal),
    async onSuccess(value, { id, key }) {
      if (!alive.current || intent.current !== key) return;
      setReceipt((current) =>
        current?.orderId === id
          ? { ...current, status: value.status, version: value.version }
          : current,
      );
      saveTrainingIntent(userId, null);
      intent.current = null;
      setRecoverable(false);
      await onChanged();
    },
    onError: revoked,
  });
  function startOrRecover() {
    if (create.isPending || cancel.isPending) return;
    if (!intent.current) {
      intent.current = crypto.randomUUID();
      saveTrainingIntent(userId, intent.current);
    }
    setRecoverable(true);
    request.current = new AbortController();
    create.mutate({ key: intent.current, signal: request.current.signal });
  }
  function finish() {
    if (
      create.isPending ||
      cancel.isPending ||
      !receipt ||
      !['SEARCHING', 'ASSIGNED', 'AT_STORE'].includes(receipt.status)
    )
      return;
    request.current = new AbortController();
    cancel.mutate({
      id: receipt.orderId,
      version: receipt.version,
      key: intent.current,
      signal: request.current.signal,
    });
  }
  function nextIntent() {
    if (
      !create.isPending &&
      !cancel.isPending &&
      receipt &&
      ['DELIVERED', 'CANCELED'].includes(receipt.status)
    ) {
      saveTrainingIntent(userId, null);
      intent.current = null;
      setReceipt(null);
      setRecoverable(false);
      create.reset();
      cancel.reset();
    }
  }
  const observeOrder = useCallback(
    (
      order:
        { id: string; status: TrainingOrderReceipt['status']; version: number } | null | undefined,
    ) => {
      if (!order) return;
      setReceipt((current) =>
        current?.orderId === order.id &&
        order.version >= current.version &&
        (current.status !== order.status || current.version !== order.version)
          ? { ...current, status: order.status, version: order.version }
          : current,
      );
    },
    [],
  );
  return {
    receipt,
    recoverable,
    pending: create.isPending || cancel.isPending,
    error: create.error ?? cancel.error,
    startOrRecover,
    finish,
    nextIntent,
    observeOrder,
  };
}
