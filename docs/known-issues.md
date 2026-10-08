# Known issues

## Preview sometimes incomplete before refinement starts (slice 3, open)

Deferred to a later session by the product owner (2026-10-08).

Reported while testing settle-at supersampling: in some cases the 1x preview was not fully drawn
(missing or blank tiles) when the refinement started. Not reproduced under test yet: the headless
checks (rapid navigation, wheel zoom, palette change mid-refine, 9x out-of-memory) all ended with a
correct image, so it likely needs a particular timing or a real display.

Where to look first (hypotheses, none confirmed):

- Tile pixels reach the preview image through one `SwingUtilities.invokeLater` per tile, posted from
  the render worker threads, while `SwingWorker.done()` decides "the preview is complete" and starts
  the refinement. Anything that lets `done()` act before the last tile has been composited, or lets a
  late tile be dropped by the generation check, would show up as a broken preview.
- Slice 3 starts the refinement from `done()` and from mouse release (`tryStartRefine`), both on the
  event thread. Heavy work on the event thread (colouring, `setPalette`, repaint storms) could change
  the ordering, as suspected.
- `Colorizer` work per tile runs on the pool threads; a render that is cancelled mid-tile may still
  leave a partly filled image on screen if it is committed.

Suggested approach when we return to it: count tiles actually composited and only mark the preview
complete (and start refining) when that equals the tile total; log the sequence of generations,
tiles and stages when the counts disagree; try to reproduce with a slow machine or throttled pool.

## No automated tests (open, planned as its own session)

The repository has no test sources. Everything so far was checked by hand in Eclipse and by throwaway
headless scripts that are not part of the repo. The stores are plain logic and the first candidates:
`BookmarkStore`, `PaletteLibrary`, `JuliaPresetStore` (seeding, tolerant load, atomic write, rename,
delete, reorder) and `SessionState` loading (old files, missing or invalid fields).

## Smaller items

- Duplicate Julia preset names are allowed.
- Two running instances share `~/mandelbrot`; the last one to write a file wins.
- Open and Show in folder were confirmed for `~/mandelbrot/renders` only. Network shares, very long paths
  and unusual characters are unchecked.
- Render time and memory estimates in the Render dialog are rough.
- The render queue is kept in memory only; quitting with unfinished jobs asks first.
