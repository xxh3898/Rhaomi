import type { AdminApiTransport } from "@/features/admin-auth/types";

import {
  isShopSettingsResponse,
  type ShopSettingsRequest,
  type ShopSettingsResponse,
} from "./types";

const SHOP_SETTINGS_PATH = "/api/admin/shop-settings";

export class AdminShopSettingsApi {
  readonly #transport: AdminApiTransport;

  constructor(transport: AdminApiTransport) {
    this.#transport = transport;
  }

  get(): Promise<ShopSettingsResponse> {
    return this.#transport.requestAuthenticatedJson(
      SHOP_SETTINGS_PATH,
      isShopSettingsResponse,
    );
  }

  put(request: ShopSettingsRequest): Promise<ShopSettingsResponse> {
    return this.#transport.requestJsonMutation(
      SHOP_SETTINGS_PATH,
      "PUT",
      request,
      isShopSettingsResponse,
    );
  }
}
