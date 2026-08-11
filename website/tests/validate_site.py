from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit
import struct


SITE_ROOT = Path(__file__).resolve().parents[1]
PROJECT_PREFIX = "/hi-mochi/"


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: set[str] = set()
        self.anchor_refs: list[str] = []
        self.resources: list[str] = []
        self.html_language: str | None = None
        self.canonical: str | None = None

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        values = dict(attrs)
        if tag == "html":
            self.html_language = values.get("lang")
        if element_id := values.get("id"):
            self.ids.add(element_id)
        if tag == "a" and (href := values.get("href", "")).startswith("#"):
            self.anchor_refs.append(href[1:])
        if tag == "link" and values.get("rel") == "canonical":
            self.canonical = values.get("href")

        key = "href" if tag in {"a", "link"} else "src" if tag in {"img", "script"} else None
        if key and (resource := values.get(key)):
            self.resources.append(resource)


def local_resource(page: Path, value: str) -> Path | None:
    parsed = urlsplit(value)
    if parsed.scheme or value.startswith(("#", "//", "mailto:")):
        return None

    path = parsed.path
    if path.startswith(PROJECT_PREFIX):
        target = SITE_ROOT / path.removeprefix(PROJECT_PREFIX)
    elif path == PROJECT_PREFIX.rstrip("/"):
        target = SITE_ROOT
    else:
        target = page.parent / path

    if path.endswith("/"):
        target /= "index.html"
    return target.resolve()


def png_dimensions(path: Path) -> tuple[int, int]:
    with path.open("rb") as stream:
        if stream.read(8) != b"\x89PNG\r\n\x1a\n":
            raise ValueError(f"{path} is not a PNG")
        stream.read(8)
        return struct.unpack(">II", stream.read(8))


def main() -> None:
    errors: list[str] = []
    pages = sorted(SITE_ROOT.rglob("*.html"))

    for page in pages:
        parser = PageParser()
        parser.feed(page.read_text(encoding="utf-8"))
        relative_page = page.relative_to(SITE_ROOT)

        for anchor in parser.anchor_refs:
            if anchor and anchor not in parser.ids:
                errors.append(f"{relative_page}: missing anchor #{anchor}")

        for resource in parser.resources:
            target = local_resource(page, resource)
            if target is not None and not target.exists():
                errors.append(f"{relative_page}: missing resource {resource}")

        if page.name == "index.html" and "zh-CN" not in page.parts:
            if parser.html_language != "en":
                errors.append("index.html: expected lang=en")
            if parser.canonical != "https://gongpx20069.github.io/hi-mochi/":
                errors.append("index.html: incorrect canonical URL")

        if "zh-CN" in page.parts:
            if parser.html_language != "zh-CN":
                errors.append("zh-CN/index.html: expected lang=zh-CN")
            if parser.canonical != "https://gongpx20069.github.io/hi-mochi/zh-CN/":
                errors.append("zh-CN/index.html: incorrect canonical URL")

    og_image = SITE_ROOT / "assets" / "mochi-og.png"
    if png_dimensions(og_image) != (1200, 630):
        errors.append("assets/mochi-og.png: expected 1200x630")

    if errors:
        raise SystemExit("\n".join(errors))

    print(f"Validated {len(pages)} website pages.")


if __name__ == "__main__":
    main()
