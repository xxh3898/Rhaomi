import type { AdminApiTransport } from "@/features/admin-auth/types";

import { isMediaItem, isMediaList, type MediaItem, type MediaStatus } from "./types";

const MEDIA_PATH = "/api/admin/media";

export class AdminMediaApi {
  readonly #transport: AdminApiTransport;

  constructor(transport: AdminApiTransport) {
    this.#transport = transport;
  }

  list(): Promise<readonly MediaItem[]> {
    return this.#transport.requestAuthenticatedJson(MEDIA_PATH, isMediaList);
  }

  content(id: string): Promise<Blob> {
    return this.#transport.requestAuthenticatedBlob(
      `${MEDIA_PATH}/${id}/content`,
      ["image/jpeg", "image/png"],
    );
  }

  upload(file: File): Promise<MediaItem> {
    const body = new FormData();
    body.append("file", file);
    return this.#transport.requestMultipartMutation(
      MEDIA_PATH,
      "POST",
      body,
      isMediaItem,
    );
  }

  updateStatus(id: string, status: MediaStatus): Promise<MediaItem> {
    return this.#transport.requestJsonMutation(
      `${MEDIA_PATH}/${id}`,
      "PUT",
      { status },
      isMediaItem,
    );
  }
}
