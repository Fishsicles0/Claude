#!/usr/bin/env python3
"""Builds the multi-page site.

Each file in site-src/pages/*.html starts with a small header block:

    title: Features
    description: Every mod in Evade Client...
    nav: features
    ---
    <page body html>

and is wrapped in the shared layout below (head, nav, footer, toast, scripts), then written to
site/<name>.html. Cloudflare serves /features from features.html, so links use clean URLs.
"""
import functools
import hashlib
import html
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "site-src" / "pages"
OUT = ROOT / "site"
SITE = "https://evadeclient.com"

NAV = [("home", "/", "Home"), ("features", "/features", "Features"), ("clips", "/clips", "Clips"), ("download", "/download", "Download"),
       ("docs", "/docs", "Docs"), ("changelog", "/changelog", "Changelog"), ("showcase", "/showcase", "Showcase"),
       ("faq", "/faq", "FAQ")]

LOGO = ('<img src="/assets/logo-mark.svg" alt="" width="32" height="32">'
        '<span><span class="wordmark">EVADE</span><span class="logo-tag">CLIENT</span></span>')

LAYOUT = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<meta name="theme-color" content="#050506">
<link rel="canonical" href="{url}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Evade Client">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{description}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{site}/assets/og.png?v={og_key}">
<meta property="og:image:type" content="image/png">
<meta property="og:image:width" content="1200">
<meta property="og:image:height" content="630">
<meta property="og:image:alt" content="{og_alt}">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:image" content="{site}/assets/og.png?v={og_key}">
<meta name="twitter:image:alt" content="{og_alt}">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon-32.png" sizes="32x32" type="image/png">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Sora:wght@600;700;800&family=Chakra+Petch:ital,wght@1,700&family=JetBrains+Mono:wght@500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="/assets/site.css?v={css_key}">
<script>document.documentElement.classList.add("js")</script>
</head>
<body data-page="{nav}">
<a class="sr-only" href="#main">Skip to content</a>
<div class="bg-fx" aria-hidden="true"></div>

<nav class="top" id="top">
  <div class="wrap nav-in">
    <a href="/" class="logo" aria-label="Evade Client home">{logo}</a>
    <div class="nav-links">
{links}
    </div>
    <div class="nav-right">
      <span class="nav-ver">v{version}</span>
      <a href="https://clips.evadeclient.com" class="btn btn-ghost btn-sm nav-clips">Evade Clips &#8599;</a>
      <a href="/download" class="btn btn-red btn-sm">Download</a>
      <button class="burger" id="burger" aria-label="Open menu" aria-expanded="false" aria-controls="mobileMenu"><span></span></button>
    </div>
  </div>
  <div class="mobile-menu" id="mobileMenu">
{mobile_links}
  </div>
</nav>

<main id="main">
{body}
</main>

<footer>
  <div class="foot-big" aria-hidden="true">EVADE</div>
  <div class="wrap foot">
    <div>
      <a href="/" class="logo" aria-label="Evade Client home">{logo}</a>
      <p>A free Fabric client for Minecraft {mc}. Not affiliated with Mojang Studios or Microsoft. Minecraft is a trademark of Mojang Studios. Always follow the rules of the servers you play on.</p>
    </div>
    <div class="foot-cols">
      <div><h5>Client</h5><a href="/features">Features</a><a href="/showcase">Showcase</a><a href="/download">Download</a></div>
      <div><h5>Downloads</h5><a href="/download">Installer</a><a href="/clips">Evade Clips</a><a href="/download#install">Install guide</a></div>
      <div><h5>Evade Clips</h5><a href="/clips">Evade Clips</a><a href="https://clips.evadeclient.com">clips.evadeclient.com</a><a href="/downloads/Evade-Clips.exe">Download</a></div>
      <div><h5>Help</h5><a href="/faq">FAQ</a><a href="/faq#update">Updating</a><a href="/servers">Servers</a><a href="/privacy">Privacy policy</a><a href="mailto:privacy@evadeclient.com">Contact</a></div>
    </div>
  </div>
  <div class="wrap foot-bottom"><span>&copy; 2026 Evade Client &middot; Made by the Evade Team &middot; <a href="/privacy">Privacy</a></span><span>v{version} &middot; Minecraft {mc} &middot; Fabric</span></div>
