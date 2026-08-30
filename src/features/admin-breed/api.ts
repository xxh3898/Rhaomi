import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import {
  isBreed,
  isBreedList,
  isCreatedBreed,
  type Breed,
  type CreateBreedRequest,
  type UpdateBreedRequest,
} from "./types";

const BREEDS_PATH = "/api/admin/breeds";

export class AdminBreedApi {
  readonly #transport: AdminApiTransport;

  constructor(transport: AdminApiTransport) {
    this.#transport = transport;
  }

  list(): Promise<readonly Breed[]> {
    return this.#transport.requestAuthenticatedJson(BREEDS_PATH, isBreedList);
  }

  async get(id: string): Promise<Breed> {
    const response = await this.#transport.requestAuthenticatedJson(
      `${BREEDS_PATH}/${id}`,
      isBreed,
    );
    if (response.id !== id) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }

  create(request: CreateBreedRequest): Promise<Breed> {
    return this.#transport.requestJsonMutation(
      BREEDS_PATH,
      "POST",
      request,
      isCreatedBreed,
    );
  }

  async update(id: string, request: UpdateBreedRequest): Promise<Breed> {
    const response = await this.#transport.requestJsonMutation(
      `${BREEDS_PATH}/${id}`,
      "PUT",
      request,
      isBreed,
    );
    if (response.id !== id || response.status !== request.status) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }
}
