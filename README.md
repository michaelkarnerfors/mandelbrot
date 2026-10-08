# Mandelbrot

An interactive Mandelbrot set explorer written in Java (Swing), with multithreaded tiled rendering, settle-at supersampled antialiasing, a live palette editor, and high-resolution export to PNG.

![Mandelbrot explorer showing a deep zoom rendered at 4x supersampling](Screenshot.png)

## Features

- **Interactive navigation**: left-drag to zoom to a region, right-drag to pan, mouse wheel to zoom at the cursor. **Home** resets the view and **Back** steps through your zoom history.
- **Parallel rendering**: the image is split into tiles and rendered on a fixed thread pool sized to your CPU. Tiles appear as they finish, and the status bar shows progress and the last render time.
- **Settle at** (1x, 2x, 4x, 9x): moving around always draws a quick 1x preview first. When it is on screen and you stop moving, the same view refines on its own at the factor you settled on; moving again cancels the refinement and starts over from a 1x preview. The status bar shows both steps ("Preview → Refining 4x", then "Settled at 4x"). Samples are averaged in gamma-approximated space to match the palette blending. 9x on a large window needs well over 1 GB of Java heap; a warning appears next to the control, and if memory runs out the preview stays on screen (start Java with a larger `-Xmx` to go higher).
- **Palette editor** (**Palette…**): add, remove, and reorder color stops, and set how many shades are interpolated between stops. The preview updates immediately; the fractal recolors on **Apply** or **Done**, and **Cancel** reverts.
- **Render to file** (**Render…**): export a PNG at the current window size or at presets from 1024 px wide up to 8K (4:3, 16:9, and 21:9), with any supersample factor. Center (Re/Im) and zoom are prefilled from the current view and can be edited. Exports run in the background with a progress bar and Cancel. Memory use is bounded by the output image size, not by the supersampled size.
- **Status bar**: the complex coordinate under the cursor, the current zoom level, **Go to…** (centre on exact Re, Im and zoom; pasting `-0.7436 + 0.1318i` into Re fills both), and the render status.
- **Light and dark theme**: switch with the sun/moon button at the right of the status bar, or start dark with `-Dmandelbrot.theme=dark`. The UI uses [FlatLaf](https://www.formdev.com/flatlaf/) and the bundled IBM Plex fonts (SIL Open Font License, see `src/main/resources/fonts/OFL.txt`).
- **Palettes**: the **Palette** button opens a menu of built-in palettes (Classic, Ocean, Ember, Mint, Violet) and your own; picking one applies it at once. **New…** (or **New / Edit…** when one of yours is active) opens a non-modal editor beside the view: every change recolours the view immediately (one sample per pixel, so it keeps up), but nothing is kept until **Apply**; **Revert**, or closing the editor, puts back the last applied palette. Name the palette in the editor and **Create** (or **Overwrite**) keeps it in `~/mandelbrot/palettes.json`, which you can also edit or copy by hand. Your own palettes can be deleted from the menu.
- **Session memory**: the app reopens where you left it: view, fractal and Julia constant, palette, supersample factor, window position and size, and the Render… dialog's resolution, supersample factor and output file. It is kept in `~/mandelbrot/state.json` (move it with `-Dmandelbrot.home=<dir>`); delete the file to start fresh. Rendering to a file that already exists asks before replacing it.
- **Bookmarks**: the **Bookmarks** button (which replaced Save/Load) opens a grid of your saved places, each with a thumbnail, a name and where it is. **Add view** bookmarks what is on screen (fractal, Julia constant, position, zoom and palette) and lets you name it at once; click a card to go there. Hover a card to rename it, export it as a JSON file or delete it. Filter by name with the field on top. **Import** reads bookmark files, view files saved by the old Save button, and PNGs exported by this app. Bookmarks live in `~/mandelbrot/bookmarks.json`.

## Requirements

- JDK 25
- Maven 3.x

## Building and running

```sh
mvn compile
java -cp target/classes se.karnerfors.mandelbrot.app.MandelbrotApp
```

You can also run `MandelbrotApp` directly from your IDE.

### Command-line render

`MandelbrotCli` renders a fixed deep-zoom view at 3840×2160 with the default settings, writes it to `saved.png` in the working directory, and prints the render time:

```sh
java -cp target/classes se.karnerfors.mandelbrot.app.MandelbrotCli
```

## Project layout

```
src/main/java/se/karnerfors/mandelbrot/
├── app/       Entry points: MandelbrotApp (GUI) and MandelbrotCli (headless render)
├── math/      Complex numbers and Viewport (center, zoom, pixel ↔ complex mapping)
├── palette/   Palette: color stops and the gamma-blended gradient built from them
├── render/    Escape-time algorithm, tiled parallel Renderer, IterationGrid,
│              Colorizer (coloring and downsampling), ImageExporter, RenderSettings
└── ui/        FractalPanel (live view), PaletteEditorPanel, ExportPanel
```

Rendering has two stages. First, `Renderer` computes escape-iteration counts into an `IterationGrid`. Then `Colorizer` maps those counts through a `Palette` and box-filters the supersampled result down to the output size. Because the stages are separate, changing the palette recolors the existing grid without recomputing the fractal.

Default render settings (max iterations, escape radius, supersample factor) are in `RenderSettings.defaults()`.

## License

Released under the [MIT License](LICENSE).