</footer>

<div class="toast" id="toast" role="status" aria-live="polite"><span id="toastText">Download started. Open it and click <b>Install</b>.</span></div>
<script src="/assets/site.js?v={js_key}" defer onerror="document.documentElement.classList.remove('js')"></script>
</body>
</html>
"""


ACTIVE = ' class="on" aria-current="page"'


def page(name: str, meta: dict, body: str, ver: dict, keys: dict) -> str:
    nav = meta.get("nav", "home")
    links = "\n".join(
        f'      <a href="{href}"{ACTIVE if key == nav else ""}>{label}</a>'
        for key, href, label in NAV)
    mobile = "\n".join(
        f'    <a href="{href}"{ACTIVE if key == nav else ""}>{label}</a>'
        for key, href, label in NAV)
    path = "/" if name == "index" else f"/{name}"
    title = meta["title"] if name == "index" else f'{meta["title"]} · Evade Client'
    og_alt = f'Evade Client: free, server-safe quality of life for Minecraft {ver["minecraft"]}'
    return LAYOUT.format(
        title=html.escape(title), description=html.escape(meta["description"]), url=SITE + path, site=SITE,
        nav=nav, logo=LOGO, links=links, mobile_links=mobile, version=ver["version"], mc=ver["minecraft"],
        og_alt=html.escape(og_alt), body=body.strip(), **keys)


# ---------- the mod catalogue, read straight from the client source ----------

JAVA = ROOT / "src" / "main" / "java" / "net" / "evadeclient"
MODULES = JAVA / "module"

# Category enum key -> label on the site. Sorting follows this order; CLIENT (the Settings page) is left out.
CATEGORY_ORDER = ["SERVER", "VISUALS", "HUD", "MODS", "MISC"]
CATEGORY_LABEL = {"SERVER": "Server-facing", "VISUALS": "Visuals", "HUD": "HUD", "MODS": "Controls", "MISC": "Misc"}

SETTING_TYPES = ("NumberSetting", "BooleanSetting", "ModeSetting", "ColorSetting", "KeySetting", "StringSetting")
# `new ModeSetting(` and fully qualified `new net.evadeclient.setting.ModeSetting(` alike.
SETTING_START = re.compile(r"\bnew\s+(?:[\w.]+\.)?(" + "|".join(SETTING_TYPES) + r")\s*\(")
STRING_LIT = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def warn(msg: str) -> None:
    print(f"warning: {msg}", file=sys.stderr)


def strip_comments(src: str) -> str:
    """Java source without // and /* */ comments (string and char literals are left alone)."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c in "\"'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            out.append(src[i:j + 1]); i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            out.append(" "); i = n if j < 0 else j + 2
        else:
            out.append(c); i += 1
    return "".join(out)


def balanced(src: str, i: int):
    """For src[i] == '(', the text between it and its matching ')' and the index just past that ')'."""
    depth, j, n = 0, i, len(src)
    while j < n:
        c = src[j]
        if c in "\"'":
            k = j + 1
            while k < n and src[k] != c:
                k += 2 if src[k] == "\\" else 1
            j = k
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return src[i + 1:j], j + 1
        j += 1
    return src[i + 1:], n


def split_top(inner: str) -> list:
    """Arguments of a call, split on the commas that aren't inside quotes or brackets."""
    out, cur, depth, i, n = [], [], 0, 0, len(inner)
    while i < n:
        c = inner[i]
        if c in "\"'":
            k = i + 1
            while k < n and inner[k] != c:
                k += 2 if inner[k] == "\\" else 1
            cur.append(inner[i:k + 1]); i = k + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            out.append("".join(cur).strip()); cur = []
        else:
            cur.append(c)
        i += 1
    if "".join(cur).strip():
        out.append("".join(cur).strip())
    return out


def unescape(s: str) -> str:
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)",
                  lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == "u" and len(m.group(1)) == 5
                  else {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)


def java_string(expr: str):
    """The value of a string literal, or of literals joined with +; None for anything else."""
    expr = expr.strip()
    parts = [p.strip() for p in split_plus(expr)]
    if not parts or not all(STRING_LIT.fullmatch(p) for p in parts):
        return None
    return "".join(unescape(STRING_LIT.fullmatch(p).group(1)) for p in parts)


