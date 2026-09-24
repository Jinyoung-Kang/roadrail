import { useApi } from "./api";
import type { Corridor } from "./types";

export function useCorridors() {
  return useApi<Corridor[]>("/api/v1/corridors");
}
