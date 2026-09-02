import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import {
  isGroomingService,
  isGroomingServiceList,
  isCreatedGroomingService,
  type CreateServiceRequest,
  type GroomingService,
  type UpdateServiceRequest,
} from "./types";

const SERVICES_PATH = "/api/admin/services";

export class AdminServiceApi {
  readonly #transport: AdminApiTransport;

  constructor(transport: AdminApiTransport) {
    this.#transport = transport;
  }

  list(): Promise<readonly GroomingService[]> {
    return this.#transport.requestAuthenticatedJson(
      SERVICES_PATH,
      isGroomingServiceList,
    );
  }

  async get(id: string): Promise<GroomingService> {
    const response = await this.#transport.requestAuthenticatedJson(
      `${SERVICES_PATH}/${id}`,
      isGroomingService,
    );
    if (response.id !== id) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }

  create(request: CreateServiceRequest): Promise<GroomingService> {
    return this.#transport.requestJsonMutation(
      SERVICES_PATH,
      "POST",
      request,
      isCreatedGroomingService,
    );
  }

  async update(
    id: string,
    request: UpdateServiceRequest,
  ): Promise<GroomingService> {
    const response = await this.#transport.requestJsonMutation(
      `${SERVICES_PATH}/${id}`,
      "PUT",
      request,
      isGroomingService,
    );
    if (response.id !== id || response.status !== request.status) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }
}
