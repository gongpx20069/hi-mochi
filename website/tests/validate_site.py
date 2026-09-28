from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit
import re
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
        self.stack: list[str] = []
        self.errors: list[str] = []

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        values = dict(attrs)
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input",
                       "link", "meta", "param", "source", "track", "wbr"}:
            self.stack.append(tag)
        if tag == "html":
            self.html_language = values.get("lang")
        if element_id := values.get("id"):
            if element_id in self.ids:
                self.errors.append(f"duplicate id: {element_id}")
            self.ids.add(element_id)
        if tag == "a" and (href := values.get("href", "")).startswith("#"):
            self.anchor_refs.append(href[1:])
        if tag == "link" and values.get("rel") == "canonical":
            self.canonical = values.get("href")

        key = "href" if tag in {"a", "link"} else "src" if tag in {"img", "script"} else None
        if key and (resource := values.get(key)):
            self.resources.append(resource)

    def handle_endtag(self, tag: str) -> None:
        if not self.stack or self.stack[-1] != tag:
            self.errors.append(f"unexpected closing tag: {tag}")
        else:
            self.stack.pop()


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
    fallback_versions: set[str] = set()

    for page in pages:
        parser = PageParser()
        parser.feed(page.read_text(encoding="utf-8"))
        relative_page = page.relative_to(SITE_ROOT)
        errors.extend(f"{relative_page}: {error}" for error in parser.errors)
        if parser.stack:
            errors.append(f"{relative_page}: unclosed tags {parser.stack}")

        for anchor in parser.anchor_refs:
            if anchor and anchor not in parser.ids:
                errors.append(f"{relative_page}: missing anchor #{anchor}")

        for resource in parser.resources:
            target = local_resource(page, resource)
            if target is not None and not target.exists():
                errors.append(f"{relative_page}: missing resource {resource}")
            elif target is not None and target.suffix == ".html" and urlsplit(resource).fragment:
                linked_page = PageParser()
                linked_page.feed(target.read_text(encoding="utf-8"))
                if urlsplit(resource).fragment not in linked_page.ids:
                    errors.append(f"{relative_page}: missing linked anchor {resource}")

        if page.name == "index.html":
            language = "zh-CN" if "zh-CN" in relative_page.parts else "en"
            if parser.html_language != language:
                errors.append(f"{relative_page}: expected lang={language}")
            route = relative_page.as_posix().removesuffix("index.html")
            if parser.canonical != f"https://gongpx20069.github.io/hi-mochi/{route}":
                errors.append(f"{relative_page}: incorrect canonical URL")

        if page in (SITE_ROOT / "index.html", SITE_ROOT / "zh-CN" / "index.html"):
            source = page.read_text(encoding="utf-8")
            for feature in ("smart-home", "speech", "documents", "connections", "tasks"):
                if source.count(f"data-{feature}-feature") != 1:
                    errors.append(
                        f"{relative_page}: expected one {feature} feature",
                    )
            smart_home_label = (
                "智能家居 · 米家"
                if "zh-CN" in page.parts
                else "Smart home · Mi Home"
            )
            if smart_home_label not in source:
                errors.append(
                    f"{relative_page}: missing localized smart-home label",
                )
            download_versions = re.findall(
                r'data-release-download href="[^"]+/download/'
                r'(v1\.0\.[1-9][0-9]*)/Mochi-\1-arm64-v8a\.apk"',
                source,
            )
            page_versions = re.findall(
                r'data-release-page href="[^"]+/tag/(v1\.0\.[1-9][0-9]*)"',
                source,
            )
            label_versions = re.findall(
                r"data-release-version>(v1\.0\.[1-9][0-9]*)</span>",
                source,
            )
            versions = set(download_versions + page_versions + label_versions)
            for extension, name in (("mijia", "Mijia"), ("termux", "Termux")):
                extension_versions = re.findall(
                    rf'data-extension-download="{extension}" href="https://github.com/'
                    rf'gongpx20069/hi-mochi/releases/download/(v1\.0\.[1-9][0-9]*)/'
                    rf'Mochi-{name}-Extension-\1\.apk"', source,
                )
                if len(extension_versions) != 1 or set(extension_versions) != versions:
                    errors.append(f"{relative_page}: inconsistent {extension} download")
            for extension in ("mijia", "termux", "agentlink"):
                if source.count(f'data-extension-card="{extension}"') != 1:
                    errors.append(f"{relative_page}: missing or duplicate {extension} card")
            if "extensions" not in parser.ids:
                errors.append(f"{relative_page}: missing extensions section")
            fallback_versions.update(versions)
            if (
                len(download_versions) != 2
                or len(page_versions) != 2
                or len(label_versions) != 3
                or len(versions) != 1
            ):
                errors.append(
                    f"{relative_page}: inconsistent release fallback metadata",
                )

    if len(fallback_versions) != 1:
        errors.append("Homepages must use the same release fallback in both languages.")

    for route in ("agentlink/index.html", "zh-CN/agentlink/index.html"):
        page = SITE_ROOT / route
        if not page.exists():
            errors.append(f"Missing AgentLink page: {route}")
        elif "data-release-download" in page.read_text(encoding="utf-8"):
            errors.append(f"{route}: AgentLink must not download the Mochi APK")

    og_image = SITE_ROOT / "assets" / "mochi-og.png"
    if png_dimensions(og_image) != (1200, 630):
        errors.append("assets/mochi-og.png: expected 1200x630")

    scripts = (SITE_ROOT / "scripts.js").read_text(encoding="utf-8")
    for marker in (
        "/releases/latest",
        "[data-release-version]",
        "[data-release-page]",
        "[data-release-download]",
    ):
        if marker not in scripts:
            errors.append(f"scripts.js: missing release updater marker {marker}")

    styles = (SITE_ROOT / "styles.css").read_text(encoding="utf-8")
    for marker in (
        ".smart-home-feature",
        ".smart-home-visual",
        ".smart-device-camera",
    ):
        if marker not in styles:
            errors.append(f"styles.css: missing smart-home style {marker}")
    if styles.count(".smart-home-feature") < 3:
        errors.append("styles.css: missing responsive smart-home sizing")

    if errors:
        raise SystemExit("\n".join(errors))

    print(f"Validated {len(pages)} website pages.")


if __name__ == "__main__":
    main()
