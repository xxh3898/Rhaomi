import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";
import { describe, expect, it } from "vitest";

import type { PublicMediaManifestItem } from "./contracts.mts";
import { renderNoticeMarkdown } from "./markdown";
import { ResponsiveMedia } from "./ResponsiveMedia";

describe("renderNoticeMarkdown", () => {
  it("renders the documented safe CommonMark subset", () => {
    const html = renderNoticeMarkdown(
      "# 제목\n\n- 항목\n\n**강조**와 [외부 링크](https://example.com/)",
    );

    expect(html).toContain("<h1>제목</h1>");
    expect(html).toContain("<li>항목</li>");
    expect(html).toContain("<strong>강조</strong>");
    expect(html).toContain('href="https://example.com/"');
    expect(html).toContain('rel="noopener noreferrer"');
  });

  it("escapes raw HTML and never emits Markdown images", () => {
    const html = renderNoticeMarkdown(
      '<script>alert(1)</script>\n\n<img src=x onerror=alert(1)>\n\n![원격 이미지](https://evil.example/a.jpg "x")',
    );

    expect(html).not.toContain("<script>");
    expect(html).not.toContain("<img");
    expect(html).not.toContain("onerror=\"");
    expect(html).toContain("&lt;script&gt;");
    expect(html).toContain("원격 이미지");
  });

  it("does not turn dangerous or protocol-relative targets into links", () => {
    const html = renderNoticeMarkdown(
      "[script](javascript:alert(1)) [data](data:text/html,x) [remote](//evil.example/x) [backslash](/\\\\evil.example/x) [userinfo](https://user:secret@example.com/)",
    );

    expect(html).not.toContain("<a ");
    expect(html).not.toContain('href="javascript:');
    expect(html).not.toContain('href="data:');
    expect(html).not.toContain('href="//evil.example');
    expect(html).not.toContain('href="https://user:secret@');
  });
});

describe("ResponsiveMedia", () => {
  it("uses only manifest paths for responsive picture sources", () => {
    const media: PublicMediaManifestItem = {
      mediaId: "00000000-0000-4000-8000-000000000041",
      variants: [
        {
          profile: "HERO",
          format: "avif",
          width: 768,
          height: 432,
          byteSize: 100,
          publicPath: `/generated/media/${"a".repeat(64)}.avif`,
        },
        {
          profile: "HERO",
          format: "webp",
          width: 768,
          height: 432,
          byteSize: 110,
          publicPath: `/generated/media/${"b".repeat(64)}.webp`,
        },
        {
          profile: "HERO",
          format: "jpeg",
          width: 768,
          height: 432,
          byteSize: 120,
          publicPath: `/generated/media/${"c".repeat(64)}.jpeg`,
        },
      ],
    };

    const html = renderToStaticMarkup(
      createElement(ResponsiveMedia, {
        media,
        profile: "HERO",
        alt: "합성 Hero",
        sizes: "100vw",
      }),
    );
    expect(html).toContain("<picture>");
    expect(html).toContain("image/avif");
    expect(html).toContain(`/generated/media/${"c".repeat(64)}.jpeg`);
    expect(html).toContain('alt="합성 Hero"');
    expect(html).not.toContain("/uploads/");
  });
});
