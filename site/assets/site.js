// Shared script for every page of evadeclient.com. Each block checks that its elements exist.
(() => {
"use strict";
const one = (s, r = document) => r.querySelector(s);
const all = (s, r = document) => Array.from(r.querySelectorAll(s));
const esc = s => String(s).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

// ---------- reveal on scroll (first, so a failure further down can't leave the page hidden)
const reveal = els => {
  if (!("IntersectionObserver" in window)) { els.forEach(el => el.classList.add("in")); return; }
  const io = new IntersectionObserver(es => es.forEach(en => {
    if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); }
  }), { threshold: .08, rootMargin: "0px 0px -40px 0px" });
  els.forEach(el => io.observe(el));
};
reveal(all(".rv"));
// Show an element and everything around it that is still waiting to fade in.
const revealUp = el => { for (let e = el; e; e = e.parentElement) if (e.classList && e.classList.contains("rv")) e.classList.add("in"); };

// ---------- toast (download started, link copied...)
const toast = one("#toast"), toastText = one("#toastText");
let toastTimer;
const showToast = htmlText => {
  if (!toast) return;
  toastText.innerHTML = htmlText;
  toast.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("show"), 5000);
};

// ---------- nav: border once scrolled, mobile menu
const navEl = one("nav.top"), burger = one("#burger");
if (navEl) {
  const onScroll = () => navEl.classList.toggle("scrolled", scrollY > 8);
  onScroll();
  addEventListener("scroll", onScroll, { passive: true });
}
if (burger) {
  const close = () => { navEl.classList.remove("open"); document.documentElement.classList.remove("menu-open"); burger.setAttribute("aria-expanded", "false"); burger.setAttribute("aria-label", "Open menu"); };
  burger.addEventListener("click", () => {
    const open = navEl.classList.toggle("open");
    document.documentElement.classList.toggle("menu-open", open);
    burger.setAttribute("aria-expanded", open);
    burger.setAttribute("aria-label", open ? "Close menu" : "Open menu");
  });
  all("#mobileMenu a").forEach(a => a.addEventListener("click", close));
  addEventListener("keydown", e => { if (e.key === "Escape") close(); });
  matchMedia("(min-width: 1121px)").addEventListener("change", e => { if (e.matches) close(); });
}

// ---------- deep links to a FAQ answer (/faq#update) open it, also when the hash changes on the same page
const openHash = () => {
  let id = "";
  try { id = decodeURIComponent(location.hash.slice(1)); } catch (e) { return; }
  const d = id && document.getElementById(id);
  if (!d || d.tagName !== "DETAILS") return;
  all("details.hl").forEach(x => x.classList.remove("hl"));
  d.open = true;
  d.classList.add("hl");
  revealUp(d);
  requestAnimationFrame(() => d.scrollIntoView({ block: "start" }));
};
openHash();
addEventListener("hashchange", openHash);