def split_plus(expr: str) -> list:
    out, cur, q, i = [], "", False, 0
    while i < len(expr):
        c = expr[i]
        if c == "\\" and q:
            cur += expr[i:i + 2]; i += 2
            continue
        if c == '"':
            q = not q
        if c == "+" and not q:
            out.append(cur); cur = ""
        else:
            cur += c
        i += 1
    out.append(cur)
    return out


def statement_end(src: str, i: int) -> int:
    """Index of the `;` that ends the statement running from src[i], skipping quotes and brackets; or of the bracket
    that closes the one it sits in (a `final` parameter), or len(src)."""
    depth, n = 0, len(src)
    while i < n:
        c = src[i]
        if c in "\"'":
            k = i + 1
            while k < n and src[k] != c:
                k += 2 if src[k] == "\\" else 1
            i = k
        elif c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth < 0:
                return i
        elif c == ";" and depth == 0:
            return i
        i += 1
    return n


FINAL_DECL = re.compile(r"\bfinal\s+(?:(?:static|transient|volatile)\s+)*")
TYPE_HEAD = re.compile(r"[\w.]+")


def mask_literals(src: str) -> str:
    """src with the inside of every string and char literal blanked out (same length), so a search for Java
    keywords doesn't land in a description."""
    out, i, n = list(src), 0, len(src)
    while i < n:
        c = src[i]
        if c in "\"'":
            k = i + 1
            while k < n and src[k] != c and src[k] != "\n":
                k += 2 if src[k] == "\\" else 1
            for p in range(i + 1, min(k, n)):
                out[p] = " "
            i = k + 1
        else:
            i += 1
    return "".join(out)


@functools.lru_cache(maxsize=None)
def constants(src: str) -> dict:
    """Every `final` variable in one source with its value: `static final String OFF = "Off", ON = "On";` gives both,
    `final String[] KINDS = {"A", "B"};` gives the array literal, `Map<String, X> M = ...` skips the type's comma.
    The first declaration of a name wins."""
    out = {}
    for m in FINAL_DECL.finditer(mask_literals(src)):
        t = TYPE_HEAD.match(src, m.end())
        if not t:
            continue
        i = t.end()
        while i < len(src) and src[i].isspace():
            i += 1
        if i < len(src) and src[i] == "<":  # generic type arguments, which can hold commas
            depth = 0
            while i < len(src):
                depth += {"<": 1, ">": -1}.get(src[i], 0)
                i += 1
                if depth == 0:
                    break
        while True:  # array brackets after the type
            k = i
            while k < len(src) and src[k].isspace():
                k += 1
            if not src.startswith("[", k):
                break
            close = src.find("]", k)
            if close < 0:
                break
            i = close + 1
        for decl in split_top(src[i:statement_end(src, i)]):
            d = re.fullmatch(r"([A-Za-z_]\w*)\s*=\s*(.+)", decl, re.S)
            if d and d.group(1) not in out:
                out[d.group(1)] = d.group(2).strip()
    return out


def resolve(expr: str, srcs: list, depth: int = 0) -> str:
    """Follows a constant (`static final ... NAME = ...`) or a no-argument method (`boolean x() { return ...; }`)
    to the literal it stands for, looking in the module's own source first, then in its base classes."""
    expr = expr.strip()
    if depth > 4:
        return expr
    m = re.fullmatch(r"(?:[\w.]+\.)?(\w+)\(\s*\)", expr)
    if m:
        pat = re.compile(r"\b[\w<>\[\]]+\s+" + m.group(1) + r"\s*\(\s*\)\s*\{\s*return\s+([^;]+);\s*\}")
        for s in srcs:
            hit = pat.search(s)
            if hit:
                return resolve(hit.group(1), srcs, depth + 1)
        return expr
    m = re.fullmatch(r"(?:([\w.]+)\.)?([A-Za-z_]\w*)", expr)
    if m and expr not in ("true", "false", "null"):
        owner = (m.group(1) or "").split(".")[-1]  # Theme.PRESETS: also look in util/Theme.java
        more = [strip_comments(CLASS_FILES[owner].read_text())] if owner in CLASS_FILES else []
        for s in more + srcs:
            value = constants(s).get(m.group(2))
            if value is not None:
                return resolve(value, more + srcs if more else srcs, depth + 1)
    return expr


