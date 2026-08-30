import { describe, expect, it } from "vitest";

import {
  buildNoticeCreateRequest,
  buildNoticeUpdateRequest,
  EMPTY_NOTICE_DRAFT,
  isNotice,
  noticeToDraft,
  validateNoticeDraft,
  type Notice,
} from "./types";

const ACTOR_ID = "b0ce7a97-3f5a-4e5e-9d68-7f50aa0cf51d";
const NOTICE: Notice = {
  id: "d64047ee-93fe-4f87-949f-493d47ad6ee4",
  status: "draft",
  title: "추석 연휴 운영 안내",
  slug: "chuseok-hours",
  summary: "연휴 운영 시간을 알려드립니다.",
  bodyMarkdown: "## 운영 시간\n\n- 오전 10시\n- 오후 6시",
  pinned: true,
  publishedAt: "2026-09-01T00:00:00.123456Z",
  expiresAt: "2026-09-10T00:00:00.123457Z",
  createdAt: "2026-08-30T00:00:00.000001Z",
  updatedAt: "2026-08-30T00:00:00.000002Z",
  createdBy: ACTOR_ID,
  updatedBy: ACTOR_ID,
};

describe("notice types", () => {
  it("12개 exact response key와 normalized contract를 검증한다", () => {
    expect(isNotice(NOTICE)).toBe(true);
    expect(isNotice({ ...NOTICE, passwordHash: "must-not-pass" })).toBe(false);
    expect(isNotice({ ...NOTICE, title: "\t\n" })).toBe(false);
    expect(isNotice({ ...NOTICE, slug: "Chuseok-Hours" })).toBe(false);
    expect(isNotice({ ...NOTICE, createdAt: "2026-08-30T00:00:00.1234567Z" })).toBe(
      false,
    );
  });

  it("published response의 body·publishedAt과 모든 상태의 window를 검증한다", () => {
    expect(isNotice({ ...NOTICE, status: "published" })).toBe(true);
    expect(
      isNotice({ ...NOTICE, status: "published", bodyMarkdown: null }),
    ).toBe(false);
    expect(
      isNotice({ ...NOTICE, status: "published", publishedAt: null, expiresAt: null }),
    ).toBe(false);
    expect(isNotice({ ...NOTICE, expiresAt: NOTICE.publishedAt })).toBe(false);
    expect(
      isNotice({ ...NOTICE, status: "archived", publishedAt: null, expiresAt: NOTICE.expiresAt }),
    ).toBe(false);
  });

  it("create request를 항상 status 없이 normalized full body로 만든다", () => {
    const request = buildNoticeCreateRequest({
      ...EMPTY_NOTICE_DRAFT,
      status: "published",
      title: "  추석 연휴 운영 안내  ",
      slug: "chuseok-hours",
      summary: "  연휴 안내  ",
      bodyMarkdown: "  ## 운영 시간\n\n`원문`  ",
      pinned: true,
      publishedAt: "2026-09-01T09:00:00.123456",
      expiresAt: "2026-09-01T09:00:00.123457",
    });

    expect(request).toEqual({
      title: "추석 연휴 운영 안내",
      slug: "chuseok-hours",
      summary: "연휴 안내",
      bodyMarkdown: "## 운영 시간\n\n`원문`",
      pinned: true,
      publishedAt: expect.stringMatching(/\.123456Z$/),
      expiresAt: expect.stringMatching(/\.123457Z$/),
    });
    expect(request).not.toHaveProperty("status");
  });

  it("update request에서 immutable slug와 audit field를 제외한다", () => {
    const draft = noticeToDraft({ ...NOTICE, status: "published" });
    const request = buildNoticeUpdateRequest(draft);

    expect(request).toEqual({
      status: "published",
      title: NOTICE.title,
      summary: NOTICE.summary,
      bodyMarkdown: NOTICE.bodyMarkdown,
      pinned: true,
      publishedAt: NOTICE.publishedAt,
      expiresAt: NOTICE.expiresAt,
    });
    expect(request).not.toHaveProperty("slug");
    expect(request).not.toHaveProperty("updatedAt");
  });

  it("unchanged local input은 backend microsecond Instant를 그대로 보존한다", () => {
    const draft = noticeToDraft(NOTICE);

    expect(buildNoticeUpdateRequest(draft)?.publishedAt).toBe(NOTICE.publishedAt);
    expect(buildNoticeUpdateRequest(draft)?.expiresAt).toBe(NOTICE.expiresAt);
  });

  it("같은 시각은 거부하고 정확히 1 microsecond 차이와 지난 유효 window는 허용한다", () => {
    const base = {
      ...EMPTY_NOTICE_DRAFT,
      title: "운영 안내",
      slug: "hours",
      publishedAt: "2020-01-01T00:00:00.000000",
    };

    expect(
      validateNoticeDraft(
        { ...base, expiresAt: "2020-01-01T00:00:00.000000" },
        "create",
      ),
    ).toBe("window-invalid");
    expect(
      buildNoticeCreateRequest({
        ...base,
        expiresAt: "2020-01-01T00:00:00.000001",
      }),
    ).not.toBeNull();
  });

  it("expires-only와 published 필수값 부족을 서로 다른 validation으로 구분한다", () => {
    expect(
      validateNoticeDraft(
        {
          ...EMPTY_NOTICE_DRAFT,
          title: "운영 안내",
          slug: "hours",
          expiresAt: "2026-09-01T00:00",
        },
        "create",
      ),
    ).toBe("window-invalid");
    expect(
      validateNoticeDraft(
        {
          ...EMPTY_NOTICE_DRAFT,
          status: "published",
          title: "운영 안내",
          slug: "hours",
        },
        "update",
      ),
    ).toBe("publish-invalid");
  });

  it("160자 slug는 허용하고 161자 slug와 초과 본문을 거부한다", () => {
    const base = { ...EMPTY_NOTICE_DRAFT, title: "운영 안내" };
    expect(buildNoticeCreateRequest({ ...base, slug: "a".repeat(160) })).not.toBeNull();
    expect(buildNoticeCreateRequest({ ...base, slug: "a".repeat(161) })).toBeNull();
    expect(
      buildNoticeCreateRequest({
        ...base,
        slug: "hours",
        bodyMarkdown: "가".repeat(50_001),
      }),
    ).toBeNull();
  });
});