// ---------- features page: every mod, read from /assets/mods.json (generated from the client source)
const grid = one("#grid");
if (grid) {
  const q = one("#q"), empty = one("#empty"), more = one("#more");
  let filter = "all";
  // "better tooltips", "BetterTooltips" and "better-tooltips" all find BetterTooltips.
  const flat = s => s.toLowerCase().replace(/[\s_.\-]+/g, "");
  const inTab = m => filter === "all" || m.dataset.c === filter || (filter === "new" && m.dataset.new === "1");
  const apply = () => {
    const term = (q.value || "").trim().toLowerCase(), ft = flat(term);
    let shown = 0, elsewhere = 0;
    all(".mod", grid).forEach(m => {
      const hit = !term || m.dataset.s.includes(term) || (ft && flat(m.dataset.s).includes(ft));
      const ok = hit && inTab(m);
      m.classList.toggle("hide", !ok);
      if (ok) shown++;
      else if (hit && term) elsewhere++;
    });
    empty.classList.toggle("show", shown === 0);
    if (more) {
      more.hidden = !elsewhere;
      if (elsewhere) more.innerHTML = `<span>${elsewhere} more ${elsewhere === 1 ? "match" : "matches"} in other tabs.</span><button type="button" id="moreAll">Search all mods</button>`;
    }
  };
  const select = f => {
    filter = f;
    all(".tab").forEach(x => { const on = x.dataset.f === f; x.classList.toggle("on", on); x.setAttribute("aria-pressed", on); });
    apply();
  };
  if (more) more.addEventListener("click", e => {
    if (!e.target.closest("#moreAll")) return;
    select("all");
    history.replaceState(null, "", location.pathname);
    q.focus();
  });
  // A representative screenshot for each category, used on a module's detail hero.
  const CAT_IMG = { server: "/assets/menu-settings.webp", visuals: "/assets/hud.webp", hud: "/assets/hud-editor.webp",
    mods: "/assets/zoom.webp", misc: "/assets/menu.webp" };
  const TYPE = { slider: "Number", toggle: "Toggle", mode: "Mode", color: "Colour", key: "Key", text: "Text" };
  const RANGE = st => st.type === "slider" ? `${esc(st.min)} to ${esc(st.max)}`
    : st.type === "mode" ? (st.options.length ? st.options.map(esc).join(", ") : "&mdash;")
    : st.type === "toggle" ? "On or off" : st.type === "color" ? "Any colour" : st.type === "key" ? "Any key"
    : st.type === "text" ? "Any text" + (st.hint ? `, or <span class="mono">${esc(st.hint)}</span> in chat` : "") : "&mdash;";
  let MODS = [];
  const detail = one("#detail"), listView = one("#listView");

  const openDetail = (name, push) => {
    const m = MODS.find(x => x.name === name);
    if (!m) return;
    const c = m.slug;
    const rows = m.settings.map(st => `<tr><th scope="row">${esc(st.name)}${st.desc ? `<small>${esc(st.desc)}</small>` : ""}</th><td>${TYPE[st.type] || esc(st.type)}</td>`
      + `<td class="def">${st.type === "color" ? `<i class="sw" style="background:${esc(st.default)}"></i>` : ""}${esc(st.default)}</td>`
      + `<td>${RANGE(st)}</td></tr>`).join("");
    detail.innerHTML = `
      <button class="detail-back" id="detailBack"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg> All ${MODS.length} mods</button>
      <div class="detail-hero">
        <img src="${CAT_IMG[c] || "/assets/menu.webp"}" alt="" loading="lazy">
        <div class="detail-hero-in">
          <div class="mod-top"><span class="cat">${esc(m.cat)}${m.new ? ' <em class="new">New</em>' : ""}</span><span class="risk low">Server-safe</span></div>
          <h1>${esc(m.name)}</h1>
          <p>${esc(m.desc)}</p>
        </div>
      </div>
      <div class="detail-body">
        <section class="card detail-sec">
          <h2>What does ${esc(m.name)} do?</h2>
          <p>${esc(m.desc)} It's in the <b>${esc(m.cat)}</b> category, and it's server-safe &mdash; quality of life, not a cheat.</p>
        </section>
        <section class="card detail-sec">
          <h2>How do I turn ${esc(m.name)} on?</h2>
          <ol class="steps-num">
            <li><b>Install Evade</b> with the <a href="/download">one-click installer</a>, then start Minecraft on the Evade Client profile.</li>
            <li><b>Open the menu</b> with <span class="kbd">Right Shift</span> in game.</li>
            <li><b>Find ${esc(m.name)}</b> under <b>${esc(m.cat)}</b> (or type to search), click the card to turn it on, and open the gear for its settings &mdash; or type <span class="mono">.toggle ${esc(m.name.replace(/ /g, ""))}</span> in chat.</li>
          </ol>
        </section>
        ${m.settings.length ? `<section class="card detail-sec">
          <h2>What can you change in ${esc(m.name)}?</h2>
          <p>${esc(m.name)} has ${m.settings.length} setting${m.settings.length > 1 ? "s" : ""}. Each is adjustable in the menu; the defaults below are what a fresh install uses.</p>
          <div class="settable-wrap"><table class="settable">
            <thead><tr><th>Setting</th><th>Type</th><th>Default</th><th>Range or options</th></tr></thead>
            <tbody>${rows}</tbody>
          </table></div>
        </section>` : `<section class="card detail-sec"><h2>Settings</h2><p>${esc(m.name)} has no settings &mdash; just switch it on.</p></section>`}
      </div>`;
    listView.hidden = true;
    detail.hidden = false;
    document.title = m.name + " · Evade Client";
    scrollTo(0, 0);
    one("#detailBack").addEventListener("click", () => history.back());
    if (push) history.pushState({ m: name }, "", "#m=" + encodeURIComponent(name));
  };

  const closeDetail = () => { detail.hidden = true; listView.hidden = false; document.title = "Features · Evade Client"; };

  const route = push => {
    const h = decodeURIComponent(location.hash.slice(1));
    if (h.startsWith("m=")) openDetail(h.slice(2), false);
    else { closeDetail(); if (all(".tab").some(t => t.dataset.f === h)) select(h); else apply(); }
  };

  fetch(grid.dataset.src || "/assets/mods.json").then(r => r.json()).then(mods => {
    MODS = mods;
    // The cards are normally already in the page (built from the client source); build them if not.
    if (!one(".mod", grid)) grid.innerHTML = mods.map(m => {
      const n = m.settings.length;
      return `<article class="card mod" data-m="${esc(m.name)}" data-c="${m.slug}" data-new="${m.new ? 1 : 0}" data-s="${esc((m.name + " " + m.desc + " " + m.cat + " " + m.settings.map(x => x.name).join(" ")).toLowerCase())}">
        <div class="mod-top"><span class="cat">${esc(m.cat)}${m.new ? ' <em class="new">New</em>' : ""}</span></div>
        <h3>${esc(m.name)}</h3><p>${esc(m.desc)}</p>
        <span class="mod-foot">${n ? n + " setting" + (n > 1 ? "s" : "") : "No settings"} <em>View &rarr;</em></span></article>`;
    }).join("");
    // Make the cards keyboard-friendly.
    all(".mod", grid).forEach(card => { card.tabIndex = 0; card.setAttribute("role", "button"); });
    route(false);
  }).catch(() => {});

  grid.addEventListener("click", e => {
    if (e.target.closest(".mod-sets")) return;
    const card = e.target.closest(".mod"); if (!card || !MODS.length) return;
    openDetail(card.dataset.m, true);
  });
  grid.addEventListener("keydown", e => {
    const card = e.target.closest(".mod");
    if (card && (e.key === "Enter" || e.key === " ") && MODS.length) { e.preventDefault(); openDetail(card.dataset.m, true); }
  });
  addEventListener("popstate", () => route(false));
  all(".tab").forEach(t => t.setAttribute("aria-pressed", t.classList.contains("on")));
  one("#tabs").addEventListener("click", e => {
    const t = e.target.closest(".tab"); if (!t) return;
    select(t.dataset.f);
    history.replaceState(null, "", t.dataset.f === "all" ? location.pathname : "#" + t.dataset.f);
  });
  q.addEventListener("input", apply);
  addEventListener("keydown", e => {
    if (e.key === "/" && document.activeElement !== q && detail.hidden) { e.preventDefault(); q.focus(); }
    if (e.key === "Escape" && !detail.hidden) history.back();
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
    // Stacked on a phone the picture sits above the list (and sticks); bring it back if it's off screen.
    const r = showImg.getBoundingClientRect(), top = (navEl ? navEl.offsetHeight : 0);
    if (r.bottom < top + 40 || r.top > innerHeight - 40) showImg.closest(".sw-view").scrollIntoView({ block: "nearest", behavior: "smooth" });
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
  const jar = document.querySelector('a[href^="/downloads/EvadeInstaller.jar"]');
  all(".js-primary").forEach(a => a.setAttribute("href", jar ? jar.getAttribute("href") : "/downloads/EvadeInstaller.jar?v=" + Date.now()));
  setText(".js-primary-label", os === "mac" ? "Download for macOS" : "Download for Linux");
  setText(".js-primary-meta", "One-click installer · needs Java 17+");
} else if (os === "phone") {
  setText(".js-primary-meta", "Evade runs on a computer: Windows, macOS or Linux");
  all(".js-meta-more").forEach(e => e.remove());
  const url = "https://evadeclient.com/download";
  const send = e => {
    if (navigator.share) {
      e.preventDefault();
      navigator.share({ title: "Evade Client", text: "Download Evade Client on your computer", url }).catch(() => {});
    } else if (navigator.clipboard && (location.pathname.startsWith("/download") || e.currentTarget.dataset.os)) {
      e.preventDefault();
      navigator.clipboard.writeText(url).then(
        () => showToast("Link copied. Open <b>evadeclient.com/download</b> on your computer."),
        () => { location.href = "/download"; });
    }
  };
  all(".js-primary").forEach(a => {
    a.removeAttribute("download");
    a.setAttribute("href", "/download");
    a.addEventListener("click", send);
  });
  setText(".js-primary-label", "Send it to your computer");
  // The per-OS installer buttons on /download: share the page rather than put an installer on the phone.
  all(".os[data-os] a[download]").forEach(a => {
    a.removeAttribute("download");
    a.dataset.os = a.closest(".os").dataset.os;
    a.addEventListener("click", send);
  });
}
all(".os[data-os]").forEach(c => c.classList.toggle("yours", c.dataset.os === os));

// ---------- hero player: the three window dots switch between recordings; each plays through, then the next
const stageVid = one("#stageVid");
if (stageVid) {
  const dots = all(".stage-dot"), label = one("#stageLabel"), bar = one("#stageProgress");
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let cur = 0, raf = 0;
  const tick = () => {
    if (stageVid.duration) bar.style.transform = `scaleX(${stageVid.currentTime / stageVid.duration})`;
    raf = requestAnimationFrame(tick);
  };
  const show = (i, play) => {
    cur = (i + dots.length) % dots.length;
    const d = dots[cur];
    dots.forEach(x => { const on = x === d; x.classList.toggle("on", on); x.setAttribute("aria-selected", on); });
    label.textContent = d.dataset.label;
    stageVid.poster = d.dataset.poster;
    stageVid.src = d.dataset.src;
    bar.style.transform = "scaleX(0)";
    if (play) stageVid.play().catch(() => {});
  };
  if (still) { stageVid.removeAttribute("autoplay"); stageVid.pause(); }
  dots.forEach((d, i) => d.addEventListener("click", () => show(i, true)));
  stageVid.addEventListener("ended", () => show(cur + 1, true));
  stageVid.addEventListener("play", () => { cancelAnimationFrame(raf); raf = requestAnimationFrame(tick); });
  stageVid.addEventListener("pause", () => cancelAnimationFrame(raf));
  // Don't download or play clips while the hero is off screen.
  new IntersectionObserver(([e]) => {
    if (still) return;
    if (e.isIntersecting) stageVid.play().catch(() => {}); else stageVid.pause();
  }).observe(stageVid);
}

// ---------- download toast
all("a[download]").forEach(a => a.addEventListener("click", () => {
  const href = a.getAttribute("href");
  showToast(/evade-client/.test(href)
    ? "Mod downloaded. Put it in your <b>mods</b> folder with Fabric API."
    : /Clips/.test(href) ? "Download started. Open it to start <b>Evade Clips</b>."
    : "Download started. Open it and click <b>Install</b>.");
}));
})();
