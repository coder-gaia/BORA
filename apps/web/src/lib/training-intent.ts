const prefix = 'bora:training-intent:';
function storage(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}
export function readTrainingIntent(userId: string) {
  try {
    return storage()?.getItem(prefix + userId) ?? null;
  } catch {
    return null;
  }
}
/** Persist only the retry key. Receipt and confirmation code stay in memory. */
export function saveTrainingIntent(userId: string, key: string | null) {
  try {
    if (key) storage()?.setItem(prefix + userId, key);
    else storage()?.removeItem(prefix + userId);
  } catch {
    /* Restricted storage leaves the in-memory retry available. */
  }
}
export function forgetTrainingIntents() {
  try {
    const values = storage();
    if (!values) return;
    for (let index = values.length - 1; index >= 0; index--) {
      const key = values.key(index);
      if (key?.startsWith(prefix)) values.removeItem(key);
    }
  } catch {
    /* Browser privacy settings may change during the session. */
  }
}
