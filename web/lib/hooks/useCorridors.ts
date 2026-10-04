import { api } from "../api/client";
import type { Corridor } from "../types";
import { useApi } from "./useApi";

export function useCorridors() {
  return useApi<Corridor[]>(api.corridors());
}
