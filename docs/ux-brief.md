# Mandelbrot / Julia Explorer: UX/UI Design Brief

For: Claude Design. From: the product owner (Michael), with a UX pass already done in conversation.
Status: design brief, October 2026. Nothing below is implemented yet.

## 1. What this is

A desktop explorer for the Mandelbrot and Julia sets, written in Java (Swing), with multithreaded tiled rendering, supersampled antialiasing, a palette editor and high-resolution PNG export. It is a one-person power tool used for long exploratory sessions: wander, zoom deep, find something beautiful, keep it, export it.

The product owner says today's UI looks like "a programmer added controls along the way". That is accurate: a flat toolbar of 12+ controls at one visual level, modal dialogs for everything, a hard-coded Julia preset list, and Save/Load via a plain file chooser. The task is to make it feel designed, calm and fast, **without** costing screen space. The fractal is the star.

Screenshots of the current state are in the repo root (`Screenshot.png`, `export panel.png`).

## 2. Users and context

- Single expert user, mouse-first, on a Windows desktop (also runs on macOS/Linux). The window is resizable and is typically used large; it must work from roughly 900×600 up to fullscreen.
- Sessions are exploratory and long. The user navigates at 1x supersampling for speed, then looks closer at higher quality.
- Keyboard shortcuts are explicitly **out of scope for now**.
- Sharing is out of scope. Everything is saved as JSON, so files are shareable by hand.

## 3. Design principles

1. **The image is the interface.** Chrome is slim, quiet and fixed. No floating overlays on the canvas (they hide what is being looked at). Popovers appear on demand and dismiss on click-away.
2. **Frequent actions are one click; rare actions are one popover.** Pan, zoom, centre, Home, Back and the settle factor are always visible. Everything else lives behind a grouped control.
3. **Nothing blocks.** No modal dialog should stop the user from navigating. Exports queue; the palette editor is live; bookmarks are one click.
4. **The app remembers.** Reopening restores exactly where the user was. No Save step for the session.
5. **Show that work is happening.** Preview, refining and export states are visible, living and unobtrusive.

## 4. Platform and technical constraints (designs must be buildable)

**Toolkit decision: stay on Swing, add FlatLaf.** The codebase is ~2,700 lines with a custom-painted tiled fractal panel and a separate render pipeline; Swing is not the bottleneck. A JavaFX rewrite would buy animation polish at the cost of re-plumbing everything, so we are not doing it. FlatLaf (one Maven dependency) gives a modern flat look, rounded components, light and dark themes, and good HiDPI support. Java 25, Maven.

So please design within what FlatLaf and Swing do well:

- Flat surfaces, 1px borders, rounded corners (FlatLaf default arc), subtle hover and pressed states. Light **and** dark theme.
- Popovers are borderless popup windows (no blur or backdrop effects, no heavy shadows).
- Animation is limited to simple timer-driven effects (a pulsing dot, a progress arc, a fade). Avoid anything that needs GPU compositing.
- Icons: simple monochrome glyphs/SVG, ideally from a single family, themeable for light and dark. FlatLaf can load SVG icons.
- The canvas is a single custom-painted panel. Split view is two such panels in a split pane.
- Please state sizes in px at 100% scale, and specify spacing on a 4px grid.

## 5. Information architecture

Five groups, left to right in a single slim top bar, plus a status bar:

| Group | Contents |
|---|---|
| **View** | Home, Back |
| **Fractal** | Mandelbrot / Julia switch; Julia c indicator and "Julia from here" |
| **Look** | Palette selector (gradient swatch + name) with Edit… |
| **Quality** | "Settle at" 1x / 2x / 4x / 9x (segmented control) |
| **Library & Output** | Bookmarks (★ popover), Add to render queue |

Status bar, left to right: hint text, cursor Re/Im, zoom, **Go to…** (opens the existing coordinate modal), then at the right end two living indicators: **render stage** and **export queue**.

Wireframe to refine, not to copy:

