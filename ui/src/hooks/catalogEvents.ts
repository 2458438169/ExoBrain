import type { AgentVO } from "../api/api.ts";
import type { KnowledgeBase } from "../types";

function createCatalogEvents<T>() {
  const listeners = new Set<(items: T[]) => void>();
  return {
    publish(items: T[]) { listeners.forEach((listener) => listener(items)); },
    subscribe(listener: (items: T[]) => void) {
      listeners.add(listener);
      return () => { listeners.delete(listener); };
    },
  };
}

export const agentCatalogEvents = createCatalogEvents<AgentVO>();
export const knowledgeCatalogEvents = createCatalogEvents<KnowledgeBase>();
