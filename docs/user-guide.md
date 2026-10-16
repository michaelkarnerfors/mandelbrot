# User guide

How to use the explorer. For building and running it, see the [README](../README.md).

## The window

![The explorer in split view with the Render dialog open](../Screenshot.png)

A top bar with the controls, the fractal in the middle, and a status bar underneath. The status bar shows the coordinate under the pointer, the zoom, the iteration limit (click to change it), the render status, the render queue chip, **Go to…** and the theme button (sun/moon).

## Moving around

- **Left-drag** a rectangle to zoom into it.
- **Right-drag** to pan.
- **Mouse wheel** to zoom at the pointer.
- **Home** shows the whole set. **Back** steps back through your positions. It does not undo switching between Mandelbrot and Julia.
- **Go to…** centres on exact coordinates. Type Re, Im and zoom, or paste a complex number such as `-0.7436 + 0.1318i` into Re and both fields fill in.

Zoom is in pixels per unit and runs from 100 (the whole set is a small shape in the window; the wheel stops there, and Go to, the Render dialog and `--zoom` ask for at least 100) up to 10¹³. A view that fits a very narrow pane can start below 100, and the wheel does not pull it up. The maths is plain double precision, so a very deep zoom turns blocky long before the upper limit.

## Quality: super-sampling

Moving always draws a quick 1×1 preview (one sample per pixel). When you stop, the same view refines on its own at the quality chosen in **Super-sampling**: 1×1, 2×2, 3×3, 4×4, 6×6 or 8×8, the grid of samples averaged into each pixel, so 3×3 is 9 samples and 8×8 is 64. Moving again cancels the refinement and starts over from a preview. The status bar shows both steps ("Preview → Refining 3×3", then "Settled at 3×3").

The refinement is adaptive: only pixels whose colour differs noticeably from a neighbour's get the extra samples (edges, filaments, tight bands of colour). Smooth areas keep their single sample, because more samples would average to the same colour anyway. Typically 5 to 60% of the pixels are refined, depending on how busy the view is; hover over the status chip once the view has settled to see the share. The picture is practically identical to sampling every pixel, at a fraction of the time.

Higher settings look smoother and take longer: each refined pixel costs as many samples as the grid has, so 8×8 costs about four times as much as 4×4. The difference is clearest in busy areas. Changing the setting on a view that is already drawn just refines it again, without a new preview; going back to 1×1 is instant.

Changing the palette recolours the view from the samples it already has, then refines any pixels the new palette needs that the old one did not (a palette that cycles faster shows more edges). While you edit a palette, the view shows one sample per pixel and returns to full quality when you apply it.

The window keeps every sample so palette changes are quick, which costs memory on busy views at high settings: 8×8 on a large window can need over 1 GB of Java heap. A warning appears next to the control when the chosen setting would take a large share of the heap, and if memory runs out the preview stays on screen. Start Java with a larger `-Xmx` to go higher.

## Quality: max iterations

Each point gets a limited number of steps before it counts as inside the set. More iterations show finer detail near the edge and deep in, and need more of them the further you zoom; fewer draw faster. The **Iterations** chip in the status bar, next to the zoom, shows the limit and opens its editor above it:

- a logarithmic **slider** from 10 to 100000, with the view redrawing as you drag;
- a **field** for an exact whole number. The slider follows what you type; Enter, leaving the field or closing the editor applies it. A number outside 10 to 100000 is moved to the nearest end, and only digits are accepted.

The limit is remembered between runs, and saved with bookmarks and in the view inside rendered pictures (as `maxIterations`), so opening a bookmark or rendering a picture again uses the limit it was made with. The Render dialog has its own iterations field for a picture that should use a different limit from the window. Files from before this setting existed read as 1000. In the split view both panes use the same limit.

## Palettes

The **Palette** button opens a menu of built-in palettes (Classic, Ocean, Ember, Mint, Violet) and your own. Picking one applies it at once.

**New…** (or **New / Edit…** when one of yours is active) opens an editor beside the view. Add, remove and reorder colour stops and set how many shades are blended between them. Every change recolours the view immediately, but nothing is kept until **Apply**; **Revert**, or closing the editor, puts back the last applied palette. Give the palette a name and **Create** (or **Overwrite**) to keep it. Your own palettes can be deleted from the menu, after a confirmation.

