from __future__ import annotations

import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
from threading import Thread

from playwright.sync_api import sync_playwright


SITE_ROOT = Path(__file__).resolve().parents[1]
RELEASE_ROOT = "https://github.com/gongpx20069/hi-mochi/releases/"
CNB_RELEASE_ROOT = "https://cnb.cool/hi-mochi/hi-mochi/-/releases/"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal")
API_URL = "https://api.github.com/repos/gongpx20069/hi-mochi/releases/latest"
TEST_VERSION = "v1.0.99"


class SiteHandler(SimpleHTTPRequestHandler):
    def do_GET(self) -> None:
        if not self.path.startswith("/hi-mochi/"):
            self.send_error(404)
            return
        self.path = self.path.removeprefix("/hi-mochi")
        super().do_GET()

    def log_message(self, format: str, *args: object) -> None:
        pass


def main() -> None:
    arguments = argparse.ArgumentParser()
    arguments.add_argument("--channel", help="Use installed msedge or chrome instead of Chromium")
    arguments.add_argument("--screenshots", type=Path, help="Save AgentLink, Wiki and app-illustration screenshots")
    options = arguments.parse_args()
    if options.screenshots:
        options.screenshots.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(SiteHandler, directory=str(SITE_ROOT)))
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel=options.channel)
            for language in ("", "zh-CN/", "wiki/quick-start/", "zh-CN/wiki/quick-start/", "agentlink/", "zh-CN/agentlink/"):
                is_home = language in ("", "zh-CN/")
                is_download = "agentlink/" not in language
                expects_api = is_download and not language.startswith("zh-CN/")
                release_root = CNB_RELEASE_ROOT if language.startswith("zh-CN/") else RELEASE_ROOT
                source = (SITE_ROOT / language / "index.html").read_text(encoding="utf-8")
                fallback = re.search(r"data-release-version>(v[^<]+)</span>", source).group(1) if is_download else None
                modes = (
                    "latest", "unavailable", "untrusted", "missing-extension",
                    "untrusted-extension", "missing-webdav", "untrusted-webdav",
                    "missing-architecture", "untrusted-architecture",
                ) if expects_api else ("latest", "unavailable") if is_download else ("standalone",)
                for mode in modes:
                    context = browser.new_context(reduced_motion="reduce")
                    asset_name = f"Mochi-{TEST_VERSION}-arm64-v8a.apk"
                    payload = {
                        "tag_name": TEST_VERSION,
                        "draft": False,
                        "prerelease": False,
                        "html_url": f"{RELEASE_ROOT}tag/{TEST_VERSION}",
                        "assets": [{
                            "name": asset_name,
                            "browser_download_url": (
                                "https://example.invalid/untrusted.apk"
                                if mode == "untrusted"
                                else f"{RELEASE_ROOT}download/{TEST_VERSION}/{asset_name}"
                            ),
                        }],
                    }
                    for abi in ABIS[1:]:
                        if mode == "missing-architecture" and abi == "universal":
                            continue
                        filename = f"Mochi-{TEST_VERSION}-{abi}.apk"
                        payload["assets"].append({
                            "name": filename,
                            "browser_download_url": (
                                "https://example.invalid/architecture.apk"
                                if mode == "untrusted-architecture" and abi == "x86"
                                else f"{RELEASE_ROOT}download/{TEST_VERSION}/{filename}"
                            ),
                        })
                    for extension in ("Mijia", "Termux", "WebDAV"):
                        if mode == "missing-extension" and extension == "Termux":
                            continue
                        if mode == "missing-webdav" and extension == "WebDAV":
                            continue
                        filename = f"Mochi-{extension}-Extension-{TEST_VERSION}.apk"
                        payload["assets"].append({
                            "name": filename,
                            "browser_download_url": (
                                "https://example.invalid/extension.apk"
                                if mode == "untrusted-extension" or (mode == "untrusted-webdav" and extension == "WebDAV")
                                else f"{RELEASE_ROOT}download/{TEST_VERSION}/{filename}"
                            ),
                        })
                    context.route(API_URL, lambda route: route.fulfill(
                        status=403 if mode == "unavailable" else 200,
                        content_type="application/json",
                        body=json.dumps(payload),
                    ))
                    page = context.new_page()
                    failures = []
                    page.on("request", lambda request: failures.append("Static download page requested Mochi release API")
                            if not expects_api and request.url == API_URL else None)
                    page.on("pageerror", lambda error: failures.append(str(error)))
                    page.on("response", lambda response: failures.append(response.url)
                            if response.url.startswith("http://127.0.0.1") and response.status >= 400
                            else None)
                    page.goto(f"http://127.0.0.1:{server.server_port}/hi-mochi/{language}")
                    page.wait_for_load_state("networkidle")
                    expected = TEST_VERSION if mode == "latest" and expects_api else fallback
                    assert page.locator("[data-release-version]").all_text_contents() == (
                        [expected] * (2 if is_home else 1) if is_download else []
                    )
                    for link in page.locator("[data-release-download]").all():
                        abi = link.get_attribute("data-release-download") or "arm64-v8a"
                        assert link.get_attribute("href") == (
                            f"{release_root}download/{expected}/Mochi-{expected}-{abi}.apk"
                        )
                    for link in page.locator("[data-release-page]").all():
                        assert link.get_attribute("href") == f"{release_root}tag/{expected}"
                    for extension, name in (("mijia", "Mijia"), ("termux", "Termux"), ("webdav", "WebDAV")):
                        if is_home:
                            assert page.locator(f"[data-extension-download='{extension}']").get_attribute("href") == (
                                f"{release_root}download/{expected}/Mochi-{name}-Extension-{expected}.apk"
                            )
                    if is_download and not is_home:
                        assert page.locator("#download-options [data-release-download]").count() == 5
                        assert page.locator("#install > p > [data-release-download]").is_visible()
                    if is_home:
                        check_app_illustrations(page, ["companion"])
                        assert page.locator("#install, #wiki, #download-options").count() == 0
                        assert page.locator(".home-cards > a").count() == 6
                        preview = page.locator("[data-extension-card='webdav']")
                        assert preview.get_attribute("data-release-status") == "released-preview"
                        assert preview.locator("[data-extension-download='webdav']").count() == 1
                        assert preview.locator(".text-link").get_attribute("href") == "wiki/capabilities/#webdav-extension"
                        for disclosure in page.locator(
                            "[data-connections-feature] details, [data-app-control-feature] details, [data-memory-feature] details, #webdav-extension details, #install details"
                        ).all():
                            summary = disclosure.locator("summary")
                            summary.focus()
                            summary.press("Enter")
                            assert disclosure.evaluate("(item) => item.open")
                            summary.press("Enter")
                            assert not disclosure.evaluate("(item) => item.open")
                    if not is_download:
                        assert page.locator("[data-agent]").count() == 5
                        assert page.locator("[data-image-feature]").count() == 1
                        assert page.locator("[data-agent] img").evaluate_all("""images =>
                            images.length === 5 && images.every(image =>
                                image.complete && image.naturalWidth > 0 &&
                                image.getAttribute("alt") === "" &&
                                image.src.startsWith(location.origin + "/hi-mochi/assets/agents/"))
                        """)
                        assert page.locator("main > section[id]").evaluate_all(
                            "sections => sections.map(section => section.id)"
                        ) == ["agents", "features", "setup", "connection", "faq"]
                        summary = page.locator("#server-commands summary")
                        summary.focus()
                        page.keyboard.press("Enter")
                        assert page.locator("#server-commands").evaluate("(item) => item.open")
                        assert "bridge\\run.py start" in page.locator("#server-commands pre").inner_text()
                        summary.press("Enter")
                        assert not page.locator("#server-commands").evaluate("(item) => item.open")
                        page.locator("[data-web-feature] a").click()
                        assert page.url.endswith("#web-workspace")
                        web_summary = page.locator("#web-workspace summary")
                        web_summary.focus()
                        web_summary.press("Enter")
                        assert page.locator("#web-workspace").evaluate("(item) => item.open")
                        assert "start --no-web" in page.locator("#web-workspace").inner_text()
                        assert "http://127.0.0.1:4318" in page.locator("#web-workspace").inner_text()
                    page.locator("details").evaluate_all("(items) => items.forEach(item => item.open = true)")

                    for width in (320, 390, 768, 1024, 1280, 1440):
                        page.set_viewport_size({"width": width, "height": 900})
                        if is_download and not is_home:
                            for link in page.locator("#download-options a").all():
                                assert link.is_visible()
                                assert link.evaluate("""item => {
                                    const box = item.getBoundingClientRect();
                                    return box.left >= 0 && box.right <= innerWidth &&
                                        item.scrollWidth <= item.clientWidth + 1;
                                }""")
                        layout_errors = page.evaluate("""() => {
                            const errors = [];
                            if (document.documentElement.scrollWidth > innerWidth) {
                                const overflow = [...document.querySelectorAll("main *")].filter(item => {
                                    const box = item.getBoundingClientRect();
                                    return box.right > innerWidth + 1 || box.left < -1;
                                }).slice(0, 8).map(item =>
                                    `${item.tagName}.${item.className} in ${item.parentElement.className}: ${item.textContent.trim().slice(0, 70)}`
                                );
                                errors.push(`Horizontal overflow: ${overflow.join(", ")}`);
                            }
                            const cards = document.querySelectorAll(
                                "[data-speech-feature], [data-documents-feature], [data-memory-feature], " +
                                "[data-connections-feature], [data-tasks-feature], " +
                                "[data-browser-feature], [data-app-control-feature], [data-skills-feature], [data-api-feature], " +
                                "[data-extension-card], .link-benefits article, .link-stage, " +
                                ".al-agent-card, .al-feature-grid article, .al-setup-grid li, .home-cards > a"
                            );
                            for (const card of cards) {
                                const bounds = card.getBoundingClientRect();
                                const items = [...card.querySelectorAll(
                                    "h3, p, dt, dd, .knowledge-workspaces, .connection-presets, .waveform, .execution-preview"
                                )];
                                for (const [index, item] of items.entries()) {
                                    const box = item.getBoundingClientRect();
                                    if (box.bottom > bounds.bottom || box.right > bounds.right ||
                                        item.scrollWidth > item.clientWidth + 1) {
                                        errors.push(`${card.id || card.className}: clipped ${item.tagName} ` +
                                            `"${item.textContent.trim().slice(0, 100)}" ` +
                                            `(width ${item.scrollWidth}/${item.clientWidth})`);
                                    }
                                    for (const other of items.slice(index + 1)) {
                                        const next = other.getBoundingClientRect();
                                        if (box.left < next.right && box.right > next.left &&
                                            box.top < next.bottom && box.bottom > next.top) {
                                            errors.push(`${card.className}: overlapping content`);
                                        }
                                    }
                                }
                            }
                            const nav = document.querySelector("[data-nav]");
                            if (document.body.classList.contains("agentlink-page") &&
                                innerWidth > 760 && getComputedStyle(nav).visibility === "visible") {
                                const brand = document.querySelector(".nav-shell .brand").getBoundingClientRect();
                                const box = nav.getBoundingClientRect();
                                if (box.left < brand.right || box.right > innerWidth) {
                                    errors.push("Desktop navigation overlaps brand or viewport");
                                }
                            }
                            return errors;
                        }""")
                        assert not layout_errors, (language, mode, width, layout_errors)
                        if is_home:
                            check_illustration_layout(page, (language, mode, width))
                            if options.screenshots and mode == "unavailable" and width in (390, 1280):
                                label = "zh-CN-home" if language else "home"
                                capture_illustrations(page, options.screenshots, label, width)
                                page.locator(".hero").screenshot(
                                    path=str(options.screenshots / f"{label}-hero-{width}.png"),
                                )
                        if is_download:
                            check_mochi_navigation(page, language.startswith("zh-CN/"))
                        if width < 760 and not is_download:
                            toggle = page.locator("[data-nav-toggle]")
                            toggle.click()
                            assert toggle.get_attribute("aria-expanded") == "true"
                            page.locator("[data-nav] a[href='#features']").click()
                            assert toggle.get_attribute("aria-expanded") == "false"
                            assert page.evaluate("document.body.style.overflow") == ""
                        if not is_download:
                            for agent in page.locator("[data-agent]").all():
                                assert agent.locator("a").get_attribute("href").startswith(
                                    "https://github.com/gongpx20069/android-agent-link/blob/master/"
                                )
                                assert agent.locator("img").is_visible()
                                assert agent.locator("img").evaluate("""image => {
                                    const box = image.getBoundingClientRect();
                                    return box.width === 56 && box.height === 56;
                                }""")
                            if options.screenshots:
                                prefix = language.strip("/").replace("/", "-")
                                page.evaluate("document.activeElement.blur(); scrollTo(0, 0)")
                                page.screenshot(path=str(options.screenshots / f"{prefix}-{width}.png"))
                                for section in ("agents", "features"):
                                    page.locator(f"#{section}").screenshot(
                                        path=str(options.screenshots / f"{prefix}-{section}-{width}.png"),
                                        style=".site-header, .skip-link { visibility: hidden !important; }",
                                    )
                    if is_home:
                        page.locator("[data-extension-card='agentlink'] .button").click()
                        assert page.url.endswith(f"/hi-mochi/{language}agentlink/")
                        page.wait_for_load_state("networkidle")
                        page.locator(".site-nav a[href='../']").click()
                        assert page.url.endswith(f"/hi-mochi/{language}")
                    elif not is_download:
                        assert page.locator(".hero-actions a").first.get_attribute("href") == (
                            "https://github.com/gongpx20069/android-agent-link/releases"
                        )
                        language_link = "../zh-CN/agentlink/" if language == "agentlink/" else "../../agentlink/"
                        page.locator(f".site-nav a[href='{language_link}']").click()
                        assert page.url.endswith("/zh-CN/agentlink/" if language == "agentlink/" else "/hi-mochi/agentlink/")
                    else:
                        filename = f"Mochi-{expected}-arm64-v8a.apk"
                        page.route("**/*.apk", lambda route: route.fulfill(
                            content_type="application/vnd.android.package-archive",
                            headers={"Content-Disposition": f'attachment; filename="{filename}"'},
                            body="download fixture",
                        ))
                        before_download = page.url
                        with page.expect_download() as download:
                            page.locator("#install > p > [data-release-download]").click()
                        assert download.value.suggested_filename == filename
                        assert page.url == before_download
                    page.wait_for_load_state("networkidle")
                    assert not failures, failures
                    context.close()
            for language in ("", "zh-CN/", "wiki/quick-start/", "zh-CN/wiki/quick-start/", "agentlink/", "zh-CN/agentlink/"):
                context = browser.new_context(java_script_enabled=False)
                page = context.new_page()
                page.goto(f"http://127.0.0.1:{server.server_port}/hi-mochi/{language}")
                page.wait_for_load_state("networkidle")
                if "agentlink/" not in language:
                    source = (SITE_ROOT / language / "index.html").read_text(encoding="utf-8")
                    fallback = re.search(r"data-release-version>(v[^<]+)</span>", source).group(1)
                    release_root = CNB_RELEASE_ROOT if language.startswith("zh-CN/") else RELEASE_ROOT
                    check_mochi_navigation(page, language.startswith("zh-CN/"))
                    for link in page.locator("[data-release-download]").all():
                        abi = link.get_attribute("data-release-download") or "arm64-v8a"
                        assert link.get_attribute("href") == (
                            f"{release_root}download/{fallback}/Mochi-{fallback}-{abi}.apk"
                        )
                    for disclosure in page.locator(
                        "[data-connections-feature] details, [data-app-control-feature] details, [data-memory-feature] details, #webdav-extension details, #install details"
                    ).all():
                        disclosure.locator("summary").click()
                        assert disclosure.evaluate("(item) => item.open")
                        assert disclosure.locator("p, ol").first.is_visible()
                    if "wiki/" not in language:
                        check_app_illustrations(page, ["companion"])
                        for width in (320, 390, 768, 1024, 1280, 1440):
                            page.set_viewport_size({"width": width, "height": 900})
                            check_illustration_layout(page, (language, "no JavaScript", width))
                        assert page.locator("[data-extension-download='webdav']").get_attribute("href") == (
                            f"{release_root}download/{fallback}/Mochi-WebDAV-Extension-{fallback}.apk"
                        )
                    else:
                        assert page.locator("#download-options [data-release-download]").count() == 5
                    context.close()
                    continue
                assert page.locator(".agentlink-hero .reveal").first.evaluate(
                    "(item) => getComputedStyle(item).opacity"
                ) == "1"
                page.locator("#server-commands summary").click()
                assert page.locator("#server-commands pre").is_visible()
                page.locator("#web-workspace summary").click()
                assert page.locator("#web-workspace .al-disclosure-body").is_visible()
                assert page.locator("[data-agent]").count() == 5
                assert page.locator("[data-agent] img").evaluate_all(
                    "images => images.every(image => image.complete && image.naturalWidth > 0)"
                )
                context.close()
            check_wiki(browser, server.server_port, options.screenshots)
            browser.close()
        print("Browser checks passed: 14 content pages, 6 widths, localized downloads, release fallbacks, Wiki navigation and no-JavaScript guides.")
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def check_mochi_navigation(page, chinese: bool) -> None:
    assert page.locator("[data-nav] a:not([lang])").all_text_contents() == (
        ["首页", "Wiki"] if chinese else ["Home", "Wiki"]
    )
    assert page.locator("[data-nav-toggle]").count() == 0
    for link in page.locator("[data-nav] a").all():
        assert link.is_visible()
        assert link.evaluate("""item => {
            const box = item.getBoundingClientRect();
            return box.left >= 0 && box.right <= innerWidth;
        }""")
    assert page.locator("[data-nav]").evaluate("""item =>
        item.getBoundingClientRect().left >= document.querySelector('.brand').getBoundingClientRect().right
    """)
    star = page.locator(".site-header > .nav-shell > .github-star")
    assert star.count() == 1
    assert star.is_visible()
    assert star.get_attribute("href") == "https://github.com/gongpx20069/hi-mochi"
    assert star.get_attribute("target") == "_blank"
    assert {"noopener", "noreferrer"} <= set(star.get_attribute("rel").split())
    assert ("给 Mochi 点 Star" if chinese else "Star Mochi on GitHub") in star.get_attribute("aria-label")
    assert star.locator("img").evaluate("image => image.complete && image.naturalWidth > 0")
    assert star.evaluate("""item => {
        const box = item.getBoundingClientRect();
        const nav = document.querySelector('[data-nav]').getBoundingClientRect();
        return box.left >= nav.right && box.right <= innerWidth &&
            box.top >= nav.top - 8 && box.bottom <= nav.bottom + 8;
    }""")
    assert star.locator("span").is_visible() == (page.viewport_size["width"] > 480)
    page.locator("[data-nav] a[lang]").focus()
    page.keyboard.press("Tab")
    assert star.evaluate("item => document.activeElement === item")