def array_items(expr: str, srcs: list, depth: int = 0):
    """The items of a String[] argument: an array literal, a constant holding one, or a small helper that puts one
    item in front of another array, such as `prepend("Local", ZONES)` or `withOff(KINDS)` (its body sets `out[0]`
    and copies the rest in with System.arraycopy). None for anything else."""
    expr = resolve(expr, srcs)
    arr = re.fullmatch(r"(?:new\s+String\s*\[\s*\]\s*)?\{(.*)\}", expr, re.S)
    if arr:
        return [resolve(x, srcs) for x in split_top(arr.group(1))]
    call = re.fullmatch(r"(?:[\w.]+\.)?(\w+)\s*\((.*)\)", expr, re.S)
    if not call or depth > 4:
        return None
    head = re.compile(r"\bstatic\s+String\s*\[\s*\]\s+" + call.group(1) + r"\s*\(([^)]*)\)\s*\{")
    for s in srcs:
        h = head.search(s)
        if not h:
            continue
        body = balanced(s, h.end() - 1)[0]
        params = [p.split()[-1] for p in split_top(h.group(1)) if p.split()]
        args = split_top(call.group(2))
        if len(params) != len(args):
            return None
        bind = dict(zip(params, args))
        sub = lambda e: bind.get(e.strip(), e.strip())
        first = re.search(r"\b(\w+)\s*\[\s*0\s*\]\s*=\s*([^;]+);", body)
        copy = first and re.search(r"System\.arraycopy\(\s*(\w+)\s*,\s*0\s*,\s*" + first.group(1) + r"\s*,\s*1\s*,", body)
        if not copy:
            return None
        rest = array_items(sub(copy.group(1)), srcs, depth + 1)
        return None if rest is None else [resolve(sub(first.group(2)), srcs)] + rest
    return None


def num(expr: str):
    try:
        v = float(re.sub(r"[fFdDlL]$", "", expr.replace("_", "")))
    except ValueError:
        return None
    return str(int(v)) if v == int(v) else f"{v:g}"


def color(expr: str):
    e = expr.replace("_", "").rstrip("lL")
    try:
        v = int(e, 16) if e.lower().startswith("0x") else int(e)
    except ValueError:
        return None
    return f"#{v & 0xFFFFFF:06X}"


def key_name(k: str) -> str:
    k = k.split(".")[-1].replace("GLFW_KEY_", "")
    if k in ("UNKNOWN", "-1"):
        return "None"
    if k.startswith("GLFW_MOUSE_BUTTON_"):
        return "Mouse " + k.replace("GLFW_MOUSE_BUTTON_", "").title()
    return k.replace("_", " ").title()


def chained_calls(src: str, i: int):
    """The calls chained onto a setting, `new X(...).describe("...").hidden()` or `add(new X(...)).describe("...")`,
    as a {method: arguments} dict."""
    calls, n = {}, len(src)
    closed_add = False
    while True:
        j = i
        while j < n and src[j].isspace():
            j += 1
        if j < n and src[j] == ")" and not closed_add:  # the end of add(...): calls after it still apply
            k = j + 1
            while k < n and src[k].isspace():
                k += 1
            if k < n and src[k] == ".":
                closed_add, j = True, k
        if j >= n or src[j] != ".":
            return calls
        m = re.match(r"\.\s*(\w+)\s*\(", src[j:])
        if not m:
            return calls
        inner, i = balanced(src, j + m.end() - 1)
        calls[m.group(1)] = inner


