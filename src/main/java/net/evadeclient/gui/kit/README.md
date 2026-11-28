# Evade GUI kit (GUI 3.0 "Obsidian")

The shared component kit every Evade screen builds on. Spec: `DESIGN.md` ("Obsidian"), sections 3–6.
See every component in every state: chat `.kitgallery` (`KitGalleryScreen`).

## Layers

| Layer | Class | What it does |
|---|---|---|
| Shape primitive | `render/ui/UiShape` | One quad per shape, drawn per pixel by `evade:core/ui_shape` (SDF rounded rect with per-corner radii, 1-device-px borders, linear / radial / mirrored gradients, specular top rim, inner shade, grain, Gaussian shadows with knockout, additive glows, ring arcs). Every pixel is dithered (±0.5 LSB). Shapes batch: all shapes between two text runs are one draw call. |
| Fallback | `util/Theme` (texture shapes) | Used automatically when `UiShape.available()` is false: Classic theme, shader failed to compile, the HUD (`UiShape.legacyScope`), or `UiShape.forceFallback(true)` (tests). `Theme.round / stroke / hairline / disc / pill / shadow / fadeLine / knobShadow / toggle` route through `UiShape` by themselves, so unmigrated screens already get exact corners and Gaussian shadows. |
| Tokens | `util/Tokens` | Neutral ramp `N0..N950` (Obsidian / Midnight / OLED / Classic), accent ramp `A50..A950` (`A400` = accent text, `A600` = brand core), status `OK WARN DANGER INFO`, rarity, elevations `E1..E5, SUNKEN`, radii `R_XS..R_XL`, spacing `S0..S12`, heights `H_SM..H_XL`, `ROW_DENSE/COMFY`, user options (`reduceTransparency`, `highContrast`, `comfortable`, `uiScale`). `Tokens.refresh()` is called by the kit every frame (no-op when nothing changed). |
| Motion | `gui/Motion` | `DUR_*`, `standard(t)` / `exit(t)` / `Bezier`, springs `SNAPPY SMOOTH PLAYFUL`, `level()` (Full / Reduced / Off, from Client Settings › Animations plus the title's Reduce Motion), `decorative()`, `duration(ms)`, `spring(s, target, token, dt)`. |
| Type | `util/UiFont` | Roles `DISPLAY HEADLINE TITLE STRONG BODY LABEL SMALL TINY CAPS CAPS_SM`, `centredY(role, centreY, smooth)` (optical cap-height centring), `drawTabular / tabularWidth` (tabular digits). Fonts per role per GUI scale 1–8 (`scripts/build-ui-fonts.py`). |
| Paint | `gui/kit/Paint` | Shader-or-fallback drawing: `rect pill circle ring vgradient hgradient radial fadeLine surface sunken focusRing glow slant dashed scrim beginTilt/endTilt`. |
| Kit | `gui/kit/Kit` | Per-screen state: hits, focus, springs, text fields, popovers, tooltips, glow budget. |

## Using the kit in a screen

```java
public final class MyScreen extends Screen {
    private final Kit kit = new Kit();

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        kit.begin(ctx, mx, my);                 // refreshes tokens, resolves hover from the last frame
        Surface.window(kit, x1, y1, x2, y2);
        int right = Surface.header(kit, "win", x1, y1, x2, "Shader Packs", "2 packs", this::back, this::close);
        Button.draw(kit, "apply", x, y, 0, Button.Size.MD, Button.Variant.PRIMARY, "Apply", null, true, this::apply);
        Toggle.draw(kit, "fog", x, y, true, fogOn, true, () -> fogOn = !fogOn);
        kit.end();                              // popovers, dropdown menus and the tooltip draw last, on top
    }

    // Forward input; each returns true when the kit used it.
    @Override public boolean mouseClicked(Click c, boolean d) { return kit.mouseClicked(c.x(), c.y(), c.button()) || super.mouseClicked(c, d); }
    @Override public boolean mouseReleased(Click c) { return kit.mouseReleased(c.x(), c.y(), c.button()) || super.mouseReleased(c); }
    @Override public boolean mouseDragged(Click c, double dx, double dy) { return kit.mouseDragged(c.x(), c.y(), c.button()) || super.mouseDragged(c, dx, dy); }
    @Override public boolean mouseScrolled(double x, double y, double h, double v) { return kit.mouseScrolled(x, y, v) || super.mouseScrolled(x, y, h, v); }
    @Override public boolean keyPressed(KeyInput i) { return kit.keyPressed(i.key(), i.modifiers()) || super.keyPressed(i); }
    @Override public boolean charTyped(CharInput i) { return kit.charTyped(i.codepoint()) || super.charTyped(i); }
}
```

Rules:
- **Ids** are any strings unique on the screen; components derive sub-ids (`id + "#h"` …). Use stable ids (a module's name, not a list index that changes when filtering).
- **Coordinates** are GUI px (design px). If you scale your layout (design units), pass the *transformed* mouse position to `begin` and to the input methods (see `KitGalleryScreen.dx/dy`).
- **Group fade**: set `Theme.fade` around a group as before; every kit draw honours it once.
- **Hit order**: later hits are on top. Popovers register in `end()`, so they cover the page. A modal registers a `barrier` (its scrim) and Tab focus is trapped above the last `…#scrim` barrier.
- **Pinned states** (screenshots): `kit.force(Kit.HOVER | Kit.FOCUSED)` applies to the next component only.
- **Glow budget**: at most `Tokens.MAX_GLOWS` (6) glows a frame; `Paint.glow` / `kit.glow()` return silently when spent, or when High Contrast / Reduce Transparency is on. Never in the HUD.
- **One primary per view.** Red fills only for the primary button; accent text is `Tokens.A400`, never body text.
- **HUD**: don't use the kit in the HUD. `Hud.render` runs inside `UiShape.legacyScope`, so `Theme` stays on its texture path there.

## Components (all in `gui/kit`)

Every component supports idle / hover / pressed / keyboard focus ring / disabled (and selected/on where it applies).

| Component | Call | Notes |
|---|---|---|
| Window | `Surface.window(k, x1, y1, x2, y2)` | e1 glass, r12, grain |
| Window header | `Surface.header(k, id, x1, y1, x2, title, subtitle, onBack, onClose)` → x for right-side actions | 40 tall, HEADLINE or TITLE+subtitle, fading divider |
| Card | `Surface.card(k, id, x1, y1, x2, y2, selected, tilt, onClick)` … `Surface.endCard(k)` | hover → e3, rim follows the cursor, ≤1.2° tilt (card grids only), selected = accent surface + slanted edge |
| Section header | `Surface.section(k, title, count, x, y)` → y below | CAPS n700 |
| Button | `Button.draw(k, id, x, y, w, Size, Variant, label, icon, [chevron,] enabled, onClick)` → width | `Variant.PRIMARY/SECONDARY/GHOST/DANGER`, `Size.SM/MD/LG/XL` (20/24/28/36); `Button.width(...)` |
| Icon button | `Button.icon(k, id, x, y, size, icon, tooltip, enabled, onClick)` | ghost square 24/28 |
| Toggle | `Toggle.draw(k, id, x, y, large, on, enabled, onToggle)` | 36×18 / 28×14, knob spring, halo glow |
| Slider | `Slider.draw(k, id, x, y, w, value, min, max, step, valueText, enabled, onChange)` | drag, arrows/Home/End, ticks ≤10 steps, value bubble, tabular value column (40) |
| Dropdown | `Dropdown.draw(k, id, x, y, w, options, selected, enabled, onPick)` | replaces `‹ Hold ›` cyclers; keyboard + type-ahead; wheel cycles the closed trigger |
| Segmented | `Segmented.draw(k, id, x, y, options, selected, enabled, onPick)` → width | gliding thumb, never red |
| Tabs | `Tabs.draw(k, id, x, y, maxW, tabs, selected, onPick)` → width | slanted gliding underline; scrolls with edge fades when wider than `maxW` |
| Sidebar | `Sidebar.highlight(k, group, x, w)` then `Sidebar.item(k, group, id, x, y, w, icon, label, count, selected, dot, onClick)`; `Sidebar.header(k, text, x, y)` | gliding accent-surface highlight, slanted edge, dot badge |
| Key chip | `KeyChip.draw(k, id, x, y, Mode, text, tooltip, onClick)` → width | `BOUND / NONE` (dashed) `/ LISTENING` (pulse) `/ CONFLICT` (warn); `KeyChip.label("Ctrl+F")` → "⌃F" |
| Search / text field | `SearchField.draw(k, id, x, y, w, placeholder, hintChip, icon)` → text | `SearchField.text(k, id)`, `SearchField.set(k, id, s)`; caret, Ctrl+word moves, paste, clear ×; max 360 wide in toolbars |
| Tooltip | `k.tooltip(id, text, sub, x1, y1, x2, y2)` (while hovered) | above the target, never over it; 450 ms delay; vanilla text tooltips on Evade screens are rerouted here automatically |
| Toast | `Notifications.push(title, body, Level, lifeMs)` | `Level.INFO/SUCCESS/WARN/ERROR`; dedupe ×N within 3 s, max 3 + "+N more", hover pauses; `Notifications.preview(...)` draws one statically |
| Dialog | `Dialog.draw(k, id, title, body, List<Dialog.Action>, openedAt)`; `Dialog.pushBack(k, id, open)` | draw last; Esc = first action, Enter = last; scrim barrier + focus trap |
| Scrollbar | `Scrollbar.draw(k, id, rightX, y1, y2, scroll, max, onScroll)`; `Scrollbar.edgeFades(k, x1, y1, x2, y2, bg, top, bottom)` | overlay, auto-hides 800 ms after scrolling |
| List row | `ListRow.draw(k, id, x, y, w, icon, title, desc, Boolean on, onToggle, onGear, onClick)`; `ListRow.height()` | 32/40 by Density; no red bar/outline (toggle + icon tint carry "on") |
| Colour picker | `ColorPicker.swatch(k, id, x, y, w, h, argb, onChange)` (opens the popover) or `ColorPicker.popover(...)` inline | SV, hue, alpha, hex, 12 swatches, 8 recents |
| Progress | `Progress.linear(k, x, y, w, value or NaN, label)`, `Progress.circle(k, cx, cy, r, value or NaN)` | emissive head, indeterminate sweep, tabular % |
| Badge | `Badge.draw(k, x, y, text, Tone)` → width | `NEUTRAL ACCENT OK WARN DANGER INFO` |
| Empty state | `EmptyState.draw(k, id, cx, y, icon, title, line, button, onButton)` → height | |

Icons (`util/Icons`, `textures/gui/nav`, 24/48/96/192): all, controls, server, misc, hud, visuals, edit, profiles,
friends, settings, settings_sm, search, close, reset, chevron_l, chevron_r, camera, check, warn, info, error, plus,
and the older keyboard, map, mods, schematic, star, wardrobe, waypoint. New glyphs go in `scripts/draw-nav-icons.py`.

## Central behaviour (no screen changes needed)

- `Theme`'s shape methods draw through the shader (exact corners at GUI 1–8, Gaussian shadows, 1-device-px hairlines).
- Modern theme neutrals are the Obsidian ramp; accent text (`Theme.RED_BRIGHT`) is red 400 `#FF5566`.
- Vanilla `ButtonWidget`s on Evade screens are painted as kit Secondary buttons in the smooth font; other vanilla
  pressable widgets get the kit background (`mixin/ButtonWidgetKitMixin`, `PressableWidgetKitMixin`).
- Vanilla text tooltips on Evade screens are kit tooltips (`mixin/TooltipKitMixin`).
- `DrawContext.fillGradient` on Evade screens is dithered (`mixin/GradientKitMixin`).
- Toasts are kit toasts (smooth font, status icon, dedupe).
- Fonts: explicit space advance (≥3 device px) in every role, so words never run together at GUI scale 1; Inter
  Display Bold for DISPLAY / HEADLINE.

## Not done here (for WP2–WP4)

Screen layouts (design units, per-screen redesigns), the MSDF icon atlas, `TitleArt` glows / vignette through
`UiShape` (WP2), the Classic vanilla-bevel skin, a Narratable adapter for kit components, and click handling for toast
action chips.
