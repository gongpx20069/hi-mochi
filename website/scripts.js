const header = document.querySelector("[data-header]");
const nav = document.querySelector("[data-nav]");
const navToggle = document.querySelector("[data-nav-toggle]");

const updateHeader = () => {
  header?.classList.toggle("scrolled", window.scrollY > 12);
};

const closeNavigation = () => {
  nav?.classList.remove("open");
  navToggle?.setAttribute("aria-expanded", "false");
  document.body.style.removeProperty("overflow");
};

navToggle?.addEventListener("click", () => {
  const isOpen = navToggle.getAttribute("aria-expanded") === "true";
  navToggle.setAttribute("aria-expanded", String(!isOpen));
  nav?.classList.toggle("open", !isOpen);
  document.body.style.overflow = isOpen ? "" : "hidden";
});

nav?.querySelectorAll("a").forEach((link) => {
  link.addEventListener("click", closeNavigation);
});

window.addEventListener("scroll", updateHeader, { passive: true });
updateHeader();

document.querySelectorAll("[data-year]").forEach((element) => {
  element.textContent = new Date().getFullYear();
});

const releaseApi =
  "https://api.github.com/repos/gongpx20069/hi-mochi/releases/latest";
const releasePath = "/gongpx20069/hi-mochi/releases/";

const trustedReleaseUrl = (value, expectedPath) => {
  const url = new URL(value);
  return url.protocol === "https:" &&
    url.hostname === "github.com" &&
    url.pathname === expectedPath
    ? url.toString()
    : null;
};

const updateLatestRelease = async () => {
  const response = await fetch(releaseApi);
  if (!response.ok) {
    throw new Error(`GitHub Releases returned ${response.status}`);
  }

  const release = await response.json();
  const version = release.tag_name;
  if (
    release.draft ||
    release.prerelease ||
    !/^v1\.0\.[1-9][0-9]*$/.test(version)
  ) {
    throw new Error("GitHub returned an unsupported release");
  }

  const assetName = `Mochi-${version}-arm64-v8a.apk`;
  const asset = release.assets?.find((item) => item.name === assetName);
  if (!asset) {
    throw new Error(`GitHub release ${version} has no ARM64 APK`);
  }

  const releasePage = trustedReleaseUrl(
    release.html_url,
    `${releasePath}tag/${version}`,
  );
  const downloadUrl = trustedReleaseUrl(
    asset.browser_download_url,
    `${releasePath}download/${version}/${assetName}`,
  );
  if (!releasePage || !downloadUrl) {
    throw new Error("GitHub returned an unexpected release URL");
  }

  document.querySelectorAll("[data-release-version]").forEach((element) => {
    element.textContent = version;
  });
  document.querySelectorAll("[data-release-page]").forEach((element) => {
    element.href = releasePage;
  });
  document.querySelectorAll("[data-release-download]").forEach((element) => {
    element.href = downloadUrl;
  });
};

updateLatestRelease().catch((error) => {
  console.warn("Using the bundled release fallback.", error);
});

const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
const revealItems = document.querySelectorAll(".reveal");

if (reduceMotion || !("IntersectionObserver" in window)) {
  revealItems.forEach((item) => item.classList.add("visible"));
} else {
  const revealObserver = new IntersectionObserver(
    (entries, observer) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("visible");
        observer.unobserve(entry.target);
      });
    },
    { rootMargin: "0px 0px -8% 0px", threshold: 0.08 },
  );

  revealItems.forEach((item) => revealObserver.observe(item));
}
