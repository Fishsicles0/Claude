// Shared script for every page of evadeclient.com. Each block checks that its elements exist.
(() => {
"use strict";
const one = (s, r = document) => r.querySelector(s);
const all = (s, r = document) => Array.from(r.querySelectorAll(s));

// ---------- mobile menu
const navEl = one("nav"), burger = one("#burger");
if (burger) {
  burger.addEventListener("click", () => {
    const open = navEl.classList.toggle("open");
    burger.setAttribute("aria-expanded", open);
    burger.setAttribute("aria-label", open ? "Close menu" : "Open menu");
  });
  all("#mobileMenu a").forEach(a => a.addEventListener("click", () => {
    navEl.classList.remove("open");
    burger.setAttribute("aria-expanded", "false");
  }));
}

// ---------- reveal on scroll
const io = new IntersectionObserver(es => es.forEach(en => {
  if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); }
}), { threshold: .12 });
all(".rv").forEach(el => io.observe(el));
const hero = one("#heroShot");
if (hero) addEventListener("scroll", () => hero.classList.toggle("flat", scrollY > 80), { passive: true });

// ---------- features grid (features page)
const MODS = [
  ["combat","AutoPot","Throws healing splash potions when you're low and re-buffs Strength, Speed and Fire Resistance when they wear off."],
  ["combat","AutoTotem","Puts a totem back in your offhand straight after a pop. Smart mode only does it when you're in danger."],
  ["combat","AutoMend","Throws XP bottles to repair Mending armor, and takes off finished pieces so the XP goes where it's needed."],
  ["combat","AimAssist","Smoothly eases your aim onto the nearest edge of a target's hitbox, rounded to your mouse sensitivity."],
  ["combat","AutoCritical","Times jumps so your hits land as real critical hits. Also has a Packet mode."],
  ["combat","AutoClicker","Accurate clicks per second with randomised timing. Leaves mining alone."],
  ["combat","Reach","Hit from further away, with slight random variance so the distance isn't fixed."],
  ["combat","TriggerBot","Attacks the moment your crosshair is on a target and your weapon is charged. Skips shields unless you hold an axe."],
  ["combat","Hitbox","Makes enemy hitboxes bigger for your crosshair."],
  ["visuals","ChestESP","Shows chests, shulkers, ender chests, barrels and spawners through walls."],
  ["visuals","ChunkLocator","Flags chunks with stashes, shulkers, rotated deepslate or player-made blocks underground, and posts the coordinates."],
  ["visuals","PlayerTracers","Lines to every player, coloured by distance, with your current target highlighted."],
  ["hud","TargetHUD","Your opponent's face, health, armor durability, held items and whether you're winning."],
  ["hud","ArmorHUD","Your armor durability plus counts of totems, gapples, pearls and potions."],
  ["hud","Cooldowns","Timers for pearls, wind charges, spears, shields and gapples, with a READY ping."],
  ["hud","Keystrokes","WASD, mouse buttons with CPS and the space bar. Keys flash red when pressed."],
  ["hud","Potion Effects","Effect icons with time left. Blinks when an effect is about to run out."],
  ["hud","CPS · FPS · Ping","Clicks per second, frame rate and server latency."],
  ["hud","Coordinates","XYZ, facing direction, biome and nether conversion."],
  ["hud","Direction HUD","A compass strip with your heading."],
  ["hud","Reach Display · Combo","Distance of your last hit and your current combo."],
  ["hud","Clock · Server · Speed · Memory","Real time, server address, blocks per second and RAM use."],
  ["hud","Module List","Active modules in a red wave down the side of the screen."],
  ["mods","ToggleSprint","Tap sprint or sneak to toggle them instead of holding."],
  ["mods","Zoom","Hold C to zoom, scroll to change how far, with smooth zoom in and out."],
  ["mods","Freelook","Hold Left Alt to look around without turning your player."],
  ["mods","Custom Crosshair","Five styles, any colour, and it turns red when it's on a target."],
  ["mods","Fullbright","See in the dark without changing your brightness setting."],
  ["mods","Block Outline","Pick the colour, thickness and fill of the block outline."],
  ["mods","No Hurt Cam","Removes the screen shake when you take damage."],
  ["mods","Time & Weather","Choose the time of day and weather you see."],
];
const grid = one("#grid");
if (grid) {
  grid.innerHTML = MODS.map(([c, n, d]) =>
    `<div class="mod" data-c="${c}"><div class="cat">${c}</div><h3>${n}</h3><p>${d}</p></div>`).join("");
  const select = f => {
    all(".tab").forEach(x => x.classList.toggle("on", x.dataset.f === f));
    grid.querySelectorAll(".mod").forEach(m => m.classList.toggle("hide", f !== "all" && m.dataset.c !== f));
  };
  one("#tabs").addEventListener("click", e => {
    const t = e.target.closest(".tab"); if (!t) return;
    select(t.dataset.f);
    history.replaceState(null, "", t.dataset.f === "all" ? location.pathname : "#" + t.dataset.f);
  });
  const fromHash = location.hash.slice(1);
  if (all(".tab").some(t => t.dataset.f === fromHash)) select(fromHash);
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
    const b = e.target.closest(".show-item"); if (!b) return;
    all(".show-item").forEach(x => x.classList.toggle("on", x === b));
    showImg.style.opacity = 0;
    setTimeout(() => { showImg.src = b.dataset.img; showImg.onload = () => showImg.style.opacity = 1; }, 180);
  });
}

