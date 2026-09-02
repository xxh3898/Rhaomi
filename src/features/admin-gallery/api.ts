import { AdminApiError } from "@/features/admin-auth/api";
import type { AdminApiTransport } from "@/features/admin-auth/types";

import {
  isCreatedGalleryItem,
  isGalleryItem,
  isGalleryList,
  type CreateGalleryRequest,
  type GalleryItem,
  type UpdateGalleryRequest,
} from "./types";

const GALLERY_ITEMS_PATH = "/api/admin/gallery-items";

export class AdminGalleryApi {
  readonly #transport: AdminApiTransport;

  constructor(transport: AdminApiTransport) {
    this.#transport = transport;
  }

  list(): Promise<readonly GalleryItem[]> {
    return this.#transport.requestAuthenticatedJson(
      GALLERY_ITEMS_PATH,
      isGalleryList,
    );
  }

  async get(id: string): Promise<GalleryItem> {
    const response = await this.#transport.requestAuthenticatedJson(
      `${GALLERY_ITEMS_PATH}/${id}`,
      isGalleryItem,
    );
    if (response.id !== id) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }

  create(request: CreateGalleryRequest): Promise<GalleryItem> {
    return this.#transport.requestJsonMutation(
      GALLERY_ITEMS_PATH,
      "POST",
      request,
      isCreatedGalleryItem,
    );
  }

  async update(
    id: string,
    request: UpdateGalleryRequest,
  ): Promise<GalleryItem> {
    const response = await this.#transport.requestJsonMutation(
      `${GALLERY_ITEMS_PATH}/${id}`,
      "PUT",
      request,
      isGalleryItem,
    );
    if (response.id !== id || response.status !== request.status) {
      throw new AdminApiError("unavailable");
    }
    return response;
  }
}