def scan_settings(src: str, srcs: list, where: str) -> list:
    """Every setting declared in one class: name, type, default and range or options, plus its description."""
    out = []
    for m in SETTING_START.finditer(src):
        kind = m.group(1)
        inner, end = balanced(src, m.end() - 1)
        args = split_top(inner)
        name = java_string(args[0]) if args else None
        if name is None:
            continue
        calls = chained_calls(src, end)
        if "hidden" in calls:
            continue
        vals = [resolve(a, srcs) for a in args[1:]]
        st = {"name": name}
        try:
            if kind == "BooleanSetting":
                st |= {"type": "toggle", "default": {"true": "On", "false": "Off"}[vals[0]]}
            elif kind == "NumberSetting":
                d, lo, hi = (num(v) for v in vals[:3])
                if None in (d, lo, hi):
                    raise ValueError
                st |= {"type": "slider", "default": d, "min": lo, "max": hi}
            elif kind == "ModeSetting":
                opts = []
                for v in vals[1:]:
                    items = array_items(v, srcs)
                    opts += [java_string(x) for x in (items if items is not None else [v])]
                if java_string(vals[0]) is None or None in opts:
                    raise ValueError
                st |= {"type": "mode", "default": java_string(vals[0]), "options": opts}
            elif kind == "ColorSetting":
                c = color(vals[0])
                if c is None:
                    raise ValueError
                st |= {"type": "color", "default": c}
            elif kind == "KeySetting":
                st |= {"type": "key", "default": key_name(vals[0])}
            elif kind == "StringSetting":
                text = java_string(vals[0])
                if text is None:
                    raise ValueError
                st |= {"type": "text", "default": text or "Empty"}
                hint = java_string(vals[1]) if len(vals) > 1 else None
                if hint:
                    st["hint"] = hint
        except (IndexError, KeyError, ValueError):
            warn(f"{where}: can't read the default of setting '{name}' ({kind}: {inner.strip()}); it is left off the site")
            continue
        desc = java_string(resolve(calls["describe"], srcs)) if "describe" in calls else None
        if desc:
            st["desc"] = desc
        out.append(st)
    return out


CLASS_FILES = {f.stem: f for f in JAVA.rglob("*.java")}


def class_chain(src: str) -> list:
    """The module's source followed by each base class's source, up to (not including) Module."""
    out, seen = [src], set()
    while True:
        m = re.search(r"\bclass\s+\w+(?:\s*<[^{]*?>)?\s+extends\s+([\w.]+)", src)
        parent = m.group(1).split(".")[-1] if m else None
        if not parent or parent == "Module" or parent in seen or parent not in CLASS_FILES:
            return out
        seen.add(parent)
        src = strip_comments(CLASS_FILES[parent].read_text())
        out.append(src)


def module_header(src: str):
    """(name, description, category key or None) from the module's super(...) call."""
    for m in re.finditer(r"\bsuper\s*\(", src):
        args = split_top(balanced(src, m.end() - 1)[0])
        if len(args) < 2:
            continue
        name, desc = java_string(resolve(args[0], [src])), java_string(resolve(args[1], [src]))
        if name is None or desc is None:
            continue
        cat = next((c.group(1) for a in args[2:] for c in [re.fullmatch(r"(?:[\w.]+\.)?Category\.([A-Z_]+)", a)] if c), None)
        return name, desc, cat
    return None


def load_new(ver: dict) -> set:
    """Classes the features page marks as New: site-src/new.json, which belongs to one x.y release."""
    path = ROOT / "site-src" / "new.json"
    if not path.exists():
        return set()
    data = json.loads(path.read_text())
    if series(data.get("version", "")) != series(ver["version"]):
        raise SystemExit(f"site-src/new.json lists what was new in v{data.get('version', '?')}, but the site is "
                         f"v{ver['version']}. Set its version to {ver['version']} and list the module classes added "
                         f"in this release (an empty list is fine).")
    return set(data["classes"])


def series(v: str) -> str:
    return ".".join(v.split(".")[:2])


def scan_mods(new: set) -> list:
    """Every registered module's name, description, category and settings."""
    registered = (MODULES / "ModuleManager.java").read_text()
    mods = []
    for f in sorted(MODULES.rglob("*.java")):
        src = strip_comments(f.read_text())
        if re.search(r"\babstract\s+class\b", src):
            continue
        cls = f.stem
        if not re.search(r"register\(\s*new\s+(?:[\w.]+\.)?" + cls + r"\s*\(\s*\)\s*\)", registered):
            continue
        head = module_header(src)
        if not head:
            continue
        name, desc, cat = head
        cat = cat or "HUD"  # HUD elements default to the HUD category
        if cat == "CLIENT":
            continue
        if cat not in CATEGORY_LABEL:
            raise SystemExit(f"{f.relative_to(ROOT)}: category {cat} has no label in build-pages.py CATEGORY_LABEL")
        srcs = class_chain(src)
        settings, seen = [], set()
        for s in srcs:  # own settings first, then the ones every HUD element inherits
            for st in scan_settings(s, srcs, cls):
                if st["name"] not in seen:
                    seen.add(st["name"]); settings.append(st)
        mods.append({"name": name, "desc": desc, "cat": CATEGORY_LABEL[cat], "slug": cat.lower(),
                     "settings": settings, "new": cls in new})
    unknown = new - {f.stem for f in MODULES.rglob("*.java")}
    if unknown:
        warn(f"site-src/new.json names classes that don't exist: {', '.join(sorted(unknown))}")
    mods.sort(key=lambda x: (CATEGORY_ORDER.index(x["slug"].upper()), x["name"].lower()))
    return mods


