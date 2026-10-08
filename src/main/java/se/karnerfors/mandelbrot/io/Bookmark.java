package se.karnerfors.mandelbrot.io;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A named place in the fractal: the view (centre, zoom), the palette, and which fractal with its Julia
 * constant, plus a small picture of what it looked like. Stored in {@code ~/mandelbrot/bookmarks.json}
 * and exchanged as plain JSON files.
 *
 * @param id        stable identity, so renaming never confuses two bookmarks that happen to share a name
 * @param name      what the user calls it
 * @param created   when it was made, ISO-8601 UTC
 * @param view      where, how it is coloured and which fractal; the same shape as a saved config file
 * @param thumbnail a small JPEG as base64 (see {@link Thumbnails}), or null when there is none
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Bookmark(String id, String name, String created, MandelbrotConfig view, String thumbnail) {

    public Bookmark withName(String newName) {
        return new Bookmark(id, newName, created, view, thumbnail);
    }

    public Bookmark withId(String newId) {
        return new Bookmark(newId, name, created, view, thumbnail);
    }

    public Bookmark withThumbnail(String newThumbnail) {
        return new Bookmark(id, name, created, view, newThumbnail);
    }
}
