from __future__ import annotations

from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit
import re
import struct
import xml.etree.ElementTree as ET


SITE_ROOT = Path(__file__).resolve().parents[1]
PROJECT_PREFIX = "/hi-mochi/"


class PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: set[str] = set()
        self.anchor_refs: list[str] = []
        self.resources: list[str] = []
        self.images: list[dict[str, str | None]] = []
        self.html_language: str | None = None
        self.canonical: str | None = None
        self.alternates: dict[str, str] = {}
        self.text: list[str] = []
        self.stack: list[str] = []
        self.errors: list[str] = []
        self.in_primary_nav = False
        self.primary_links: list[dict[str, str | None]] = []

    def handle_starttag(
        self,
        tag: str,
        attrs: list[tuple[str, str | None]],
    ) -> None:
        values = dict(attrs)
        if tag == "nav" and "data-nav" in values:
            self.in_primary_nav = True
        if tag == "a" and self.in_primary_nav:
            self.primary_links.append(values)
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input",
                       "link", "meta", "param", "source", "track", "wbr"}:
            self.stack.append(tag)
        if tag == "html":
            self.html_language = values.get("lang")
        if tag == "img":
            self.images.append(values)
        if element_id := values.get("id"):
            if element_id in self.ids:
                self.errors.append(f"duplicate id: {element_id}")
            self.ids.add(element_id)
        if tag == "a" and (href := values.get("href", "")).startswith("#"):
            self.anchor_refs.append(href[1:])
        if tag == "link" and values.get("rel") == "canonical":
            self.canonical = values.get("href")
        if tag == "link" and values.get("rel") == "alternate":
            self.alternates[values.get("hreflang", "")] = values.get("href", "")

        key = "href" if tag in {"a", "link"} else "src" if tag in {"img", "script"} else None
        if key and (resource := values.get(key)):
            self.resources.append(resource)

    def handle_endtag(self, tag: str) -> None:
        if tag == "nav":
            self.in_primary_nav = False
        if not self.stack or self.stack[-1] != tag:
            self.errors.append(f"unexpected closing tag: {tag}")
        else:
            self.stack.pop()

    def handle_data(self, data: str) -> None:
        self.text.append(data.strip())


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
    sitemap_urls = {
        item.text for item in ET.parse(SITE_ROOT / "sitemap.xml").findall(
            ".//{http://www.sitemaps.org/schemas/sitemap/0.9}loc",
        )
    }

    for page in pages:
        parser = PageParser()
        source = page.read_text(encoding="utf-8")
        parser.feed(source)
        relative_page = page.relative_to(SITE_ROOT)
        is_home = page in (SITE_ROOT / "index.html", SITE_ROOT / "zh-CN" / "index.html")
        is_quick_start = relative_page.parts[-3:] == ("wiki", "quick-start", "index.html")
        if is_home or "wiki" in relative_page.parts:
            primary = [link for link in parser.primary_links if "lang" not in link]
            expected_home = SITE_ROOT / ("zh-CN" if "zh-CN" in relative_page.parts else "") / "index.html"
            expected_wiki = expected_home.parent / "wiki" / "index.html"
            if [local_resource(page, link.get("href") or "") for link in primary] != [
                expected_home.resolve(), expected_wiki.resolve(),
            ]:
                errors.append(f"{relative_page}: primary navigation must contain only Home and Wiki")
            if len(parser.primary_links) != 3 or "data-nav-toggle" in source:
                errors.append(f"{relative_page}: keep Home, Wiki and one language switch visible without a menu")
        errors.extend(f"{relative_page}: {error}" for error in parser.errors)
        if parser.stack:
            errors.append(f"{relative_page}: unclosed tags {parser.stack}")

        for anchor in parser.anchor_refs:
            if anchor and anchor not in parser.ids:
                errors.append(f"{relative_page}: missing anchor #{anchor}")

        for resource in parser.resources:
            if resource.startswith("https://github.com/gongpx20069/android-agent-link/blob/main/"):
                errors.append(f"{relative_page}: AgentLink documentation lives on master, not main")
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
            if parser.canonical not in sitemap_urls:
                errors.append(f"{relative_page}: missing from sitemap")
            if "wiki" in relative_page.parts:
                english_route = route.removeprefix("zh-CN/")
                expected_alternates = {
                    "en": f"https://gongpx20069.github.io/hi-mochi/{english_route}",
                    "zh-CN": f"https://gongpx20069.github.io/hi-mochi/zh-CN/{english_route}",
                    "x-default": f"https://gongpx20069.github.io/hi-mochi/{english_route}",
                }
                if parser.alternates != expected_alternates:
                    errors.append(f"{relative_page}: incorrect Wiki language alternates")

        if relative_page.parts[-3:] == ("wiki", "capabilities", "index.html"):
            source = page.read_text(encoding="utf-8")
            for feature in (
                "smart-home", "speech", "documents", "connections", "tasks",
                "browser", "app-control", "skills", "api", "memory",
            ):
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

        if is_home:
            if len(" ".join(parser.text)) > (4500 if language == "zh-CN" else 9000):
                errors.append(f"{relative_page}: homepage is too long; move details into Wiki")
            for guide in ("wiki/", "wiki/quick-start/"):
                if guide not in parser.resources:
                    errors.append(f"{relative_page}: missing Wiki entry {guide}")
            cards = re.search(r'<div class="home-cards">(.*?)</div>', source, re.S)
            card_links = re.findall(r'<a href="([^"]+)"', cards.group(1)) if cards else []
            if len(card_links) != 6 or not all(link.startswith("wiki/") for link in card_links):
                errors.append(f"{relative_page}: every capability card must link to a Wiki guide")
            if {"install", "wiki", "download-options"} & parser.ids:
                errors.append(f"{relative_page}: setup and guide directories belong in Wiki, not homepage sections")
            for extension in ("mijia", "termux", "agentlink", "webdav"):
                if source.count(f'data-extension-card="{extension}"') != 1:
                    errors.append(f"{relative_page}: missing or duplicate {extension} card")
            if "extensions" not in parser.ids:
                errors.append(f"{relative_page}: missing extensions section")

        if is_home or is_quick_start:
            release_root = (
                "https://cnb.cool/hi-mochi/hi-mochi/-/releases/"
                if language == "zh-CN"
                else "https://github.com/gongpx20069/hi-mochi/releases/"
            )
            trusted_root = re.escape(release_root)
            abis = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")
            abi_pattern = "|".join(abis)
            download_versions = re.findall(
                rf'data-release-download(?:="(?:{abi_pattern})")? href="{trusted_root}download/'
                rf'(v1\.0\.[1-9][0-9]*)/Mochi-\1-(?:{abi_pattern})\.apk"', source,
            )
            page_versions = re.findall(
                rf'data-release-page href="{trusted_root}tag/(v1\.0\.[1-9][0-9]*)"',
                source,
            )
            label_versions = re.findall(
                r"data-release-version>(v1\.0\.[1-9][0-9]*)</span>",
                source,
            )
            versions = set(download_versions + page_versions + label_versions)
            for abi in abis if is_quick_start else ():
                abi_versions = re.findall(
                    rf'data-release-download="{abi}" href="{trusted_root}download/'
                    rf'(v1\.0\.[1-9][0-9]*)/Mochi-\1-{abi}\.apk"', source,
                )
                if len(abi_versions) != 1 or set(abi_versions) != versions:
                    errors.append(f"{relative_page}: missing or inconsistent {abi} download")
            for extension, name in (("mijia", "Mijia"), ("termux", "Termux"), ("webdav", "WebDAV")):
                extension_versions = re.findall(
                    rf'data-extension-download="{extension}" href="{trusted_root}'
                    rf'download/(v1\.0\.[1-9][0-9]*)/'
                    rf'Mochi-{name}-Extension-\1\.apk"', source,
                )
                if (is_home and (len(extension_versions) != 1 or set(extension_versions) != versions)) or (
                    is_quick_start and extension_versions
                ):
                    errors.append(f"{relative_page}: inconsistent {extension} download")
            fallback_versions.update(versions)
            if (
                len(download_versions) != (1 if is_home else 6)
                or source.count("data-release-download") != (1 if is_home else 6)
                or len(page_versions) != 1
                or len(label_versions) != (2 if is_home else 1)
                or len(versions) != 1
            ):
                errors.append(
                    f"{relative_page}: inconsistent release fallback metadata",
                )

    if len(fallback_versions) != 1:
        errors.append("Homepages and quick-start guides must share one release fallback in both languages.")

    for locale in ("", "zh-CN/"):
        for guide in ("", "quick-start/", "models/", "speech/", "capabilities/"):
            path = SITE_ROOT / locale / "wiki" / guide / "index.html"
            if not path.is_file():
                errors.append(f"Missing Wiki guide: {locale}wiki/{guide}")
                continue
            source = path.read_text(encoding="utf-8")
            if source.count("<h1>") != 1:
                errors.append(f"{path.relative_to(SITE_ROOT)}: expected one guide heading")
            if guide:
                for layout in ('<main class="shell wiki-frame"', '<aside class="wiki-sidebar"', '<article class="wiki-article"'):
                    if layout not in source:
                        errors.append(f"{path.relative_to(SITE_ROOT)}: missing shared Wiki layout {layout}")
                if re.search(r'class="[^"]*\b(?:feature-card|feature-grid|reference-page|reveal)\b', source):
                    errors.append(f"{path.relative_to(SITE_ROOT)}: guides must not use homepage presentation")
            if guide == "capabilities/":
                for extension in ("mijia", "termux", "agentlink", "webdav"):
                    if not re.search(
                        rf'<details class="wiki-disclosure" id="{extension}-extension"[^>]*data-extension-card="{extension}"',
                        source,
                    ):
                        errors.append(f"{path.relative_to(SITE_ROOT)}: missing {extension} setup disclosure")
            if guide != "quick-start/" and ("data-release-download" in source or "data-extension-download" in source):
                errors.append(f"{path.relative_to(SITE_ROOT)}: unexpected release download outside quick start")
            if guide == "models/":
                providers = re.findall(r'data-provider="([^"]+)"', source)
                expected = {"groq", "modelscope", "openai", "azure", "deepseek", "kimi", "glm", "minimax", "agnes", "custom"}
                if len(providers) != len(expected) or set(providers) != expected:
                    errors.append(f"{locale}wiki/models: incomplete provider instructions")
                for provider, body in re.findall(r'<details[^>]+data-provider="([^"]+)"[^>]*>(.*?)</details>', source, re.S):
                    if body.count("<li>") != 3:
                        errors.append(f"{locale}wiki/models: {provider} needs three setup steps")
            if guide == "speech/":
                providers = re.findall(r'data-speech-provider="([^"]+)"', source)
                if len(providers) != 3 or set(providers) != {"system", "iflytek", "azure"}:
                    errors.append(f"{locale}wiki/speech: incomplete speech provider instructions")

    for route in ("agentlink/index.html", "zh-CN/agentlink/index.html"):
        page = SITE_ROOT / route
        if not page.exists():
            errors.append(f"Missing AgentLink page: {route}")
            continue
        source = page.read_text(encoding="utf-8")
        if "data-release-download" in source:
            errors.append(f"{route}: AgentLink must not download the Mochi APK")
        agents = re.findall(r'data-agent="([^"]+)"', source)
        expected_agents = {"copilot-cli", "claude-code", "kimi-cli", "qwen-code", "deepseek-harness"}
        if len(agents) != 5 or set(agents) != expected_agents:
            errors.append(f"{route}: expected exactly one card for each of the five agents")
        agent_logos = {
            "copilot-cli": "githubcopilot.svg",
            "claude-code": "claude-color.svg",
            "kimi-cli": "kimi-color.svg",
            "qwen-code": "qwen-color.svg",
            "deepseek-harness": "deepseek-color.svg",
        }
        for agent, markup in re.findall(
            r'<article[^>]*data-agent="([^"]+)"[^>]*>(.*?)</article>', source, re.S,
        ):
            card = PageParser()
            card.feed(markup)
            expected_logo = SITE_ROOT / "assets" / "agents" / agent_logos.get(agent, "")
            if len(card.images) != 1:
                errors.append(f"{route}: {agent} must have one brand image")
                continue
            image = card.images[0]
            if local_resource(page, image.get("src") or "") != expected_logo.resolve():
                errors.append(f"{route}: {agent} must use its bundled brand logo")
            if image.get("alt") != "" or image.get("width") != "56" or image.get("height") != "56":
                errors.append(f"{route}: {agent} logo needs decorative alt and explicit dimensions")
        order = [source.find(f'id="{section}"') for section in ("agents", "features", "setup", "connection", "faq")]
        if -1 in order or order != sorted(order):
            errors.append(f"{route}: expected agents, features, setup, connection, FAQ reading order")
        for marker in ("data-image-feature", "0.0.39+", "1 MiB", "assets/agents/NOTICE.txt"):
            if marker not in source:
                errors.append(f"{route}: missing image feature or brand notice: {marker}")
        for section in ("features", "agents", "setup", "server-commands", "connection", "faq", "mochi"):
            if f'id="{section}"' not in source:
                errors.append(f"{route}: missing official-site section {section}")
        if "claude-agent-acp" not in source or "claude --acp" in source:
            errors.append(f"{route}: Claude setup must use the ACP adapter")
        if source.count("<h1>") != 1:
            errors.append(f"{route}: expected one product heading")
        for marker in (
            "https://github.com/gongpx20069/android-agent-link/releases",
            "https://github.com/gongpx20069/android-agent-link/issues",
            r".\.venv\Scripts\python.exe .\bridge\run.py start",
            r".\.venv\Scripts\python.exe .\bridge\run.py start --interactive",
        ):
            if marker not in source:
                errors.append(f"{route}: missing setup/resource content {marker}")
        recovery_note = "不回放旧消息" if route.startswith("zh-CN/") else "no old-message replay"
        update_note = "更新 APK，不等于更新了电脑端。" if route.startswith("zh-CN/") else "Updating the APK does not update the computer."
        for note in (recovery_note, update_note):
            if note not in source:
                errors.append(f"{route}: missing capability boundary: {note}")

    logo_root = SITE_ROOT / "assets" / "agents"
    for filename in ("NOTICE.txt", "LICENSE-lobe-icons.txt"):
        if not (logo_root / filename).is_file():
            errors.append(f"Missing redistributed icon notice: {filename}")
    for logo in logo_root.glob("*.svg"):
        root = ET.fromstring(logo.read_text(encoding="utf-8"))
        if root.tag != "{http://www.w3.org/2000/svg}svg" or not root.get("viewBox"):
            errors.append(f"{logo.name}: expected a scalable SVG image")
        for element in root.iter():
            if element.tag.rsplit("}", 1)[-1] not in {
                "svg", "title", "path", "g", "defs", "linearGradient", "stop",
            }:
                errors.append(f"{logo.name}: unexpected SVG element {element.tag}")
            if any(key.lower().startswith("on") or key.rsplit("}", 1)[-1] == "href"
                   for key in element.attrib):
                errors.append(f"{logo.name}: logo must not execute scripts or load resources")

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

    if errors:
        raise SystemExit("\n".join(errors))

    print(f"Validated {len(pages)} website pages.")


if __name__ == "__main__":
    main()