# ---------- the changelog's newest feature release, for the home page's "New in" pill ----------

def news(ver: dict) -> dict:
    """The x.y.0 release of the current series, read from the changelog: its anchor and one line about it.
    The line is the entry's data-pill attribute if it has one (short enough for the pill), else its summary."""
    text = (SRC / "changelog.html").read_text()
    entries = []
    for m in re.finditer(r'<article\b([^>]*)>(.*?)</article>', text, re.S):
        attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', m.group(1)))
        vid = attrs.get("id", "")
        if not re.fullmatch(r"v\d+\.\d+\.\d+", vid):
            continue
        summary = re.search(r'<p class="rel-sum">(.*?)</p>', m.group(2), re.S)
        entries.append((vid, attrs.get("data-pill") or (summary.group(1).strip() if summary else "")))
    current = [e for e in entries if series(e[0][1:]) == series(ver["version"])]
    if not current:
        raise SystemExit(f"changelog.html has no entry for v{series(ver['version'])}.x yet (looking for "
                         f'<article ... id="v{ver["version"]}">). Add one before building the site.')
    vid, line = current[-1]  # the oldest entry of the series: its x.y.0 release
    if not line:
        raise SystemExit(f"changelog.html: the {vid} entry needs a <p class=\"rel-sum\"> summary or a data-pill")
    return {"{{NEWS_ID}}": vid, "{{NEWS_SERIES}}": "v" + series(vid[1:]), "{{NEWS}}": line}


