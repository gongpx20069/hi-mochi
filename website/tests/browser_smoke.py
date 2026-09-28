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
    options = arguments.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", 0), partial(SiteHandler, directory=str(SITE_ROOT)))
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel=options.channel)
            for language in ("", "zh-CN/"):
                source = (SITE_ROOT / language / "index.html").read_text(encoding="utf-8")
                fallback = re.search(r"data-release-version>(v[^<]+)</span>", source).group(1)
                for mode in ("latest", "unavailable", "untrusted"):
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
                    context.route(API_URL, lambda route: route.fulfill(
                        status=403 if mode == "unavailable" else 200,
                        content_type="application/json",
                        body=json.dumps(payload),
                    ))
                    page = context.new_page()
                    failures = []
                    page.on("pageerror", lambda error: failures.append(str(error)))
                    page.on("response", lambda response: failures.append(response.url)
                            if response.url.startswith("http://127.0.0.1") and response.status >= 400
                            else None)
                    page.goto(f"http://127.0.0.1:{server.server_port}/hi-mochi/{language}")
                    page.wait_for_load_state("networkidle")
                    expected = TEST_VERSION if mode == "latest" else fallback
                    assert page.locator("[data-release-version]").all_text_contents() == [expected] * 3
                    for link in page.locator("[data-release-download]").all():
                        assert link.get_attribute("href") == (
                            f"{RELEASE_ROOT}download/{expected}/Mochi-{expected}-arm64-v8a.apk"
                        )
                    for link in page.locator("[data-release-page]").all():
                        assert link.get_attribute("href") == f"{RELEASE_ROOT}tag/{expected}"

                    for width in (320, 390, 768, 1280):
                        page.set_viewport_size({"width": width, "height": 900})
                        layout_errors = page.evaluate("""() => {
                            const errors = [];
                            if (document.documentElement.scrollWidth > innerWidth) {
                                errors.push("Horizontal overflow");
                            }
                            const cards = document.querySelectorAll(
                                "[data-speech-feature], [data-documents-feature], " +
                                "[data-connections-feature], [data-tasks-feature]"
                            );
                            for (const card of cards) {
                                const bounds = card.getBoundingClientRect();
                                const items = [...card.querySelectorAll(
                                    "h3, p, .knowledge-workspaces, .connection-presets, .waveform"
                                )];
                                for (const [index, item] of items.entries()) {
                                    const box = item.getBoundingClientRect();
                                    if (box.bottom > bounds.bottom || box.right > bounds.right ||
                                        item.scrollWidth > item.clientWidth + 1) {
                                        errors.push(`${card.className}: clipped content`);
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
                            return errors;
                        }""")
                        assert not layout_errors, (language, mode, width, layout_errors)
                        if width < 760:
                            toggle = page.locator("[data-nav-toggle]")
                            toggle.click()
                            assert toggle.get_attribute("aria-expanded") == "true"
                            page.locator("[data-nav] a[href='#features']").click()
                            assert toggle.get_attribute("aria-expanded") == "false"
                            assert page.evaluate("document.body.style.overflow") == ""
                    assert not failures, failures
                    context.close()
            browser.close()
        print("Browser checks passed: both languages, 4 widths, release update/fallback, and navigation.")
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == "__main__":
    main()
