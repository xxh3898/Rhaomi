import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import {
  isCreatedNotice,
  isNotice,
  isNoticeList,
  type CreateNoticeRequest,
  type Notice,
  type UpdateNoticeRequest,
} from "./types";

const NOTICES_PATH = "/api/admin/notices";

export class AdminNoticeApi {
  readonly #transport: AdminApiTransport;

  constructor(transport: AdminApiTransport) {
    this.#transport = transport;
  }

  list(): Promise<readonly Notice[]> {
    return this.#transport.requestAuthenticatedJson(NOTICES_PATH, isNoticeList);
  }

  async get(id: string): Promise<Notice> {
    const response = await this.#transport.requestAuthenticatedJson(
      `${NOTICES_PATH}/${id}`,
      isNotice,
    );
    if (response.id !== id) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }

  create(request: CreateNoticeRequest): Promise<Notice> {
    return this.#transport.requestJsonMutation(
      NOTICES_PATH,
      "POST",
      request,
      isCreatedNotice,
    );
  }

  async update(id: string, request: UpdateNoticeRequest): Promise<Notice> {
    const response = await this.#transport.requestJsonMutation(
      `${NOTICES_PATH}/${id}`,
      "PUT",
      request,
      isNotice,
    );
    if (response.id !== id || response.status !== request.status) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }
}
