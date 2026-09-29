/**
 * Remove black borders after drift correction.
 *
 * This is a recipe template, not a standalone script. run_recipe.py validates
 * and substitutes the three configuration values before sending it to Fiji.
 */

import ij.IJ

int detectChannel = ${detect_channel}
int scanStep = ${scan_step}
int safetyMargin = ${safety_margin}

def imp = IJ.getImage()
if (imp == null) {
    throw new IllegalStateException("No image open")
}

def w = imp.getWidth()
def h = imp.getHeight()
def nc = imp.getNChannels()
def nf = imp.getNFrames()
def stack = imp.getStack()

if (nf <= 1) {
    throw new IllegalArgumentException("Image has only one frame")
}
if (detectChannel < 1 || detectChannel > nc) {
    throw new IllegalArgumentException("detect_channel is outside the image channel range")
}
if (scanStep < 1 || safetyMargin < 0) {
    throw new IllegalArgumentException("scan_step and safety_margin are invalid")
}

IJ.log("=== Remove Drift Borders ===")
IJ.log("Image: " + w + "x" + h + ", " + nc + " channels, " + nf + " frames")

def offsets = []
def minW = w
def minH = h

for (int f = 1; f <= nf; f++) {
    int idx = imp.getStackIndex(detectChannel, 1, f)
    def ip = stack.getProcessor(idx)

    int left = 0
    boolean leftFound = false
    for (int x = 0; x < w / 2 && !leftFound; x++) {
        for (int y = 0; y < h; y += scanStep) {
            if (ip.get(x, y) > 0) {
                left = x
                leftFound = true
                break
            }
        }
    }

    int top = 0
    boolean topFound = false
    for (int y = 0; y < h / 2 && !topFound; y++) {
        for (int x = 0; x < w; x += scanStep) {
            if (ip.get(x, y) > 0) {
                top = y
                topFound = true
                break
            }
        }
    }

    int right = w - 1
    boolean rightFound = false
    for (int x = w - 1; x > w / 2 && !rightFound; x--) {
        for (int y = 0; y < h; y += scanStep) {
            if (ip.get(x, y) > 0) {
                right = x
                rightFound = true
                break
            }
        }
    }

    int bottom = h - 1
    boolean bottomFound = false
    for (int y = h - 1; y > h / 2 && !bottomFound; y--) {
        for (int x = 0; x < w; x += scanStep) {
            if (ip.get(x, y) > 0) {
                bottom = y
                bottomFound = true
                break
            }
        }
    }

    if (!leftFound || !topFound || !rightFound || !bottomFound) {
        throw new IllegalStateException("Could not find non-zero content on frame " + f)
    }
    int contentWidth = right - left + 1
    int contentHeight = bottom - top + 1
    minW = Math.min(minW, contentWidth)
    minH = Math.min(minH, contentHeight)
    offsets.add([left, top])
}

minW -= 2 * safetyMargin
minH -= 2 * safetyMargin
if (minW <= 0 || minH <= 0) {
    throw new IllegalStateException("Safety margin leaves an empty crop")
}

for (int f = 1; f <= nf; f++) {
    int offsetX = offsets[f - 1][0] + safetyMargin
    int offsetY = offsets[f - 1][1] + safetyMargin
    for (int c = 1; c <= nc; c++) {
        int idx = imp.getStackIndex(c, 1, f)
        def ip = stack.getProcessor(idx)
        def duplicate = ip.duplicate()
        ip.setValue(0)
        ip.fill()
        ip.insert(duplicate, -offsetX, -offsetY)
    }
}

imp.setRoi(0, 0, minW, minH)
IJ.run(imp, "Crop", "")
imp.deleteRoi()
imp.updateAndDraw()
IJ.log("Done: " + imp.getWidth() + "x" + imp.getHeight())