def check_app_illustrations(page, expected: list[str]) -> None:
    chinese = page.locator("html").get_attribute("lang") == "zh-CN"
    figures = page.locator("[data-app-illustration]")
    assert figures.evaluate_all(
        "figures => figures.map(figure => figure.dataset.appIllustration)"
    ) == expected
    for figure in figures.all():
        panel = figure.locator(".app-shot[role='img']")
        assert panel.count() == 1 and panel.is_visible()
        assert panel.get_attribute("aria-label")
        assert panel.locator(":scope > [aria-hidden='true']").count() == 1
        assert panel.locator(
            "a, button, input, select, textarea, [tabindex], [contenteditable], [role='button']"
        ).count() == 0, "Illustrations must not accept input or act as app controls"
        assert panel.locator("img").evaluate_all(
            "images => images.every(image => image.complete && image.naturalWidth > 0)"
        )
        caption = figure.locator("figcaption")
        is_companion = figure.get_attribute("data-app-illustration") == "companion"
        assert panel.locator(".app-shot-pin").all_text_contents() == (
            [] if is_companion else ["1", "2", "3"]
        )
        assert caption.locator("ol > li").count() == (0 if is_companion else 3)
        assert figure.get_attribute("data-app-version") in caption.inner_text()
        assert ("非真实截图" if chinese else "not a screenshot") in caption.inner_text()


