const REUSE_MS = 2000;

let cached: { at: number; promise: Promise<unknown> } | null = null;

export function shareParentChildren<T>(load: () => Promise<T>): Promise<T> {
  if (cached && Date.now() - cached.at < REUSE_MS) return cached.promise as Promise<T>;
  const promise = load();
  cached = { at: Date.now(), promise };
  promise.catch(() => {
    if (cached?.promise === promise) cached = null;
  });
  return promise;
}

export function forgetParentChildren(): void {
  cached = null;
}
