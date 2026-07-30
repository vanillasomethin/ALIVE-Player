# Branding source assets

Drop the two source images here, then run `./tools/generate-icons.sh` from the repo
root to produce every density bucket. Both are gitignored-by-intent exceptions —
commit them, so any checkout can regenerate the assets.

## `alive-player-banner.png` — 16:9, min 1280x720

The full `alive● PLAYER` lockup on ink background. This becomes the **Android TV
banner**, which is the artwork the leanback launcher actually shows for the app on
the TV home row. Aspect must be exactly 16:9 or it will be squashed.

## `alive-player-icon.png` — 1:1 square, min 1024x1024

This becomes the **launcher icon**. It must be *square artwork*, not the wide
lockup: the full `alive● PLAYER` lockup shrunk into a 48dp square is unreadable.
Use the `alive●` mark alone (or just `a●`), centred, on the ink background.

Keep the mark within the middle ~66% of the canvas. Adaptive icons (API 26+) only
guarantee the centre 72dp of a 108dp canvas is visible; launchers mask the rest to
a circle, squircle or rounded square depending on the device, and anything near the
edge gets clipped. The script handles the padding, but art that already runs to the
edge will still lose its extremities to the mask.
