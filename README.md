# Mandelbrot

An interactive Mandelbrot set explorer written in Java (Swing), with multithreaded tiled rendering, supersampled antialiasing, a live palette editor, and high-resolution export to PNG.

![Mandelbrot explorer showing a deep zoom rendered at 4x supersampling](Screenshot.png)

## Features

- **Interactive navigation**: left-drag to zoom to a region, right-drag to pan, mouse wheel to zoom at the cursor. **Home** resets the view and **Back** steps through your zoom history.
- **Parallel rendering**: the image is split into tiles and rendered on a fixed thread pool sized to your CPU. Tiles appear as they finish, and the status bar shows progress and the last render time.
- **Supersampling**: 1x, 2x, 4x, or 9x antialiasing from the toolbar. Samples are averaged in gamma-approximated space to match the palette blending.
- **Palette editor** (**Palette…**): add, remove, and reorder color stops, and set how many shades are interpolated between stops. The preview updates immediately; the fractal recolors on **Apply** or **Done**, and **Cancel** reverts.
- **Render to file** (**Render…**): export a PNG at the current window size or at presets from 1024 px wide up to 8K (4:3, 16:9, and 21:9), with any supersample factor. Center (Re/Im) and zoom are prefilled from the current view and can be edited. Exports run in the background with a progress bar and Cancel. Memory use is bounded by the output image size, not by the supersampled size.
- **Status bar**: the complex coordinate under the cursor and the current zoom level.

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