```
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ ⌂ Home  ◀ Back │ Mandelbrot | Julia ▸ │ ▮▮▮ Palette ▾ │ Settle at 1x 2x 4x 9x │ ★ Bookmarks ▾ │ ⎙ Render… │
├──────────────────────────────────────────────────────────────────────────────────────┤
│                         fractal canvas (optionally split with a Julia pane)            │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ hint · Re/Im under cursor · Zoom · [Go to…]       ◔ Preview → Refining 4x       ◉ 1 render │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

## 6. Features to design, in priority order

The product owner rated how easy each must be, from 1 to 5, with 5 the highest. Higher means more obvious, fewer clicks, more prominent.

### 6.1 Navigation (pan 5, zoom 5, centre 5, exact coordinates 3)

Existing mouse model, to be kept: left-drag draws an aspect-locked box and zooms to it (drags under 4 px are ignored); right-drag pans; wheel zooms at the cursor; left click centres on the point; right click asks for a coordinate. Home resets; Back steps through history.

Design asks:
- The hint text should be unobtrusive and ideally fade after first use.
- Exact coordinates stay in the status bar for reading, and in a modal for entry (**leave as is**). Please design the status-bar "Go to…" affordance and a tidier version of the modal (Re, Im, Zoom fields; paste-friendly; clear validation).
- Back should show a disabled state and (optionally) a history count.

### 6.2 Settle-at supersampling and auto-refine (5)

This is a behaviour change plus a control relabel.

- The control becomes "**Settle at**": the factor the view converges to, not the factor used while moving.
- Any navigation triggers a **1x preview pass**. When that pass completes and no new input has arrived, the pass at the chosen factor starts **immediately** and sharpens the image in place, tile by tile.
- New input cancels the refine and restarts at 1x.
- Setting 1x means "never refine".

Design asks:
- A segmented control (1x 2x 4x 9x) with a tooltip explaining "Settle at".
- A **two-stage status indicator**: *Preview* → *Refining 4x* → idle. It should read at a glance from across the room, using a small progress arc or bar plus text. Specify idle, previewing, refining and cancelled-by-input states.
- Consider a subtle on-canvas cue that the image is still a preview (for example a hairline progress along the canvas edge). Do not use an overlay that covers the image.

### 6.3 Session memory (recall, 2)

State is restored on launch: last view, fractal type, Julia c, active palette, settle factor, window size and position, last export settings. There is no UI beyond making launch feel seamless. Please consider a very brief, non-blocking "Restored your last view" hint. Optional.

### 6.4 Bookmarks (replace Save/Load)

A ★ button opens a popover gallery.

Each bookmark has: name, thumbnail, fractal type, Julia c (if Julia), centre, zoom, and palette (inline, so each bookmark is self-contained).

- **Thumbnail**: roughly 96×64, JPEG at moderate quality, embedded as base64 in the JSON. Estimated ~4 to 7 KB each; this is an estimate that we will verify on real renders. Design the card for that size.
- **Add bookmark** is one click and auto-names (for example by fractal and zoom), with inline rename.
- Per item: open (click), rename, delete (with undo rather than a confirm dialog), **Export JSON**.
- Needs: empty state, long-list scrolling and search or filter once there are many, a visible "current view is already bookmarked" state.
- Importing a bookmark JSON (drag-drop or an Import button) is desirable.
- Loading a bookmark must be undoable via Back.

### 6.5 Palette (edit 3) and palette presets

- A dropdown shows gradient swatches and names. Built-in presets plus the user's own saved ones.
- **Edit…** opens a **non-modal** editor panel (docked popover or small floating window). Current controls: ordered colour stops with colour swatch, move up/down and remove, a "+" to add, and "Shades per segment". Please redesign for **drag to reorder**, click-to-edit colour with an inline picker (HSV/RGB/hex) in place of the stock Swing colour chooser, and a larger gradient preview that is also the editing surface if that works.
- **Live recolour**: while editing, the fractal recolours (debounced) so the user sees the effect. **Apply commits; Cancel or closing reverts.** The UI must make the uncommitted state obvious (for example an "Unsaved changes" tag and a Revert affordance).
- **Save as preset…** names and stores the palette. Presets come from a config file, not the code.

### 6.6 Julia exploration (4)

Current flow: pick "Julia", then choose c from a dropdown of the Mandelbrot centre plus five hard-coded presets (Douady rabbit, Siegel disk, San Marco, Dendrite, Seahorse).

New flow:
- **Julia from here**: one action (button or context action) that switches to Julia using the current Mandelbrot centre as c.
- **Split view** ("New Julia"): Mandelbrot on the left, a live Julia on the right. As the cursor moves over the Mandelbrot pane the Julia pane previews that c (at preview quality). A **click without dragging locks c.** Drags still zoom, so no conflict. Note that today a left click means "centre here"; in split mode a click means "lock c", so the pane header must make the mode explicit.
- Once locked, the Julia pane is fully navigable. A lock icon in its header releases it to follow the cursor again.
- Split pane can be **popped out** into its own window and re-docked.
- **Julia presets** appear in a popover list with the c value, a small thumbnail if cheap, and an in-app editor: add current c, rename, delete, reorder. Presets come from `julia-presets.json`, seeded with the five above.

Please design: split layout, the following/locked states, the c readout (copyable), the cursor feedback while following, and the preset popover.

### 6.7 Render queue (order a high-quality render, 3)

Today: a modal "Render to File" dialog (resolution, supersample, Re/Im/Zoom, output file, progress, Cancel). Exports run synchronously from the user's perspective.

New behaviour:
- The dialog becomes **Add to queue** and is non-modal. It snapshots view, palette and settings at submit, so the user keeps navigating.
- It remembers the last resolution, supersample and output folder, and **suggests a filename** so the user does not have to browse each time. Re/Im/Zoom are still prefilled from the current view and editable.
- Resolution choices: current window size, or presets from 1024 px wide up to 8K in 4:3, 16:9 and 21:9.
- One job runs at a time at lower priority so the live view stays responsive (a decision for the product owner to confirm).
- **Status-bar indicator**: a pulsing/living dot with a count while anything is rendering; idle when empty. **Hover** shows a tooltip summary; **click** opens a popover with per-job rows: thumbnail or name, resolution and factor, progress bar, ETA if available, Cancel, and once done, Open file / Open folder.
- States to cover: queued, running, done, failed, cancelled; and the empty state.

## 7. Cross-cutting UI states to specify

Fractal switch (Mandelbrot vs Julia); Back disabled; palette with uncommitted changes; preview vs refining vs idle; export queued/running/done/failed; empty bookmark and preset lists; first launch (no config yet); light and dark; narrow window width (what collapses first: labels to icons, then groups into overflow).

## 8. Persistence (design implications only)

Everything lives in `~/mandelbrot/` and is split by volatility:

- `state.json`: volatile, restored on launch.
- `bookmarks.json`: bookmarks with embedded thumbnails.
- `julia-presets.json`: seeded with the five defaults.
- `palettes.json`: seeded with the classic red-white-black plus a few built-ins.
- `renders/`: default export folder.

Existing JSON already carries `viewport {real, imaginary, zoom}`, `palette {stops[{r,g,b}], shadesPerSegment}` and `fractal {algorithm, cReal, cImaginary}`; bookmarks extend this.

## 9. Known risks that affect design

- **Memory at 9x.** Live recolour keeps the full iteration grid. At 9x and 1096×700 that is about 62 million cells, over 1 GB. Auto-refine to 9x makes this the normal case, so the 9x option may need a visible caveat, or live recolour during editing may use a one-sample-per-pixel approximation. Design the 9x option so it can carry a "heavy" hint.
- **Exports versus navigation** compete for CPU; the queue UI should make the one-at-a-time behaviour legible.
- **Live recolour smoothness** may mean a coarser preview while dragging a colour and a full-quality pass on release/Apply.

## 10. What we would like back

1. A **component and token sheet**: colours (light and dark), type scale, spacing on a 4px grid, radii, icon set, control heights.
2. **Main window** in light and dark at 1100×700 and at 900×600, showing the new top bar and status bar, in Mandelbrot mode.
3. **Split Julia view**: following state and locked state, plus the preset popover.
4. **Bookmarks popover**: populated and empty.
5. **Palette**: preset dropdown, non-modal editor with uncommitted state.
6. **Settle-at control and the two-stage status indicator**, all states.
7. **Add to queue** panel and the **queue popover** with all job states.
8. **Go to…** modal.
9. A short **motion spec** for the pulsing indicator, the progress arc and the refine state change.

## 11. Implementation order (for context)

(a) session memory, toolbar revamp and auto-refine; (b) palette presets and live recolour; (c) bookmarks; (d) render queue; (e) Julia explorer and preset editor. Designs for (a) are the most urgent.

## 12. Open questions for the product owner

- Docked Julia pane with a pop-out (recommended) versus always a separate window.
- One export at a time at low priority: acceptable?
- Whether 9x should be allowed together with live recolour on large windows.
