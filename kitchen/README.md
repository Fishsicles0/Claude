# Plumb & Square — kitchen measure, design & estimate

A web app for kitchen and bath remodelers: `index.html` plus plain scripts in `js/`. Serve the folder (or open `index.html`) in any browser; no build step.

- **Measure**: wall lengths, corner diagonals (out-of-square detection with scribe allowance), closure check, and pinned rough-ins (outlets, supply/drain, gas, vents, windows/doors with casing, soffits). Bluetooth laser support for Leica DISTO (BLE) and Bosch GLM (beta) via Web Bluetooth (Chrome/Edge, or Bluefy on iPhone); any laser in keyboard (HID) mode works everywhere.
- **Design**: parametric cabinet and appliance catalog that snaps along walls, islands, auto-fill with stock widths and scribe fillers, undo/redo.
- **Checks**: NKBA aisles, landing areas, work triangle, dishwasher placement, hood clearances, NEC countertop receptacle spacing, 3D collisions, window/door trim, soffit/ceiling, buried utilities.
- **Elevations**: dimensioned wall elevations, island sections, rough-in and cabinet schedules (SVG export), plan CAD export (DXF).
- **3D**: orbit, top and walk views; 10 rendering styles (photo, watercolor, sketch, line, technical, clay, toon, blueprint, x-ray, pastel); material painter; PNG/4K renders; 360° panorama export; glTF (.glb) model import.
- **Bath**: vanities, linen towers, toilets, tubs, tub platforms, showers and mirrors, with IRC/NKBA clearance checks.
- **Docs & Present**: selections, schedules, site photos and spec sheets, branding, and a client presentation exported as one HTML file.
- **Options & library**: save design iterations and compare them; save cabinet runs or islands to a reusable library.
- **AI (inside Claude)**: read a photo of a sketch or floor plan into the room shell; generate a full layout as a new option.
- **Estimate**: live BOM with editable price book, customer proposal, frameless cut list and door/drawer order (CSV).

Projects save in the browser (and to your account when opened as a Claude artifact). Export/import project files as JSON.

## Website

Hosted free on Cloudflare Pages: https://plumb-and-square.pages.dev (project `plumb-and-square`).
To redeploy after changes, from this folder: `npx wrangler pages deploy . --project-name plumb-and-square --branch main`
(`.assetsignore` keeps the README and config out of the upload).

On the public site, projects save in each visitor's browser and exports download as normal files.
The AI features and account sync only appear when the app is opened inside Claude.
