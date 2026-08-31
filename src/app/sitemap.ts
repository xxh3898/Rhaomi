import type { MetadataRoute } from "next";

import {
  absolutePublicUrl,
  getGeneratedArtifacts,
} from "../public-site/content";

export const dynamic = "force-static";

export default function sitemap(): MetadataRoute.Sitemap {
  const { content } = getGeneratedArtifacts();
  return [
    {
      url: absolutePublicUrl("/"),
      lastModified: new Date(content.generatedAt),
      changeFrequency: "weekly",
      priority: 1,
    },
    ...content.notices.map((notice) => ({
      url: absolutePublicUrl(`/notices/${notice.slug}/`),
      lastModified: new Date(notice.publishedAt),
      changeFrequency: "monthly" as const,
      priority: notice.pinned ? 0.8 : 0.6,
    })),
  ];
}
