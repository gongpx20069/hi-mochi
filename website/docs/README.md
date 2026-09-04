# Website Development

This documentation applies only to the official Mochi website. Product and
Android application documentation remains in the repository-level `docs/`
directory.

## Structure

```text
website/
├── index.html       English homepage
├── zh-CN/           Simplified Chinese homepage
├── assets/          Favicon and social sharing image
├── docs/            Website-only documentation
├── styles.css       Shared responsive styles
├── scripts.js       Shared progressive interactions
├── 404.html         GitHub Pages error page
├── robots.txt
└── sitemap.xml
```

## Local preview

From the repository root:

```powershell
Set-Location website
python -m http.server 8000
```

Open:

- English: `http://127.0.0.1:8000/`
- Simplified Chinese: `http://127.0.0.1:8000/zh-CN/`

Keep internal assets relative so both languages work under the `/hi-mochi/`
GitHub Pages project path. The fixed `/hi-mochi/` paths in `404.html` are
intentional because GitHub Pages serves that document for arbitrary missing
URLs.

English and Simplified Chinese homepages keep the same product feature
structure. The smart-home feature describes the optional signed Mi Home
extension, its supported device categories, and foreground-only latest camera
event images. It must not imply support for camera live view, playback, PTZ,
two-way audio, locks, alarms, body-composition measurements, or sharing Xiaomi
session credentials.

Download controls include a checked-in current-release fallback. On page load,
`scripts.js` reads GitHub's public `releases/latest` API, validates the returned
repository URLs, and updates the displayed version, ARM64 download, and release
page links. If the API is unavailable or rate-limited, the fallback remains
usable.

## Validation

Run the website-owned static checks from the repository root:

```powershell
python website\tests\validate_site.py
```

The deployment workflow runs the same check before constructing the public
artifact. Website documentation, tests, and harness files are deliberately
excluded from that artifact.

## Deployment

`.github/workflows/pages.yml` publishes `website/` when website files change
on `main`, or through a manual workflow dispatch. Set the repository's Pages
source to **GitHub Actions** before the first deployment.
