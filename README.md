# Morphometrics Helper for Fiji

A Fiji script for taking manual length and angle measurements across a folder
of images quickly and consistently. You work through the images one at a time;
for each one the helper puts a line or angle on the image for every measurement
you have defined, you drag it into place and click **Record**, and the value is
written straight to a CSV file. Every selection is also saved, so any
measurement can be revisited, checked or redone later.

It was originally written for zebrafish larval morphometrics (craniofacial
cartilage, head and body measurements), but nothing in it is specific to fish:
the views, measurements and scales all come from a plain-text config file, so
it can be used for any set of images you measure by hand.

## What it assumes

- **One folder per batch of images.** The config, results and saved selections
  all live in that folder.
- **Images were taken in a (mostly) repeating sequence of views.** For example,
  each fish photographed ventrally at 4x, then ventrally at 2x, then laterally
  at 2x. Sorted by file name, the images follow that cycle, and the helper uses
  it to predict each image's view. When the sequence breaks (a missed or extra
  shot), you correct that one image and the predictions after it follow on.
- **Each view has a fixed magnification**, so one scale per view is enough.
- **Subjects sit in a similar place in the frame each time.** Measurement start
  positions and zoom are remembered per view, so the more consistent the
  framing, the less adjusting you do.

## Installation

1. Install [Fiji](https://fiji.sc) if you don't already have it.
2. Download [`Morphometrics_Helper.groovy`](https://github.com/timrankin/Fiji-Morphometrics-Helper/raw/main/Morphometrics_Helper.groovy).
3. Copy it into Fiji's `plugins` folder
   ([where to find it](https://imagej.net/imagej-wiki-static/Installing_3rd_party_plugins)).
   On macOS the `plugins` folder is inside the Fiji folder, or inside
   `Fiji.app` on older installs (right-click `Fiji.app` > **Show Package
   Contents**).
4. Restart Fiji, or use **Help > Refresh Menus**.

It then appears as **Plugins > Morphometrics Helper**. You can also open the
file in Fiji's Script Editor and click **Run**.

## Getting started

1. Run **Plugins > Morphometrics Helper** and choose your image folder.
2. If the folder has no `morphometrics_config.txt`, the helper offers to create
   one from a template and opens it for editing. Set your views, their order
   and the measurements for each, save, and click **OK**. A full worked example
   is in [`examples/morphometrics_config.txt`](examples/morphometrics_config.txt).
3. If a view has no scale yet, the **Settings** window opens. Open an image of
   that view, click **Measure scale bar**, draw a line along the scale bar,
   click **Use drawn line**, enter the bar's real length and unit, and **Save**.
   Angles can be recorded without a scale; lengths can't.

## Measuring

The main window lists the images on the left and the current image's
measurements on the right.

- Pick a measurement: its line or angle appears on the image. Drag it into
  place and click **Record**. The helper moves on to the next measurement.
  **Next measurement** after the last one moves to the next image.
- Each image shows its view and progress, e.g. `fish01_a.tif  ventral_4x [3/4]`.
  A `?` means the view is only predicted. Recording a measurement confirms it;
  if the prediction is wrong, change it with the **View** selector first.
- **Clear** removes the selected measurement for this image.
  **Next incomplete** jumps to the next image with measurements left.
  **Filter** and **Hide completed** narrow the image list.
- **Keep this window on top** stops image windows covering the helper. Its
  position and this setting are remembered between sessions.
- **Finish session** (or closing the window) saves everything and closes the
  image.

### Settings

**Settings...** edits, per view:

- **Scale**: the scale bar length in pixels, its real length, and the unit.
  If you change a scale after recording lengths, the helper offers to
  recalculate them from their saved selections.
- **Default start positions**: where each measurement's line or angle first
  appears. Select a measurement, move its selection on the image to where it
  usually belongs, and click **Use selection on image**. Positions are stored
  as fractions of the image size, so they work across image sizes.

Changes are written back to `morphometrics_config.txt` when you click **Save**.

### Zoom memory

Each **Record** also remembers the zoom, the visible part of the image and the
window size, and restores them when you next select that measurement on an
image of the same view. Zoom in on the eye for `eye_diameter` once, and it opens
that way on every following image. Set `zoom_memory = view` for a single zoom per
view, or `zoom_memory = off` to leave the zoom alone.

## Files it creates

All in the image folder:

| File | Contents |
| --- | --- |
| `measurements_long.csv` | One row per measurement: `Folder, Image, View, Structure, Type, Value`. Lengths are in the view's unit, angles in degrees (0–180). |
| `RoiSets/<image>_RoiSet.zip` | The selections behind each value. They open in Fiji's ROI Manager. |
| `image_views.csv` | The confirmed view for each image. |
| `zoom_state.csv` | Remembered zoom and window framing. Delete it to start over. |
| `morphometrics_config.txt` | Your configuration. |

The CSV is written after every **Record**, so nothing is lost if Fiji closes
unexpectedly, and a folder can be picked up again later where you left off.

## Configuration reference

The config is `key = value` lines; `#` starts a comment.

| Key | Meaning |
| --- | --- |
| `views` | Comma-separated view names. |
| `view_cycle` | The order the views repeat in. Defaults to the order of `views`. |
| `view.<view>.scale_distance_px` | Scale bar length in pixels. `0` = not set yet. |
| `view.<view>.scale_known` | Real length of the scale bar (default `1`). |
| `view.<view>.scale_unit` | Unit of that length (default `mm`). |
| `view.<view>.measurements` | `name[:type]` entries separated by `;`. The type is `length` or `angle`; if it's left out, names ending in `_angle` are angles and everything else is a length. |
| `view.<view>.position.<name>` | Start position for a measurement. Set it from the Settings window. |
| `zoom_memory` | `measurement` (default), `view` or `off`. |
| `image_extensions` | Default `.jpg,.jpeg,.tif,.tiff,.png`. |
| `roi_subdir`, `output_long`, `view_assign`, `zoom_state` | Names of the files and folder above. |

A scale key without the `view.<view>.` prefix (e.g. `scale_unit = um`) applies
to every view that doesn't set its own.

## Feedback and bug reports

Please use [GitHub Issues](https://github.com/timrankin/Fiji-Morphometrics-Helper/issues)
for bug reports, questions and feature requests. For a bug, include your Fiji
version, your `morphometrics_config.txt`, and any error from Fiji's Console
(**Window > Console**).
