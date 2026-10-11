# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

An interactive Mandelbrot/Julia explorer in Java 25 + Swing (FlatLaf look and feel, Jackson for JSON). Single Maven module, package `se.karnerfors.mandelbrot`.

## Build and run

```sh
mvn package                      # or ./mvnw package / .\mvnw.cmd package; builds the shaded target/mandelbrot.jar
java -jar target/mandelbrot.jar  # no arguments: opens the window
java -jar target/mandelbrot.jar seahorse.json -o out.png --size 7680x4320 --samples 3   # arguments: headless render
java -jar target/mandelbrot.jar --help
```

`app/Main` is the jar's entry point: arguments go to `MandelbrotCli`, no arguments open `MandelbrotApp`. Both can also be run directly from an IDE.

Useful system properties (put before `-jar`):
- `-Dmandelbrot.home=<dir>`: per-user data directory (default `~/mandelbrot`). Use a scratch directory when experimenting so the real `state.json`, `bookmarks.json`, `palettes.json`, `julia-presets.json` and `renders/` are left alone.
- `-Dmandelbrot.theme=dark|light`
- `-Dmandelbrot.adaptiveThreshold=0`: refine every pixel (uniform super-sampling), for comparisons.

There are **no automated tests** and no linter. Verification so far has been by hand and with throwaway headless scripts kept outside the repo. `Renderer.computeGrid` (single-threaded) is kept as the bit-exact reference for `computeGridParallel`.

## Working agreement (from docs/roadmap.md)

- Work is done in slices, each on its own git branch with a reviewable diff, stacked on the previous one. Michael (product owner) tests in Eclipse and asks for the commit message and commit; don't commit unasked.
- The authoritative build (`mvn clean install`) is Michael's; Claude type-checks and may drive the UI headlessly.
- When a slice lands, `docs/roadmap.md` gets a row in its table, and open problems go in `docs/known-issues.md`. `docs/ux-brief.md` is the design intent (the image is the interface, nothing blocks, the app remembers; keyboard navigation and sharing are deliberately out of scope).
- Prose, comments and docs use British spelling ("colour") and plain, short sentences. Existing identifiers (`Colorizer`, `colorFor`) keep their spelling.

## Architecture

### Two-stage rendering
1. **Compute**: an escape-time `RenderAlgorithm` (enum: `MANDELBROT`, `JULIA`) fills an `IterationGrid` (flat primitive arrays; disjoint tiles are written from several threads without locking).
2. **Colour**: a `Palette` maps the grid to pixels. Because the stages are separate, palette changes recolour cached grids without recomputing the fractal.

All coordinate maths goes through `math/Viewport` (center, zoom, size, pixel ↔ complex, with sub-pixel offsets). Precision is double only; `Viewport` is the seam where a deep-zoom engine could go later.

### Adaptive super-sampling (`render/AdaptiveSampler`)
Every pixel gets one base sample. `mask()` marks pixels that differ from a neighbour (colour above the threshold, inside vs outside the set, or a jump of more than a palette segment), widened by one pixel. `refine()` computes the n×n samples (centred on the pixel, 1×1 to 8×8) only for marked pixels, and reuses an existing `Refinement` at the same factor (a "top-up"). `colorize()` averages the samples with a gamma-approximated blend. `MARGIN` is the extra ring of base cells a region needs. Note: README still says `Colorizer` box-filters supersampled output. That is outdated. `Colorizer` now only colours 1-sample grids (previews, thumbnails, live recolour).

There are two consumers of the sampler with different memory strategies:
- **`ui/FractalPanel`** (live view): a 1× preview pass rendered as 24-px tiles that are painted as they land, then a refinement pass at the chosen factor once the view is at rest. It keeps `lastGrid` plus one `Refinement` per tile, so factor and palette changes top up instead of redrawing. A `volatile renderGeneration` counter is checked per tile and per pass so stale work is dropped. Interactions repaint immediately with an affine transform of the last image while the background render catches up. Tile pixels reach the screen via `invokeLater` from pool threads; see the open preview race in `docs/known-issues.md`.
- **`render/ImageExporter`** (export, CLI and render queue): allocates only per-tile grids (tile plus margin) and colours them straight into the output image, so memory is bounded by the output size.

`RenderQueue`/`RenderJob` run exports one at a time at low priority, copying all settings in when a job is added. The queue lives only in memory.

### "A place" and persistence (`io/`)
`MandelbrotConfig` (center, zoom, palette, fractal + Julia c, deliberately no width/height) is the one representation of a place. It is used by bookmarks, session state, view files, and the `tEXt` chunk embedded in exported PNGs (`MandelbrotConfigIO`). That is why the CLI and bookmark import accept `.json`, `bookmarks.json` or app-made `.png`. Every store (`SessionStore`, `BookmarkStore`, `PaletteLibrary`, `JuliaPresetStore`) follows the same pattern: tolerant load that never throws (invalid entries skipped, missing file means defaults), and atomic write (temp file + rename). `SessionRecorder` debounces writes of `state.json` and flushes on close.

### App wiring (`app/`, `ui/`)
`MandelbrotApp` builds the window and connects `FractalPanel` listeners (viewport, render status, palette) to `TopBar`, `StatusBar`, popovers and the session recorder. `JuliaSplit` adds a second, 1× Mandelbrot `FractalPanel` beside the main canvas. In split mode the **main canvas is always the Julia canvas**, so bookmarks, the render queue and the session work unchanged. UI components are custom-painted and read every colour from `Theme.color(Token)` at paint time (light/dark switch is just a repaint); bundled IBM Plex fonts are in `src/main/resources/fonts`. Stores, the queue and listeners are used from the Swing event thread only.

Default render settings (max iterations, escape radius, supersample factor) are in `RenderSettings.defaults()`. The CLI frames the same area as the Render dialog: a config's zoom is relative to a window (default 1100×700, `--window`).
