import MarkdownIt from "markdown-it";

const markdown = new MarkdownIt({
  html: false,
  linkify: false,
  typographer: false,
  breaks: false,
});

const RELATIVE_LINK_BASE = new URL("https://rhaomi.invalid/");

function isAllowedLink(value: string): boolean {
  if (
    value.length === 0 ||
    value !== value.trim() ||
    value.includes("\\") ||
    /%(?:2f|5c)/iu.test(value)
  ) {
    return false;
  }
  if (value.startsWith("#")) return true;
  if (value.startsWith("/")) {
    try {
      return new URL(value, RELATIVE_LINK_BASE).origin === RELATIVE_LINK_BASE.origin;
    } catch {
      return false;
    }
  }
  try {
    const parsed = new URL(value);
    return (
      ["https:", "http:", "mailto:", "tel:"].includes(parsed.protocol) &&
      parsed.username.length === 0 &&
      parsed.password.length === 0
    );
  } catch {
    return false;
  }
}

markdown.validateLink = isAllowedLink;

const defaultLinkOpen =
  markdown.renderer.rules.link_open ??
  ((tokens, index, options, _environment, renderer) =>
    renderer.renderToken(tokens, index, options));

markdown.renderer.rules.link_open = (
  tokens,
  index,
  options,
  environment,
  renderer,
) => {
  const href = String(tokens[index].attrGet("href") ?? "");
  if (/^https?:/u.test(href)) {
    tokens[index].attrSet("rel", "noopener noreferrer");
  }
  return defaultLinkOpen(tokens, index, options, environment, renderer);
};

markdown.renderer.rules.image = (tokens, index) =>
  markdown.utils.escapeHtml(String(tokens[index].content));

export function renderNoticeMarkdown(value: string): string {
  return markdown.render(value);
}

export function noticeDescription(markdownValue: string): string {
  return markdownValue
    .replace(/<[^>]*>/gu, " ")
    .replace(/!\[([^\]]*)\]\([^)]*\)/gu, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/gu, "$1")
    .replace(/[`*_>#~-]/gu, " ")
    .replace(/\s+/gu, " ")
    .trim()
    .slice(0, 160);
}
