# Mandelbrot / Julia Explorer: Roadmap

Last updated 2026-10-08, after the Diligence phase (cleanup, README and user guide, command-line renderer). Detail on open problems lives in the repo's `docs/known-issues.md`; the design intent is in `docs/ux-brief.md`.

## How we work
Michael owns the product and the code. Claude acts as UX/UI designer and implementer: one slice at a time, each on its own git branch with a reviewable diff, stacked (each branch starts from the last commit). Michael tests in Eclipse, then asks for the commit message and commit. Claude only type-checks and drives the UI headlessly (stub FlatLaf, xvfb, Robot); the real build (`mvn clean install`) is Michael's. Main is at the end of the stack.

## What exists (package `se.karnerfors.mandelbrot`, Java 25, Maven, Jackson, FlatLaf)

| Slice | What | Commit |
|---|---|---|
| 1 Shell | Themed (light/dark) top bar, status bar, custom-painted controls, Go to | 7dccbfb |
| 2 Session memory | Reopens where you left off (`state.json`) | 0848a8d |
| 3 Settle-at supersampling | 1x preview, then refinement at 1x/2x/4x/9x once the view is at rest | abe0fb9 |
| 4 Palettes | Built-in and own palettes, non-modal editor with live recolour (`palettes.json`) | eab3583 |
| 5 Bookmarks | Thumbnail grid, rename, export/import, filter (`bookmarks.json`); popup-dismiss and stop-refinement-on-move fixes | aacc027 |
| 6 Render queue | One-at-a-time low-priority renders to `~/mandelbrot/renders`, status-bar chip and popover | cdae33f |
| 7a Julia presets | c chip with preset popover (thumbnails, rename, delete, reorder, save current c), `julia-presets.json` | 185d6cb |
| 7b Julia split explorer | Mandelbrot pane beside the Julia pane; press/drag sets c and locks, double-click unlocks and c follows the pointer; Julia redraws at 1x while dragging and refines on release | 8495ee8 |
| Follow-ups | Palette identity remembered across runs, palette menu survives delete, default canvas 1100 x 700 | 310215c |
| Diligence cleanup | "Sync to current view" in the Render dialog, confirm before deleting a preset, status-bar hint follows the pane, README split into a front page and `docs/user-guide.md` | de0cbb0 |
| Command-line renderer | `MandelbrotCli` renders from a bookmark, view file or app PNG, with overrides, `--silent` and exit codes for batch use | cf1087c |
| Runnable jar | `mvn package` builds a self-contained `target/mandelbrot.jar`; `Main` opens the window without arguments and runs `MandelbrotCli` with them. The CLI demo is retired: an input is required | 53bd1c8 |
| Supersample steps | Super-sampling (top bar, formerly "Settle at"), Render and `--samples` offer 1×1, 2×2, 3×3, 4×4 (was 1x/2x/4x/9x, where 9x meant 81 samples per pixel); saved 9 becomes 4×4 | bfccb80 |
| Adaptive super-sampling | Only pixels that differ from a neighbour (colour above 12/255, inside vs outside the set, or a jump of more than a palette segment; mask widened by one pixel) get the n×n samples, now centred on the pixel. The window refines from the preview's samples and keeps them plus the extra ones per tile; factor changes and palette changes top up instead of redrawing. Steps now 1×1 to 8×8 (saved 9 becomes 8×8). Status chip tooltip shows the share refined; estimates and memory notes use it. 1.2× to 8× faster at 4×4, visually identical | 7fa2112 |
| Palette phase shift | Arrows beside the editor strip (hold to repeat, Shift moves a segment) and dragging the strip rotate the palette by whole gradient entries; double-click resets. The shift is an edit like any other (live preview, Apply, Revert, dirty state), rescales when stops or shades change, is saved with the library, bookmarks and PNG view (omitted when 0), and has `--shift N` in the CLI | 8f39294 |
| Fractal toggles | The split button is gone: Mandelbrot and Julia are toggles (`ToggleSegmentedControl`), at least one on, both on is the split. Going between them always passes through the split, where a pane grows or shrinks in place: Julia off lets the Mandelbrot pane take the window as it is (it used to centre on c) | |

Per-user files live in `~/mandelbrot/` (override with `-Dmandelbrot.home`): `state.json`, `palettes.json`, `bookmarks.json`, `julia-presets.json`, `renders/`. All follow the same pattern: tolerant load, atomic write, `MandelbrotConfig` as the shared representation of "a place".

## Decisions that still hold
- Double precision only. The coordinate and iteration math sits behind `Viewport` so a deep-zoom engine (perturbation / arbitrary precision) could replace it later.
- Rendering is tiled on a thread pool with a generation counter that drops stale tiles. Compute (`IterationGrid`) is separate from colouring (`Colorizer`), so palette edits recolour from cached data.
- Super-sampling is adaptive (`AdaptiveSampler`). Benchmarked against uniform sampling: the threshold barely matters between 6 and 20, widening the mask cuts missed pixels about tenfold, and what limits quality is the samples per refined pixel, not the mask. `-Dmandelbrot.adaptiveThreshold=0` refines every pixel, for comparisons. A palette top-up keeps every pixel refined so far (the union), so a view can be slightly better than a fresh render, never worse.
- Exports run one at a time at low priority; the heaviest quality stays selectable with a warning; live recolour uses one sample per pixel.
- No keyboard navigation and no sharing features for now (deliberately deferred).
- Go to stays in the status bar plus a modal.
- There is no direct switch between Mandelbrot and Julia: both toggles on is the split, and you pass through it. Switching Julia on keeps the Mandelbrot view in its pane (c is its centre); switching Julia off lets the Mandelbrot pane take the window as it is. A pane grows or shrinks in place instead of one fractal replacing the other.
- The split view is not remembered between runs: a bookmark saved as Julia brings you back, and switching Mandelbrot on reopens the picker. The first time in a run it is centred on c at the remembered Mandelbrot zoom (the whole set if none).
- The Render dialog follows the Julia canvas while split (checked: feels right). "Sync to current view" is enabled only when the dialog differs from the live window.
- In split mode the main canvas is always the Julia canvas, so bookmarks, the render queue and the session keep working unchanged; the Mandelbrot pane is a second, 1x canvas.

## Outstanding
- **Preview sometimes incomplete before refinement** (slice 3). Not reproduced; left for another session.
- **Super-sampling ideas, measured before adopting:** a second adaptive level (refine to n, then to 2n only where a pixel's own samples still disagree) for near-8×8 quality at less time and memory; jittered sample positions (seeded per pixel, so renders stay repeatable) to turn residual moiré into noise.
- **Automated tests.** The repo has none; the throwaway checks used so far are not in it. Planned as its own session, starting with the stores (`BookmarkStore`, `PaletteLibrary`, `JuliaPresetStore`) and `SessionState` loading.
- Smaller items are tracked in `docs/known-issues.md`.

## Possible later work
Deep-zoom engine; keyboard navigation; sharing; including the Mandelbrot pane's view in Julia bookmarks.
