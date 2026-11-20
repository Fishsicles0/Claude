// Shared script for every page of evadeclient.com. Each block checks that its elements exist.
(() => {
"use strict";
const one = (s, r = document) => r.querySelector(s);
const all = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

// ---------- nav: border once scrolled, mobile menu
const navEl = one("nav.top"), burger = one("#burger");
if (navEl) {
  const onScroll = () => navEl.classList.toggle("scrolled", scrollY > 8);
  onScroll();
  addEventListener("scroll", onScroll, { passive: true });
}
if (burger) {
  const close = () => { navEl.classList.remove("open"); burger.setAttribute("aria-expanded", "false"); burger.setAttribute("aria-label", "Open menu"); };
  burger.addEventListener("click", () => {
    const open = navEl.classList.toggle("open");
    burger.setAttribute("aria-expanded", open);
    burger.setAttribute("aria-label", open ? "Close menu" : "Open menu");
  });
  all("#mobileMenu a").forEach(a => a.addEventListener("click", close));
  addEventListener("keydown", e => { if (e.key === "Escape") close(); });
}

// ---------- reveal on scroll
const reveal = els => {
  if (!("IntersectionObserver" in window)) { els.forEach(el => el.classList.add("in")); return; }
  const io = new IntersectionObserver(es => es.forEach(en => {
    if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); }
  }), { threshold: .08, rootMargin: "0px 0px -40px 0px" });
  els.forEach(el => io.observe(el));
};
reveal(all(".rv"));

// ---------- features page: every mod, read from /assets/mods.json (generated from the client source)
const grid = one("#grid");
if (grid) {
  const q = one("#q"), empty = one("#empty");
  let filter = "all";
  const RISK = { low: "Low risk", med: "Medium risk", high: "High risk" };
  const apply = () => {
    const term = (q.value || "").trim().toLowerCase();
    let shown = 0;
    all(".mod", grid).forEach(m => {
      const ok = (filter === "all" || m.dataset.c === filter) && (!term || m.dataset.s.includes(term));
      m.classList.toggle("hide", !ok);
      if (ok) shown++;
    });
    empty.classList.toggle("show", shown === 0);
  };
  const select = f => {
    filter = f;
    all(".tab").forEach(x => x.classList.toggle("on", x.dataset.f === f));
    apply();
  };
  fetch("/assets/mods.json").then(r => r.json()).then(mods => {
    grid.innerHTML = mods.map(m => {
      const c = m.cat.toLowerCase();
      return `<article class="card mod" data-c="${c}" data-s="${esc((m.name + " " + m.desc + " " + m.cat).toLowerCase())}">
        <div class="mod-top"><span class="cat">${esc(m.cat)}</span><span class="risk ${m.risk}" title="Ban risk">${RISK[m.risk]}</span></div>
        <h3>${esc(m.name)}</h3><p>${esc(m.desc)}</p></article>`;
    }).join("");
    const fromHash = location.hash.slice(1);
    if (all(".tab").some(t => t.dataset.f === fromHash)) select(fromHash); else apply();
  }).catch(() => { grid.innerHTML = "<p>Couldn't load the list of mods. Refresh to try again.</p>"; });
  one("#tabs").addEventListener("click", e => {
    const t = e.target.closest(".tab"); if (!t) return;
    select(t.dataset.f);
    history.replaceState(null, "", t.dataset.f === "all" ? location.pathname : "#" + t.dataset.f);
  });
  q.addEventListener("input", apply);
  addEventListener("keydown", e => {
    if (e.key === "/" && document.activeElement !== q) { e.preventDefault(); q.focus(); }
  });
  grid.addEventListener("pointermove", e => {
    const m = e.target.closest(".mod"); if (!m) return;
    const r = m.getBoundingClientRect();
    m.style.setProperty("--mx", (e.clientX - r.left) + "px");
    m.style.setProperty("--my", (e.clientY - r.top) + "px");
  });
}

// ---------- showcase switcher
const showImg = one("#showImg");
if (showImg) {
  one("#showList").addEventListener("click", e => {
    const b = e.target.closest(".sw-item"); if (!b || b.classList.contains("on")) return;
    all(".sw-item").forEach(x => { x.classList.toggle("on", x === b); x.setAttribute("aria-selected", x === b); });
    showImg.style.opacity = 0;
    const next = new Image();
    next.onload = () => { showImg.src = next.src; showImg.alt = b.querySelector("h4").textContent; showImg.style.opacity = 1; };
    next.src = b.dataset.img;
  });
}

// ---------- lightbox for gallery images
const lb = one("#lightbox");
if (lb) {
  const close = () => { lb.classList.remove("open"); lb.setAttribute("aria-hidden", "true"); };
  all("[data-full]").forEach(el => {
    el.tabIndex = 0;
    const open = () => {
      one("img", lb).src = el.dataset.full;
      one("img", lb).alt = el.dataset.caption || "";
      one("figcaption", lb).textContent = el.dataset.caption || "";
      lb.classList.add("open");
      lb.setAttribute("aria-hidden", "false");
    };
    el.addEventListener("click", open);
    el.addEventListener("keydown", e => { if (e.key === "Enter") open(); });
  });
  lb.addEventListener("click", close);
  addEventListener("keydown", e => { if (e.key === "Escape") close(); });
}

// ---------- copy-to-clipboard buttons (checksums)
all("[data-copy]").forEach(b => b.addEventListener("click", () => {
  const t = one(b.dataset.copy);
  if (!t || !navigator.clipboard) return;
  navigator.clipboard.writeText(t.textContent.trim()).then(() => {
    const old = b.textContent;
    b.classList.add("copied");
    b.textContent = "Copied";
    setTimeout(() => { b.classList.remove("copied"); b.textContent = old; }, 1400);
  });
}));

// ---------- pick the right installer for this OS
const ua = navigator.userAgent;
const plat = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || "";
const isMac = /Mac/i.test(plat) || /Macintosh/.test(ua);
const isLinux = !isMac && /Linux|X11/i.test(plat + ua) && !/Android/i.test(ua);
const isPhone = /iPhone|iPad|iPod|Android/i.test(ua) || (isMac && navigator.maxTouchPoints > 1);
const os = isPhone ? "phone" : isMac ? "mac" : isLinux ? "linux" : "windows";
document.documentElement.dataset.os = os;
const setText = (sel, val) => all(sel).forEach(e => e.textContent = val);
if (os === "mac" || os === "linux") {
  all(".js-primary").forEach(a => a.setAttribute("href", "/downloads/EvadeInstaller.jar"));
  setText(".js-primary-label", os === "mac" ? "Download for macOS" : "Download for Linux");
  setText(".js-primary-meta", "One-click installer · needs Java 17+");
} else if (os === "phone") {
  setText(".js-primary-meta", "Evade runs on a computer · Windows, macOS, Linux");
}
all(".os[data-os]").forEach(c => c.classList.toggle("yours", c.dataset.os === os));

// ---------- download toast
const toast = one("#toast"), toastText = one("#toastText");
let toastTimer;
all("a[download]").forEach(a => a.addEventListener("click", () => {
  const href = a.getAttribute("href");
  toastText.innerHTML = /Texture-Pack/.test(href)
    ? "Pack downloaded. In Minecraft open <b>Options › Resource Packs › Open Pack Folder</b> and drop it in."
    : /evade-client/.test(href)
    ? "Mod downloaded. Put it in your <b>mods</b> folder with Fabric API."
    : "Download started. Open it and click <b>Install</b>.";
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 5000);
}));
})();
