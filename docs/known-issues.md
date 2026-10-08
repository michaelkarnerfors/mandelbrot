# Known issues

## Preview sometimes incomplete before refinement starts (slice 3, open)

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