// ---------- lightbox for gallery images
const lb = one("#lightbox");
if (lb) {
  all("[data-full]").forEach(el => el.addEventListener("click", () => {
    one("img", lb).src = el.dataset.full;
    one("figcaption", lb).textContent = el.dataset.caption || "";
    lb.classList.add("open");
  }));
  lb.addEventListener("click", () => lb.classList.remove("open"));
  addEventListener("keydown", e => { if (e.key === "Escape") lb.classList.remove("open"); });
}

// ---------- version info (written by scripts/build-site.sh)
const setText = (sel, val) => all(sel).forEach(e => e.textContent = val);
fetch("/version.json").then(r => r.ok ? r.json() : null).then(v => {
  if (!v) return;
  setText(".js-version", "v" + v.version);
  setText(".js-mc", v.minecraft);
  setText(".js-version-line", "v" + v.version + " · Minecraft " + v.minecraft);
  setText(".js-date", new Date(v.date + "T12:00:00").toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" }));
  if (v.installer) {
    setText(".js-size-exe", v.installer.windows.size);
    setText(".js-size-ijar", v.installer.jar.size);
    setText(".js-sha-exe", v.installer.windows.sha256);
    setText(".js-sha-ijar", v.installer.jar.sha256);
  }
  setText(".js-size-mod", v.size);
  setText(".js-sha-mod", v.sha256);
}).catch(() => {});

// copy-to-clipboard buttons (checksums)
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
if (os === "mac" || os === "linux") {
  all(".js-primary").forEach(a => a.setAttribute("href", "/downloads/EvadeInstaller.jar"));
  setText(".js-primary-label", os === "mac" ? "Download for macOS" : "Download for Linux");
  setText(".js-primary-meta", "One-click installer · needs Java 17+");
}
all(".os-card").forEach(c => c.classList.toggle("yours", c.dataset.os === os));

// ---------- download toast
const toast = one("#toast"), toastText = one("#toastText");
all("a[download]").forEach(a => a.addEventListener("click", () => {
  toastText.innerHTML = /evade-client/.test(a.getAttribute("href"))
    ? "Mod downloaded. Put it in your <b>mods</b> folder with Fabric API."
    : "Download started. Open it and click <b>Install</b>.";
  toast.classList.add("show");
  setTimeout(() => toast.classList.remove("show"), 4500);
}));
})();
