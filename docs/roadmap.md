# Mandelbrot / Julia Explorer: Roadmap

Last updated 2026-10-08, after the Diligence phase (cleanup, README and user guide, command-line renderer). Detail on open problems lives in the repo's `docs/known-issues.md`; the design intent is in `docs/ux-brief.md`.

## How we work
Michael owns the product and the code. Claude acts as UX/UI designer and implementer: one slice at a time, each on its own git branch with a reviewable diff, stacked (each branch starts from the last commit). Michael tests in Eclipse, then asks for the commit message and commit. Claude only type-checks and drives the UI headlessly (stub FlatLaf, xvfb, Robot); the real build (`mvn clean install`) is Michael's. Main is at the end of the stack.

## What exists (package `se.karnerfors.mandelbrot`, Java 25, Maven, Jackson, FlatLaf)

| Slice | What |
|---|---|
| 1 Shell | Themed (light/dark) top bar, status bar, custom-painted controls, Go to |
| 2 Session memory | Reopens where you left off (`state.json`) |
| 3 Settle-at supersampling | 1x preview, then refinement at 1x/2x/4x/9x once the view is at rest |
| 4 Palettes | Built-in and own palettes, non-modal editor with live recolour (`palettes.json`) |
| 5 Bookmarks | Thumbnail grid, rename, export/import, filter (`bookmarks.json`); popup-dismiss and stop-refinement-on-move fixes |
| 6 Render queue | One-at-a-time low-priority renders to `~/mandelbrot/renders`, status-bar chip and popover |
| 7a Julia presets | c chip with preset popover (thumbnails, rename, delete, reorder, save current c), `julia-presets.json` |
| 7b Julia split explorer | Mandelbrot pane beside the Julia pane; press/drag sets c and locks, double-click unlocks and c follows the pointer; Julia redraws at 1x while dragging and refines on release |
| Follow-ups | Palette identity remembered across runs, palette menu survives delete, default canvas 1100 x 700 |
| Diligence cleanup | "Sync to current view" in the Render dialog, confirm before deleting a preset, status-bar hint follows the pane, README split into a front page and `docs/user-guide.md` |
| Command-line renderer | `MandelbrotCli` renders from a bookmark, view file or app PNG, with overrides, `--silent` and exit codes for batch use |
| Runnable jar | `mvn package` builds a self-contained `target/mandelbrot.jar`; `Main` opens the window without arguments and runs `MandelbrotCli` with them. The CLI demo is retired: an input is required |
| Supersample steps | Super-sampling (top bar, formerly "Settle at"), Render and `--samples` offer 1×1, 2×2, 3×3, 4×4 (was 1x/2x/4x/9x, where 9x meant 81 samples per pixel); saved 9 becomes 4×4 |
| Adaptive super-sampling | Only pixels that differ from a neighbour (colour above 12/255, inside vs outside the set, or a jump of more than a palette segment; mask widened by one pixel) get the n×n samples, now centred on the pixel. The window refines from the preview's samples and keeps them plus the extra ones per tile; factor changes and palette changes top up instead of redrawing. Steps now 1×1 to 8×8 (saved 9 becomes 8×8). Status chip tooltip shows the share refined; estimates and memory notes use it. 1.2× to 8× faster at 4×4, visually identical |
| Palette phase shift | Arrows beside the editor strip (hold to repeat, Shift moves a segment) and dragging the strip rotate the palette by whole gradient entries; double-click resets. The shift is an edit like any other (live preview, Apply, Revert, dirty state), rescales when stops or shades change, is saved with the library, bookmarks and PNG view (omitted when 0), and has `--shift N` in the CLI |
| Fractal toggles | The split button is gone: Mandelbrot and Julia are toggles (`ToggleSegmentedControl`), at least one on, both on is the split. Going between them always passes through the split, where a pane grows or shrinks in place: Julia off lets the Mandelbrot pane take the window as it is (it used to centre on c) |
| Max iterations | A status-bar chip beside the zoom opens a popover with a logarithmic slider (10 to 100000, redraws while dragging) and a digits-only field for an exact value; they follow each other, the field applies on Enter, on leaving it and on closing. Saved as `maxIterations` in bookmarks, session, view files and PNG metadata (missing reads as 1000, out of range is clamped); the render queue and the CLI use it (the CLI also has `--iterations N`, rejected outside 10 to 100000; the Render dialog has an Iterations field on the super-sampling row for a picture-only limit, which drops the time estimate while it differs from the window's); the Mandelbrot pane in the split follows |

Per-user files live in `~/mandelbrot/` (override with `-Dmandelbrot.home`): `state.json`, `palettes.json`, `bookmarks.json`, `julia-presets.json`, `renders/`. All follow the same pattern: tolerant load, atomic write, `MandelbrotConfig` as the shared representation of "a place".

## Decisions that still hold
- Double precision only. The coordinate and iteration math sits behind `Viewport` so a deep-zoom engine (perturbation / arbitrary precision) could replace it later.
- Rendering is tiled on a thread pool with a generation counter that drops stale tiles. Compute (`IterationGrid`) is separate from colouring (`Colorizer`), so palette edits recolour from cached data.
- Super-sampling is adaptive (`AdaptiveSampler`). Benchmarked against uniform sampling: the threshold barely matters between 6 and 20, widening the mask cuts missed pixels about tenfold, and what limits quality is the samples per refined pixel, not the mask. `-Dmandelbrot.adaptiveThreshold=0` refines every pixel, for comparisons. A palette top-up keeps every pixel refined so far (the union), so a view can be slightly better than a fresh render, never worse.
- Exports run one at a time at low priority; the heaviest quality stays selectable with a warning; live recolour uses one sample per pixel.
- No keyboard navigation and no sharing features for now (deliberately deferred).
- Go to stays in the status bar plus a modal.
- The zoom floor is 100 pixels per unit for anything the user does: the wheel stops there (and never pulls up a view already below it), Go to, the Render dialog and `--zoom` ask for at least 100. `Viewport` itself still accepts down to 0.01, because the Julia preset thumbnails (zoom about 25) and a narrow split pane's home view fit the set at less than 100; saved views below 100 open as saved. The Render dialog shows the zoom to three digits (plain to five digits, `1.23E9` beyond) and uses the exact zoom until the field is edited.
- There is no direct switch between Mandelbrot and Julia: both toggles on is the split, and you pass through it. Switching Julia on keeps the Mandelbrot view in its pane (c is its centre); switching Julia off lets the Mandelbrot pane take the window as it is. Switching Mandelbrot on or off beside Julia leaves the Julia view's centre and zoom alone. A pane grows or shrinks in place instead of one fractal replacing the other. Back restores positions only (it also takes the panel's current size), not the fractal or the split.
- The split view is not remembered between runs: a bookmark saved as Julia brings you back, and switching Mandelbrot on reopens the picker. The first time in a run it is centred on c at the remembered Mandelbrot zoom (the whole set if none).
- The Render dialog follows the Julia canvas while split (checked: feels right). "Sync to view", an outlined button on the Re/Im/Zoom row, is enabled only when the dialog differs from the live window.
- In split mode the main canvas is always the Julia canvas, so bookmarks, the render queue and the session keep working unchanged; the Mandelbrot pane is a second, 1x canvas.

## Outstanding
- **Preview sometimes incomplete before refinement** (slice 3). Not reproduced; left for another session.
- **Super-sampling ideas, measured before adopting:** a second adaptive level (refine to n, then to 2n only where a pixel's own samples still disagree) for near-8×8 quality at less time and memory; jittered sample positions (seeded per pixel, so renders stay repeatable) to turn residual moiré into noise.
- **Automated tests.** The repo has none; the throwaway checks used so far are not in it. Planned as its own session, starting with the stores (`BookmarkStore`, `PaletteLibrary`, `JuliaPresetStore`) and `SessionState` loading.
- Smaller items are tracked in `docs/known-issues.md`.

## Possible later work
Deep-zoom engine; keyboard navigation; sharing; including the Mandelbrot pane's view in Julia bookmarks.