def check_illustration_layout(page, context: tuple) -> None:
    for figure in page.locator("[data-app-illustration]").all():
        # Nested grids can finish reflowing after the outer page fits a resized viewport.
        for _ in range(20):
            fits = figure.locator(".app-shot, .app-shot *").evaluate_all("""items =>
                items.every(item => {
                    const box = item.getBoundingClientRect();
                    return box.left >= 0 && box.right <= innerWidth + 1 &&
                        (getComputedStyle(item).display === 'inline' ||
                         item.scrollWidth <= item.clientWidth + 1);
                })
            """)
            if fits:
                break
            page.wait_for_timeout(50)
        assert fits, (*context, "clipped illustration")


def capture_illustrations(page, directory: Path, label: str, width: int) -> None:
    for figure in page.locator("[data-app-illustration]").all():
        kind = figure.get_attribute("data-app-illustration")
        figure.screenshot(
            path=str(directory / f"{label}-{kind}-{width}.png"),
            style=".site-header, .skip-link { visibility: hidden !important; }",
        )
        for link in page.locator("[data-nav] a").all():
            link.wait_for(state="visible")


def check_wiki(browser, port: int, screenshots: Path | None) -> None:
    base = f"http://127.0.0.1:{port}/hi-mochi/"
    for locale in ("", "zh-CN/"):
        for guide in ("", "quick-start/", "models/", "speech/", "capabilities/"):
            for javascript in (True, False):
                context = browser.new_context(java_script_enabled=javascript, reduced_motion="reduce")
                failures = []
                context.route(API_URL, lambda route: route.abort())
                page = context.new_page()
                page.on("pageerror", lambda error: failures.append(str(error)))
                page.on("request", lambda request: failures.append("Wiki requested release API")
                        if request.url == API_URL and not (guide == "quick-start/" and not locale) else None)
                page.on("response", lambda response: failures.append(response.url)
                        if response.url.startswith(base) and response.status >= 400 else None)
                route = f"{locale}wiki/{guide}"
                page.goto(base + route)
                assert page.locator("h1").count() == 1
                if guide:
                    assert page.locator("main.wiki-frame > .wiki-sidebar").count() == 1
                    assert page.locator("main.wiki-frame > .wiki-article").count() == 1
                    assert page.locator(".wiki-sidebar [aria-current='page']").get_attribute("href") == "./"
                if screenshots and javascript and guide == "capabilities/":
                    for width in (390, 1280):
                        page.set_viewport_size({"width": width, "height": 900})
                        label = (locale + "wiki-capabilities").replace("/", "-")
                        page.screenshot(path=str(screenshots / f"{label}-overview-{width}.png"), full_page=True)
                if guide == "capabilities/":
                    figures = page.locator("[data-app-illustration]")
                    assert figures.count() == 3
                    assert all(not figure.is_visible() for figure in figures.all())
                    assert page.locator("details > [data-app-illustration]").count() == 3
                if guide == "models/":
                    preferred = "modelscope" if locale else "groq"
                    assert page.locator(".wiki-article > .wiki-section").first.get_attribute("id") == preferred
                    steps = page.locator(f"section#{preferred} > ol > li")
                    assert steps.count() == 3
                    assert all(step.is_visible() for step in steps.all())
                    assert page.locator("[data-provider]").count() == 10
                    assert page.locator("details[data-provider][open]").count() == 0
                if guide == "quick-start/":
                    speech = page.locator("section#speech")
                    assert speech.is_visible()
                    assert ("可选" if locale else "optional") in speech.locator("h2").inner_text()
                    assert speech.locator('a[href="../speech/#iflytek"]').is_visible()
                    assert speech.locator('a[href="../speech/#voices"]').is_visible()
                    assert page.locator('.wiki-tabs a[href="#speech"]').is_visible()
                for disclosure in page.locator("details").all():
                    summary = disclosure.locator("summary")
                    if disclosure.evaluate("item => item.open"):
                        summary.focus()
                        summary.press("Enter")
                    summary.focus()
                    summary.press("Enter")
                    assert disclosure.evaluate("item => item.open")
                    assert disclosure.locator("p, ol, ul").first.is_visible()
                if guide == "models/" or (guide == "quick-start/" and locale):
                    illustration = page.locator("[data-modelscope-binding] img")
                    assert illustration.count() == 1 and illustration.is_visible()
                    assert illustration.get_attribute("alt")
                    assert illustration.evaluate("image => image.complete && image.naturalWidth > 0")
                expected_illustrations = {
                    "quick-start/": ["chat"], "models/": ["model"],
                    "speech/": ["speech", "voice"],
                    "capabilities/": ["tasks", "browser", "skills"],
                }.get(guide, [])
                check_app_illustrations(page, expected_illustrations)
                for width in (320, 390, 768, 1024, 1280, 1440):
                    page.set_viewport_size({"width": width, "height": 900})
                    # Poll from Python: page animation/timer callbacks do not run with JavaScript disabled.
                    for _ in range(20):
                        fits_viewport = page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                        fits_text = page.locator("main").evaluate("""main =>
                            [...main.querySelectorAll('p, h1, h2, h3, li, code, summary')].every(item =>
                                item.scrollWidth <= item.clientWidth + 1 || getComputedStyle(item).display === 'inline')
                        """)
                        if fits_viewport and fits_text:
                            break
                        page.wait_for_timeout(50)
                    assert fits_viewport, (route, javascript, width)
                    assert fits_text, (route, javascript, width, "clipped text")
                    check_illustration_layout(page, (route, javascript, width))
                    if screenshots and javascript and width in (390, 1280):
                        label = (locale + "wiki-" + guide).strip("/").replace("/", "-")
                        capture_illustrations(page, screenshots, label, width)
                    check_mochi_navigation(page, bool(locale))
                    if guide:
                        assert page.locator(".wiki-sidebar").evaluate("""(sidebar) => {
                            const side = sidebar.getBoundingClientRect();
                            const article = document.querySelector('.wiki-article').getBoundingClientRect();
                            return innerWidth > 1024 ? side.right < article.left : side.bottom <= article.top;
                        }"""), (route, width, "Wiki sidebar layout")
                    if screenshots and javascript and guide in ("", "models/") and width in (390, 1280):
                        page.evaluate("scrollTo(0, 0)")
                        label = (locale + "wiki-" + (guide or "overview")).strip("/").replace("/", "-")
                        page.screenshot(path=str(screenshots / f"{label}-{width}.png"))
                if javascript and guide == "models/":
                    page.goto(base + route + "#azure")
                    assert page.locator("#azure").evaluate("item => item.open")
                if javascript and not guide:
                    page.locator("#llm-and-agent").evaluate("item => item.open = false")
                    page.locator('.wiki-link-list a[href="#llm-and-agent"]').click()
                    page.wait_for_function("document.getElementById('llm-and-agent').open")
                    assert page.locator("#llm-and-agent p").first.is_visible()
                if javascript and guide == "capabilities/":
                    for anchor in ("planner", "browser", "memory", "documents", "app-control",
                                   "mijia-extension", "termux-extension", "agentlink-extension", "webdav-extension"):
                        page.locator("details").evaluate_all("items => items.forEach(item => item.open = false)")
                        page.goto(base + route + "#" + anchor)
                        target = page.locator(f"#{anchor}")
                        assert target.evaluate("item => item.open"), (route, anchor)
                        assert target.locator("p").first.is_visible()
                other = f"{'' if locale else 'zh-CN/'}wiki/{guide}"
                language_link = page.locator("[data-nav] a").filter(has_text="EN" if locale else "中文")
                language_link.click()
                assert page.url == base + other
                assert not failures, (route, failures)
                context.close()


if __name__ == "__main__":
    main()
