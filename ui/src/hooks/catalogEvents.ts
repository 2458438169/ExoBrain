import type { AgentVO } from "../api/api.ts";
import type { KnowledgeBase } from "../types";

function createCatalogEvents<T>() {
  type Update = T[] | ((items: T[]) => T[]);
  const listeners = new Set<(items: Update) => void>();
  let revision = 0;
  return {
    get revision() { return revision; },
    publish(items: Update) { revision += 1; listeners.forEach((listener) => listener(items)); },
    subscribe(listener: (items: Update) => void) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
  };
}

export const agentCatalogEvents = createCatalogEvents<AgentVO>();
export const knowledgeCatalogEvents = createCatalogEvents<KnowledgeBase>();