**Shifting the colours.** The arrows on either side of the gradient strip move all the colours along the palette, so the same fractal gets a different look without changing the palette itself. Click an arrow to move one shade, hold it to repeat (faster the longer you hold), or Shift-click to move a whole segment. You can also drag the gradient itself sideways; the colours follow the pointer. Double-click the strip to put them back. The strip shows the shift as "Shift 17 / 48" (shades moved out of the total in one cycle), and the stop markers under the strip travel with the colours. A shift is an edit like any other: **Apply** keeps it, **Revert** drops it, and adding a stop or changing the shades keeps the look by scaling the shift to the new length. Palettes you **Create** or **Overwrite** keep their shift, and so do bookmarks and the pictures you render.

## Bookmarks

**Bookmarks** opens a grid of saved places, each with a thumbnail, a name and where it is.

- **Add view** bookmarks what is on screen (fractal, Julia constant, max iterations, position, zoom, palette) and lets you name it at once.
- Click a card to go there.
- Hover a card to rename it, export it as a JSON file or delete it.
- Filter by name with the field on top.
- **Import** reads bookmark files, view files from older versions, and PNGs exported by this app (the view is embedded in them).

## Julia sets

**Mandelbrot** and **Julia** in the top bar switch on and off on their own, and one always stays on. With both on you get the split view (below). So going from one fractal to the other always passes through the split: switch the other one on, then this one off.

- Switching Julia on beside Mandelbrot takes the constant c from the point the Mandelbrot view is centred on. The Mandelbrot pane keeps that view, with a crosshair on c.
- Switching Julia off lets the Mandelbrot pane take the whole window, carrying on from where it was.
- Switching Mandelbrot on or off beside Julia opens or closes the Mandelbrot pane. The Julia view keeps its centre and zoom.

**Back** returns to the previous position, but it does not switch fractals: to undo a switch, switch back.

### The c chip and presets

In Julia mode the constant is shown as a chip in the top bar. Click it for your presets: a small picture of each set, a name and the value, with a check on the one in use.

- Click a row to use it.
- Hover to rename (inline) or delete (after a confirmation).
- Drag the dotted handle to reorder.
- **Save current c** keeps the constant on screen.

Presets start as five classics and are yours to change.

### Split view

With both Mandelbrot and Julia switched on, the Mandelbrot set is shown beside the Julia set.

- The Julia pane header says **Locked (double-click to unlock)** or **Following (click to lock)**. It starts locked at the c in use.
- **Press or drag** on the Mandelbrot pane to put c under the pointer and lock it. The Julia set redraws at 1x while you drag and refines when you let go.
- **Double-click** the Mandelbrot pane to unlock: c then tracks the pointer until you click.
- The wheel zooms and the right button pans in either pane. A crosshair marks c on the Mandelbrot set.
- **Copy c** in the header copies the constant to the clipboard.
- The status-bar hint changes with the pane under the pointer.

Switching either fractal off (or opening a Mandelbrot bookmark) closes the split. The split is not remembered between runs: a bookmark saved in Julia mode brings you back, and switching Mandelbrot on reopens the Mandelbrot pane. The first time in a run, that pane is centred on c at the zoom the Mandelbrot side last had (the whole set if there is none).

## Rendering to a file

**Render…** adds a picture to a queue instead of blocking the window.

In the dialog you choose:
- the **size**: window size and multiples of it, or presets from 1024 px wide up to 8K in 4:3, 16:9 and 21:9;
- the **quality** (the grid of samples behind each pixel, 1×1 to 8×8) and, at the right end of the same row, the **iterations** for this picture. The field starts at the window's limit; a different number, from 10 to 100000, is used for this picture only and is saved in it. The time estimate was measured at the window's limit and time does not scale in proportion to it, so while this field differs from the window's the footer says "time unknown" instead (it returns when they match again, for instance after **Sync to view**);
- the **location**: Re, Im and zoom, prefilled from the view and editable. The zoom is shown to three digits (`233`, `1.23E9`), but the view's exact zoom is used until you change the field; hover over it to see the exact number;
- the **file** and folder.

