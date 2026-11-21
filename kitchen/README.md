# Plumb & Square — kitchen measure, design & estimate

A single-file web app (`index.html`) for kitchen remodelers. Open it in any browser; no build step.

- **Measure**: wall lengths, corner diagonals (out-of-square detection with scribe allowance), closure check, and pinned rough-ins (outlets, supply/drain, gas, vents, windows/doors with casing, soffits). Bluetooth laser support for Leica DISTO (BLE) and Bosch GLM (beta) via Web Bluetooth (Chrome/Edge, or Bluefy on iPhone); any laser in keyboard (HID) mode works everywhere.
- **Design**: parametric cabinet and appliance catalog that snaps along walls, islands, auto-fill with stock widths and scribe fillers, undo/redo.
- **Checks**: NKBA aisles, landing areas, work triangle, dishwasher placement, hood clearances, NEC countertop receptacle spacing, 3D collisions, window/door trim, soffit/ceiling, buried utilities.
- **Elevations**: dimensioned wall elevations with rough-in and cabinet schedules (SVG export).
- **3D**: orbit, top and first-person walk views with finish and lighting options.
- **Estimate**: live BOM with editable price book, customer proposal, frameless cut list and door/drawer order (CSV).

Projects save in the browser (and to your account when opened as a Claude artifact). Export/import project files as JSON.
