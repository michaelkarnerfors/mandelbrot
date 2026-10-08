# User guide

How to use the explorer. For building and running it, see the [README](../README.md).

## The window

![The explorer in split view with the Render dialog open](../Screenshot.png)

A top bar with the controls, the fractal in the middle, and a status bar underneath. The status bar shows the coordinate under the pointer, the zoom, the render status, the render queue chip, **Go to…** and the theme button (sun/moon).

## Moving around

- **Left-drag** a rectangle to zoom into it.
- **Right-drag** to pan.
- **Mouse wheel** to zoom at the pointer.
- **Home** shows the whole set. **Back** steps back through your views, including a switch between Mandelbrot and Julia, so a mode change is never a one-way trip.
- **Go to…** centres on exact coordinates. Type Re, Im and zoom, or paste a complex number such as `-0.7436 + 0.1318i` into Re and both fields fill in.

Zoom is limited to roughly 0.01 to 10¹³. The maths is plain double precision, so a very deep zoom turns blocky long before the upper limit.

## Quality: settle at

Moving always draws a quick 1x preview. When you stop, the same view refines on its own at the factor chosen in **Settle at** (1x, 2x, 4x, 9x); moving again cancels the refinement and starts over from a preview. The status bar shows both steps ("Preview → Refining 4x", then "Settled at 4x").

Higher factors look smoother and take longer. 9x on a large window needs well over 1 GB of Java heap; a warning appears next to the control, and if memory runs out the preview stays on screen. Start Java with a larger `-Xmx` to go higher.

## Palettes

The **Palette** button opens a menu of built-in palettes (Classic, Ocean, Ember, Mint, Violet) and your own. Picking one applies it at once.

**New…** (or **New / Edit…** when one of yours is active) opens an editor beside the view. Add, remove and reorder colour stops and set how many shades are blended between them. Every change recolours the view immediately, but nothing is kept until **Apply**; **Revert**, or closing the editor, puts back the last applied palette. Give the palette a name and **Create** (or **Overwrite**) to keep it. Your own palettes can be deleted from the menu, after a confirmation.

## Bookmarks

**Bookmarks** opens a grid of saved places, each with a thumbnail, a name and where it is.

- **Add view** bookmarks what is on screen (fractal, Julia constant, position, zoom, palette) and lets you name it at once.
- Click a card to go there.
- Hover a card to rename it, export it as a JSON file or delete it.
- Filter by name with the field on top.
- **Import** reads bookmark files, view files from older versions, and PNGs exported by this app (the view is embedded in them).

## Julia sets

The **Mandelbrot / Julia** control switches fractal. Switching to Julia takes the constant c from the point the Mandelbrot view is centred on. Switching back centres the Mandelbrot view on c at the zoom the Mandelbrot side last had (the whole set if there is none). If that was not what you wanted, **Back** undoes it.

### The c chip and presets

In Julia mode the constant is shown as a chip in the top bar. Click it for your presets: a small picture of each set, a name and the value, with a check on the one in use.

- Click a row to use it.
- Hover to rename (inline) or delete (after a confirmation).
- Drag the dotted handle to reorder.
- **Save current c** keeps the constant on screen.

Presets start as five classics and are yours to change.

### Split view

In Julia mode, the split button next to the fractal control shows the Mandelbrot set beside the Julia set.

- The Julia pane header says **Locked (double-click to unlock)** or **Following (click to lock)**. It starts locked at the c in use.
- **Press or drag** on the Mandelbrot pane to put c under the pointer and lock it. The Julia set redraws at 1x while you drag and refines when you let go.
- **Double-click** the Mandelbrot pane to unlock: c then tracks the pointer until you click.
- The wheel zooms and the right button pans in either pane. A crosshair marks c on the Mandelbrot set.
- **Copy c** in the header copies the constant to the clipboard.
- The status-bar hint changes with the pane under the pointer.

Choosing Mandelbrot in the fractal control (or opening a Mandelbrot bookmark) closes the split. The split is not remembered between runs; a bookmark saved in Julia mode brings you back, and the split button reopens the picker.

## Rendering to a file

**Render…** adds a picture to a queue instead of blocking the window.

In the dialog you choose:
- the **size**: window size and multiples of it, or presets from 1024 px wide up to 8K in 4:3, 16:9 and 21:9;
- the **quality** (samples per pixel along each axis, 1x to 9x);
- the **location**: Re, Im and zoom, prefilled from the view and editable;
- the **file** and folder.

**Sync to current view** takes the view, palette and picture from the main window again. It is enabled only when the dialog differs from the window: you edited the location, or the window has moved on since the dialog opened.

When you add the job, the view, palette and settings are frozen, and the picture frames the same area as the window. Jobs are drawn one at a time at low priority, so the live view stays usable. Rendering to a file that already exists asks before replacing it.

The queue chip in the status bar summarises the queue and opens it: progress and time left, cancel or remove, and for finished pictures **Open** and **Show in folder**. A failed job (out of memory at 9x, say) offers a lighter retry. Memory use is bounded by the output image size, not by the supersampled size.

Pictures go to `~/mandelbrot/renders` unless you choose another folder. Jobs are kept in memory only: quitting asks first if any are unfinished.

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