**Sync to view**, the button at the end of the Re, Im and Zoom row, takes the view, palette and picture from the main window again. It is enabled only when the dialog differs from the window: you edited the location or the iterations, or the window has moved on since the dialog opened.

When you add the job, the view, palette and settings are frozen, and the picture frames the same area as the window. Jobs are drawn one at a time at low priority, so the live view stays usable. Rendering to a file that already exists asks before replacing it.

The queue chip in the status bar summarises the queue and opens it: progress and time left, cancel or remove, and for finished pictures **Open** and **Show in folder**. A failed job (out of memory at 8×8, say) offers a lighter retry. Pictures are super-sampled adaptively like the window, and memory use is bounded by the output image size, not by the number of samples.

Pictures go to `~/mandelbrot/renders` unless you choose another folder. Jobs are kept in memory only: quitting asks first if any are unfinished.

## Rendering from the command line

Given arguments, the jar draws a picture without the window, which suits big renders and scripts. Build it first as described in the [README](../README.md) (`mvn package`), then:

```sh
java -jar target/mandelbrot.jar input [options]
```

Without arguments the jar opens the window instead. JVM options such as `-Xmx` go before `-jar`.

**Input** is where the view comes from:
- a bookmark exported from the Bookmarks popover (`.json`);
- `~/mandelbrot/bookmarks.json` itself: it holds many, so pick one with `--bookmark "Name"` (or its number; without it the CLI lists them);
- a view file saved by the old Save button;
- a PNG made by this app, which carries its view inside, so you can render any picture again at a new size.

| Option | Meaning |
|---|---|
| `-o`, `--output FILE` | Where to write the PNG. Default: the input's name with `.png`. Never overwrites the input. |
| `--size WxH` | Picture size, up to 16384 per side. Default 1920x1080. |
| `--samples N` | Supersampling per axis: 1, 2, 3, 4, 6 or 8 (a 3x3 grid is 9 samples per pixel). Default 2. |
| `--window WxH` | The window size the view's zoom refers to. Default 1100x700, the app's default canvas. The picture frames the same area as that window, like the Render dialog. |
| `--fractal`, `--c` | Override the fractal (`mandelbrot` or `julia`) and the Julia constant (`-0.8,0.156` or `-0.8+0.156i`). |
| `--re`, `--im`, `--zoom` | Override the centre and zoom of the view. The zoom must be at least 100. |
| `--iterations N` | The most iterations a point gets, 10 to 100000. Default: the input's `maxIterations`, or 1000 if it has none. |
| `--shift N` | Move the palette's colours N shades along its cycle (negative moves the other way). |
| `--threads N` | Worker threads. Default: all processors. |
| `-s`, `--silent` | Print nothing on success. Errors still go to stderr. |

It prints progress as it goes (unless `--silent`) and exits with 0 if the picture was written, 1 if it was not (unreadable or missing input, a failed render or write, out of memory), and 2 for a mistake on the command line. That makes it usable in a batch: `for f in *.json; do java -jar mandelbrot.jar "$f" -s --size 3840x2160 || echo "failed: $f"; done`. The picture gets the view embedded, like pictures from the app, so it can be bookmarked or re-rendered later. Memory use is bounded by the picture size, not the supersampled size; if it still runs out, lower `--samples` or start Java with a larger `-Xmx`.

## Good to know

**Your files** live in `~/mandelbrot/` (change with `-Dmandelbrot.home=<dir>`):

| File | What |
|---|---|
| `state.json` | Where you left off: view, fractal and Julia constant, palette, quality, window, Render… choices. Delete it to start fresh. |
| `palettes.json` | Your palettes. Plain JSON, safe to edit or copy. |
| `bookmarks.json` | Your bookmarks. |
| `julia-presets.json` | Your Julia presets. |
| `renders/` | Finished pictures. |

All files are read tolerantly (a damaged one is ignored, not fatal) and written atomically. Two running copies share the same folder, and the last one to write wins.

**Theme.** Light or dark with the button at the right of the status bar, or start dark with `-Dmandelbrot.theme=dark`.

**Time and memory estimates** in the Render dialog are rough, based on how long your last view took to draw.

Known problems and ideas are tracked in [known-issues.md](known-issues.md) and [roadmap.md](roadmap.md).
