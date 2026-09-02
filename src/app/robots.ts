import type { MetadataRoute } from "next";

import { absolutePublicUrl } from "../public-site/content";

export const dynamic = "force-static";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [
      {
        userAgent: "*",
        allow: "/",
        disallow: ["/admin/", "/api/", "/actuator/"],
      },
    ],
    sitemap: absolutePublicUrl("/sitemap.xml"),
  };
}
