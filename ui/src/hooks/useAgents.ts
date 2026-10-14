import { useEffect, useState } from "react";
import {
  type AgentVO,
  createAgent,
  type CreateAgentRequest,
  getAgents,
  deleteAgent,
  updateAgent,
  type UpdateAgentRequest,
} from "../api/api.ts";
import { showRequestError } from "../api/http.ts";

export function useAgents() {
  const [agents, setAgents] = useState<AgentVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    let active = true;
    void getAgents().then((response) => {
      if (active) {
        setAgents(response.agents);
        setLoadFailed(false);
      }
    }).catch((error) => {
      if (active) {
        setLoadFailed(true);
        showRequestError(error, "助手加载失败，请稍后刷新");
      }
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  async function refreshAgents() {
    setLoading(true);
    setLoadFailed(false);
    try {
      const resp = await getAgents();
      setAgents(resp.agents);
    } catch (error) {
      setLoadFailed(true);
      throw error;
    } finally {
      setLoading(false);
    }
  }

  async function createAgentHandle(agent: CreateAgentRequest) {
    await createAgent(agent);
    await refreshAgents();
  }

  async function deleteAgentHandle(agentId: string) {
    await deleteAgent(agentId);
    await refreshAgents();
  }

  async function updateAgentHandle(
    agentId: string,
    request: UpdateAgentRequest,
  ) {
    await updateAgent(agentId, request);
    await refreshAgents();
  }

  return {
    agents,
    loading,
    loadFailed,
    createAgentHandle,
    deleteAgentHandle,
    updateAgentHandle,
    refreshAgents,
  };
}