def file_key(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()[:10]


def main() -> None:
    ver = json.loads((OUT / "version.json").read_text())
    mods = scan_mods(load_new(ver))
    (OUT / "assets" / "mods.json").write_text(json.dumps(mods, separators=(",", ":")))
    # Cache keys: each asset's URL changes whenever its content does, so browsers, Discord and X fetch the new one.
    keys = {k: file_key(OUT / "assets" / f) for k, f in
            (("css_key", "site.css"), ("js_key", "site.js"), ("og_key", "og.png"), ("mods_key", "mods.json"))}
    # count by enum key (HUD, MODS, SERVER...) so site tokens are {{HUD}} etc., not label text
    counts = {key: sum(1 for m in mods if m["cat"] == label) for key, label in CATEGORY_LABEL.items()}
    n_settings = sum(len(m["settings"]) for m in mods)
    n_new = sum(1 for m in mods if m["new"])
    tokens = {"{{TOTAL}}": str(len(mods))} | {"{{" + k + "}}": str(v) for k, v in counts.items()}
    tokens |= {"{{SETTINGS}}": str(n_settings), "{{NEW}}": str(n_new), "{{CATS}}": str(len([c for c in counts.values() if c]))}
    clips = ver.get("clips") or {}
    tokens |= {
        "{{VERSION}}": ver["version"], "{{MC}}": ver["minecraft"], "{{DATE}}": ver["date"],
        "{{SIZE_MOD}}": ver["size"], "{{SHA_MOD}}": ver["sha256"],
        "{{SIZE_EXE}}": ver["installer"]["windows"]["size"], "{{SHA_EXE}}": ver["installer"]["windows"]["sha256"],
        "{{SIZE_IJAR}}": ver["installer"]["jar"]["size"], "{{SHA_IJAR}}": ver["installer"]["jar"]["sha256"],
        "{{CLIPS_VERSION}}": clips.get("version", ""),
        "{{SIZE_CLIPS_EXE}}": (clips.get("windows") or {}).get("size", ""),
        "{{SHA_CLIPS_EXE}}": (clips.get("windows") or {}).get("sha256", ""),
        "{{SIZE_CLIPS_JAR}}": (clips.get("jar") or {}).get("size", ""),
        "{{SHA_CLIPS_JAR}}": (clips.get("jar") or {}).get("sha256", ""),
    }
    tokens |= news(ver)
    tokens |= {"{{MODS_KEY}}": keys["mods_key"]}
    # One card per HUD element, for the Showcase page's HUD gallery (links to each element's detail view).
    hud_cards = "".join(
        f'<a class="card hud-card" href="/features#m={html.escape(m["name"].replace(" ", "%20"))}">'
        f'<h4>{html.escape(m["name"])}</h4><p>{html.escape(m["desc"])}</p>'
        f'<span class="hud-foot">{len(m["settings"])} setting{"s" if len(m["settings"]) != 1 else ""} <em>&rarr;</em></span></a>'
        for m in mods if m["slug"] == "hud")
    # The full mod list, rendered into the page so it works without JavaScript (and for search engines).
    # site.js keeps these cards and adds search, filters and the detail view on top.
    def esc(x):
        return html.escape(str(x), quote=True)

    def card(m):
        n = len(m["settings"])
        search = (m["name"] + " " + m["desc"] + " " + m["cat"] + " " + " ".join(x["name"] for x in m["settings"])).lower()
        sets = "".join(f'<li><b>{esc(x["name"])}</b> &middot; {esc(x["default"])}</li>' for x in m["settings"])
        more = f'<details class="mod-sets"><summary>{n} setting{"s" if n != 1 else ""}</summary><ul>{sets}</ul></details>' if n else ""
        return (f'<article class="card mod" data-m="{esc(m["name"])}" data-c="{m["slug"]}" data-new="{1 if m["new"] else 0}" data-s="{esc(search)}">'
                f'<div class="mod-top"><span class="cat">{esc(m["cat"])}{" <em class=\"new\">New</em>" if m["new"] else ""}</span></div>'
                f'<h3>{esc(m["name"])}</h3><p>{esc(m["desc"])}</p>'
                f'<span class="mod-foot">{f"{n} setting" + ("s" if n > 1 else "") if n else "No settings"} <em>View &rarr;</em></span>{more}</article>')
    tokens |= {"{{MOD_GRID}}": "".join(card(m) for m in mods)}
    tokens |= {"{{HUD_LIST}}": hud_cards, "{{HUD_COUNT}}": str(sum(1 for m in mods if m["slug"] == "hud"))}
    print(f"mods.json: {len(mods)} mods, {n_settings} settings, {n_new} new: " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    for src in sorted(SRC.glob("*.html")):
        text = src.read_text()
        for k, v in tokens.items():
            text = text.replace(k, v)
        head, body = text.split("\n---\n", 1)
        meta = dict(line.split(": ", 1) for line in head.strip().splitlines())
        out = OUT / src.name
        left = re.findall(r"\{\{[A-Z_]+\}\}", body + meta["description"])
        if left:
            raise SystemExit(f"{src.name}: unknown tokens {sorted(set(left))}")
        out.write_text(page(src.stem, meta, body, ver, keys))
        print(f"built {out.relative_to(ROOT)}")
    write_seo_files(ver)


SITE = "https://evadeclient.com"


def write_seo_files(ver: dict) -> None:
    """sitemap.xml and robots.txt for search engines, and _headers so browsers always use HTTPS."""
    pages = sorted(p.stem for p in SRC.glob("*.html") if p.stem != "404")
    prio = {"index": "1.0", "download": "0.9", "features": "0.9", "clips": "0.8"}
    urls = "".join(
        f"  <url><loc>{SITE}{'/' if p == 'index' else '/' + p}</loc><lastmod>{ver['date']}</lastmod>"
        f"<priority>{prio.get(p, '0.6')}</priority></url>\n" for p in pages)
    (OUT / "sitemap.xml").write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n{urls}</urlset>\n')
    (OUT / "robots.txt").write_text(f"User-agent: *\nAllow: /\n\nSitemap: {SITE}/sitemap.xml\n")
    (OUT / "_headers").write_text(
        "/*\n"
        "  Strict-Transport-Security: max-age=31536000; includeSubDomains\n"
        "  X-Content-Type-Options: nosniff\n"
        "  Referrer-Policy: strict-origin-when-cross-origin\n")
    print(f"built sitemap.xml ({len(pages)} pages), robots.txt, _headers")


if __name__ == "__main__":
    main()
