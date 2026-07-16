#!/usr/bin/env python3
"""
ring_render.py — Find and render 3D ring structures (Ch4 around DAPI)

Workflow:
  1. Open a .lif series via Bio-Formats
  2. Find bright objects in Ch4 (max projection)
  3. Check Ch4/Ch2 autofluorescence ratio for each candidate
  4. Crop, median filter, set strict display ranges, scale up
  5. Render with 3Dscript (combined transparency)
  6. Save AVI + ROI location image to AI_Exports/

Usage:
  python ring_render.py scan                    # find all candidates, rank by Ch4/Ch2 ratio
  python ring_render.py preview 3               # preview candidate #3 (Ch1+Ch4 composite)
  python ring_render.py render 3                # render candidate #3 as 3D AVI
  python ring_render.py render 3 --name "NLGF9" # custom output name
  python ring_render.py render_all              # render top 5 candidates, save all
  python ring_render.py batch series1 series2   # scan + render best from each series

Requires: ij.py, pixels.py, active ImageJ TCP connection, 3Dscript installed
"""

import sys, os, json, time, subprocess, tempfile

# Add agent dir to path
AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AGENT_DIR)

import ij as _ij_client

try:
    from ij_send import send_command  # direct TCP if available
except ImportError:
    pass

def ij(cmd_str):
    """Run ij.py command and return parsed JSON."""
    result = subprocess.run(
        ["python", os.path.join(AGENT_DIR, "ij.py")] + cmd_str.split(maxsplit=1) if " " not in cmd_str else ["python", os.path.join(AGENT_DIR, "ij.py")] + [cmd_str.split()[0]] + [" ".join(cmd_str.split()[1:])],
        capture_output=True, text=True, cwd=AGENT_DIR, timeout=120
    )
    try:
        return json.loads(result.stdout)
    except:
        return {"ok": False, "raw": result.stdout, "err": result.stderr}

def ij_macro(code, timeout=120):
    """Run ImageJ macro code."""
    result = subprocess.run(
        ["python", os.path.join(AGENT_DIR, "ij.py"), "macro", code],
        capture_output=True, text=True, cwd=AGENT_DIR, timeout=timeout
    )
    try:
        return json.loads(result.stdout)
    except:
        return {"ok": False, "raw": result.stdout}


def resolve_output_dir(requested=None, export_root=None):
    """Resolve an output folder strictly beneath the active AI_Exports root."""
    if export_root is None:
        from gemma4_31b import active_image
        export_root = active_image.current_export_folder()
    if not export_root:
        raise RuntimeError(
            "No active on-disk image is available; open an image before rendering"
        )
    root = os.path.realpath(os.path.abspath(export_root))
    candidate = root if requested is None else os.path.realpath(
        os.path.abspath(requested)
    )
    try:
        inside = os.path.commonpath((root, candidate)) == root
    except ValueError:
        inside = False
    if not inside:
        raise ValueError("render output must remain beneath {}".format(root))
    os.makedirs(candidate, exist_ok=True)
    return candidate


def run_batch_animation(animation_path):
    """Run 3Dscript's host-file input through the explicit script capability."""
    script = """
def image = ij.WindowManager.getImage("_rbig")
if (image == null) throw new IllegalStateException("_rbig is not open")
ij.IJ.run(image, "Batch Animation", "animation=[" + %s + "]")
return "started"
""" % json.dumps(os.path.abspath(animation_path).replace("\\", "/"))
    return _ij_client.run_groovy(script, timeout=300)


def save_avi_output(output_path, fps, export_root=None):
    """Save the rendered AVI through the explicit, audited script channel."""
    output_path = os.path.join(
        resolve_output_dir(os.path.dirname(os.path.abspath(output_path)), export_root),
        os.path.basename(output_path),
    )
    options = "compression=JPEG frame={} save=[{}]".format(
        int(fps), os.path.abspath(output_path).replace("\\", "/")
    )
    script = """
def image = ij.WindowManager.getImage("_rbig.avi")
if (image == null) throw new IllegalStateException("_rbig.avi is not open")
ij.IJ.run(image, "AVI... ", %s)
return "saved"
""" % json.dumps(options)
    return _ij_client.run_groovy(script, timeout=120)


def save_image_output(image_title, image_format, output_path, export_root=None):
    """Save one named Fiji image through the explicit script capability."""
    output_path = os.path.join(
        resolve_output_dir(os.path.dirname(os.path.abspath(output_path)), export_root),
        os.path.basename(output_path),
    )
    script = """
def image = ij.WindowManager.getImage(%s)
if (image == null) throw new IllegalStateException("output image is not open")
ij.IJ.saveAs(image, %s, %s)
return "saved"
""" % (
        json.dumps(image_title),
        json.dumps(image_format),
        json.dumps(os.path.abspath(output_path).replace("\\", "/")),
    )
    return _ij_client.run_groovy(script, timeout=120)

def find_cells_ch4():
    """Find bright objects in Ch4 of active image using pixels.py."""
    result = subprocess.run(
        ["python", os.path.join(AGENT_DIR, "pixels.py"), "find_cells"],
        capture_output=True, text=True, cwd=AGENT_DIR, timeout=60
    )
    cells = []
    for line in result.stdout.strip().split("\n"):
        if "pos=" in line:
            # Parse: label=N: pos=(X,Y) area=N mean=N
            parts = line.strip()
            try:
                pos_str = parts.split("pos=(")[1].split(")")[0]
                x, y = [float(v) for v in pos_str.split(",")]
                area = int(parts.split("area=")[1].split()[0])
                mean = float(parts.split("mean=")[1].split()[0]) if "mean=" in parts else 0
                cells.append({"x": x, "y": y, "area": area, "mean": mean})
            except:
                continue
    return cells

def check_af_ratio(candidates, crop_size=50, z_slice=8):
    """Check Ch4/Ch2 autofluorescence ratio for each candidate."""
    # Build macro to check all at once
    positions = []
    for c in candidates:
        positions.append(str(int(c["x"])))
        positions.append(str(int(c["y"])))

    macro = 'positions = newArray(' + ",".join(positions) + ');\n'
    macro += 'results = "";\n'
    macro += 'for (i=0; i<positions.length; i+=2) {\n'
    macro += '    x = positions[i] - ' + str(crop_size // 2) + ';\n'
    macro += '    y = positions[i+1] - ' + str(crop_size // 2) + ';\n'
    macro += '    makeRectangle(x, y, ' + str(crop_size) + ', ' + str(crop_size) + ');\n'
    macro += '    Stack.setPosition(2, ' + str(z_slice) + ', 1);\n'
    macro += '    getRawStatistics(n, m2);\n'
    macro += '    Stack.setPosition(4, ' + str(z_slice) + ', 1);\n'
    macro += '    getRawStatistics(n, m4);\n'
    macro += '    ratio = m4 / m2;\n'
    macro += '    print(positions[i] + "," + positions[i+1] + "," + d2s(m4,1) + "," + d2s(m2,1) + "," + d2s(ratio,3));\n'
    macro += '}\n'

    ij_macro(macro)
    # Read log for results
    log_result = subprocess.run(
        ["python", os.path.join(AGENT_DIR, "ij.py"), "log"],
        capture_output=True, text=True, cwd=AGENT_DIR, timeout=30
    )

    # Parse the ratio results from log
    for line in log_result.stdout.strip().split("\n"):
        parts = line.strip().split(",")
        if len(parts) == 5:
            try:
                cx, cy = float(parts[0]), float(parts[1])
                ch4_mean, ch2_mean, ratio = float(parts[2]), float(parts[3]), float(parts[4])
                # Match to candidate
                for c in candidates:
                    if abs(c["x"] - cx) < 2 and abs(c["y"] - cy) < 2:
                        c["ch4_mean"] = ch4_mean
                        c["ch2_mean"] = ch2_mean
                        c["af_ratio"] = ratio
                        break
            except:
                continue

    return candidates

def scan(top_n=10):
    """Find candidates, rank by Ch4/Ch2 ratio. Active image must be the z-stack."""
    print("Finding bright objects in Ch4...")

    # Max project for cell detection
    ij_macro('run("Z Project...", "projection=[Max Intensity]"); rename("_scan_proj"); run("Make Composite"); Stack.setPosition(4, 1, 1);')
    cells = find_cells_ch4()
    ij_macro('selectWindow("_scan_proj"); close();')

    if not cells:
        print("No bright objects found in Ch4.")
        return []

    # Keep top candidates by area * mean
    cells.sort(key=lambda c: c["area"] * c["mean"], reverse=True)
    candidates = cells[:min(top_n, len(cells))]

    print(f"Found {len(cells)} objects, checking top {len(candidates)} for autofluorescence...")
    candidates = check_af_ratio(candidates)

    # Sort by Ch4/Ch2 ratio (higher = better, less autofluorescence)
    candidates.sort(key=lambda c: c.get("af_ratio", 0), reverse=True)

    print(f"\n{'#':>3} {'Pos':>14} {'Area':>6} {'Ch4':>8} {'Ch2(AF)':>8} {'Ratio':>6} {'Quality':>8}")
    print("-" * 62)
    for i, c in enumerate(candidates):
        ratio = c.get("af_ratio", 0)
        quality = "GOOD" if ratio > 1.5 else ("OK" if ratio > 1.0 else "BAD")
        print(f"{i:>3} ({c['x']:>5.0f},{c['y']:>5.0f}) {c['area']:>6} {c.get('ch4_mean',0):>8.0f} {c.get('ch2_mean',0):>8.0f} {ratio:>6.2f} {quality:>8}")

    return candidates

def preview(candidates, index, crop_size=50):
    """Save a composite preview of a candidate to .tmp/."""
    c = candidates[index]
    x, y = int(c["x"]) - crop_size // 2, int(c["y"]) - crop_size // 2

    ij_macro(f'''
        makeRectangle({x}, {y}, {crop_size}, {crop_size});
        run("Duplicate...", "title=_preview duplicate");
        run("Make Composite");
        Stack.setActiveChannels("1001");
        Stack.setPosition(1, 8, 1); setMinAndMax(2500, 18000);
        Stack.setPosition(4, 8, 1); setMinAndMax(5000, 25000);
        run("Z Project...", "projection=[Max Intensity]");
        rename("_preview_proj");
    ''')

    # Capture to file
    subprocess.run(
        ["python", os.path.join(AGENT_DIR, "ij.py"), "capture", f"candidate_{index}"],
        capture_output=True, text=True, cwd=AGENT_DIR, timeout=30
    )

    ij_macro('selectWindow("_preview_proj"); close(); selectWindow("_preview"); close();')
    print(f"Preview saved to .tmp/candidate_{index}.png")

# === Rendering parameters (the user's preferred style) ===
RENDER_PARAMS = {
    "crop_size": 50,
    "median_radius": 1,         # Median 3D, radius 1 (light, edge-preserving)
    "ring_min": 11000,          # Strict — clips Ch4 background
    "ring_max": 42000,
    "dapi_min": 2500,           # Moderate — shows nuclei well
    "dapi_max": 18000,
    "scale": 15,                # XY upscale (50*15=750 output)
    "ring_lut": "Green",        # AF488 = green
    "dapi_lut": "Blue",
    "frames": 200,
    "fps": 24,
}

def render(candidates, index, output_dir, name=None, params=None):
    """Render a candidate as 3D AVI. Returns output path."""
    output_dir = resolve_output_dir(output_dir)
    p = {**RENDER_PARAMS, **(params or {})}
    c = candidates[index]
    x, y = int(c["x"]) - p["crop_size"] // 2, int(c["y"]) - p["crop_size"] // 2

    if name is None:
        name = f"ring_candidate_{index}"

    print(f"Rendering candidate {index} at ({c['x']:.0f},{c['y']:.0f}), ratio={c.get('af_ratio',0):.2f}...")

    # Write animation file
    anim_path = os.path.join(AGENT_DIR, ".tmp", "ring_anim.txt").replace("\\", "/")
    with open(anim_path, "w") as f:
        f.write(f"At frame 0:\n")
        f.write(f"- change rendering algorithm to combined transparency\n")
        f.write(f"- change all channels' lighting to on\n")
        f.write(f"- change all channels' object light to 1\n")
        f.write(f"- change bounding box visibility to off\n")
        f.write(f"- change scalebar visibility to off\n")
        f.write(f"From frame 0 to frame {p['frames']} rotate by 360 degrees vertically ease-in-out\n")

    # Crop, split, filter, merge, convert, scale, render — all in one macro
    macro = f'''
        makeRectangle({x}, {y}, {p["crop_size"]}, {p["crop_size"]});
        run("Duplicate...", "title=_rcrop duplicate");
        run("Split Channels");
        selectWindow("C2-_rcrop"); close();
        selectWindow("C3-_rcrop"); close();

        selectWindow("C4-_rcrop"); run("Median 3D...", "x={p['median_radius']} y={p['median_radius']} z={p['median_radius']}");
        selectWindow("C1-_rcrop"); run("Median 3D...", "x={p['median_radius']} y={p['median_radius']} z={p['median_radius']}");

        run("Merge Channels...", "c2=[C4-_rcrop] c3=[C1-_rcrop] create");
        rename("_rmerge");
        Stack.setPosition(1, 8, 1); setMinAndMax({p['ring_min']}, {p['ring_max']});
        Stack.setPosition(2, 8, 1); setMinAndMax({p['dapi_min']}, {p['dapi_max']});
        run("8-bit");
        Stack.setPosition(1, 8, 1); run("{p['ring_lut']}");
        Stack.setPosition(2, 8, 1); run("{p['dapi_lut']}");
        run("Scale...", "x={p['scale']} y={p['scale']} z=1.0 interpolation=Bicubic process create title=_rbig");
    '''
    ij_macro(macro)

    # 3Dscript reads an animation file. That host-file access is deliberately
    # sent through run_script, never hidden inside an ImageJ macro argument.
    result = run_batch_animation(anim_path)
    if not result.get("ok") or not result.get("result", {}).get("success", True):
        raise RuntimeError("3Dscript Batch Animation failed: {}".format(result))

    # Save AVI
    avi_path = os.path.join(output_dir, f"{name}_ring_3D.avi").replace("\\", "/")
    saved = save_avi_output(avi_path, p["fps"], output_dir)
    if not saved.get("ok") or not saved.get("result", {}).get("success", True):
        raise RuntimeError("AVI export failed: {}".format(saved))

    # Cleanup
    ij_macro('selectWindow("_rbig.avi"); close(); selectWindow("_rbig"); close(); selectWindow("_rmerge"); close();')

    # Close any 3Dscript dialogs
    subprocess.run(
        ["python", os.path.join(AGENT_DIR, "ij.py"), "close_dialogs"],
        capture_output=True, text=True, cwd=AGENT_DIR, timeout=10
    )

    print(f"  Saved: {avi_path}")
    return avi_path

def save_roi_location(source_title, roi_x, roi_y, crop_size, output_path):
    """Save full image with yellow ROI rectangle."""
    output_root = resolve_output_dir(os.path.dirname(os.path.abspath(output_path)))
    output_path = os.path.join(
        output_root,
        os.path.basename(output_path),
    )
    ij_macro(f'''
        selectWindow("{source_title}");
        run("Z Project...", "projection=[Max Intensity]");
        rename("_roi_ov");
        run("Make Composite");
        Stack.setActiveChannels("1001");
        Stack.setPosition(1, 1, 1); setMinAndMax(2500, 18000);
        Stack.setPosition(4, 1, 1); setMinAndMax(5000, 25000);
        run("Flatten");
        rename("_roi_flat");
        setColor(255, 255, 0);
        drawRect({roi_x}, {roi_y}, {crop_size}, {crop_size});
        drawRect({roi_x-1}, {roi_y-1}, {crop_size+2}, {crop_size+2});
        drawRect({roi_x-2}, {roi_y-2}, {crop_size+4}, {crop_size+4});
        setFont("SansSerif", 18, "bold antialiased");
        drawString("ROI", {roi_x}, {roi_y - 5});
    ''')
    saved = save_image_output("_roi_flat", "PNG", output_path, output_root)
    if not saved.get("ok") or not saved.get("result", {}).get("success", True):
        raise RuntimeError("ROI image export failed: {}".format(saved))
    ij_macro('selectWindow("_roi_flat"); close(); selectWindow("_roi_ov"); close();')
    print(f"  ROI location: {output_path}")

def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return

    cmd = sys.argv[1]

    if cmd == "scan":
        top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 10
        candidates = scan(top_n)
        # Save candidates to .tmp for later use
        with open(os.path.join(AGENT_DIR, ".tmp", "candidates.json"), "w") as f:
            json.dump(candidates, f, indent=2)
        print(f"\nCandidates saved to .tmp/candidates.json")
        print("Use: python ring_render.py preview <#>  to inspect")
        print("Use: python ring_render.py render <#>   to render")

    elif cmd == "preview":
        index = int(sys.argv[2])
        with open(os.path.join(AGENT_DIR, ".tmp", "candidates.json")) as f:
            candidates = json.load(f)
        preview(candidates, index)

    elif cmd == "render":
        index = int(sys.argv[2])
        name = sys.argv[sys.argv.index("--name") + 1] if "--name" in sys.argv else None
        requested = sys.argv[sys.argv.index("--output") + 1] if "--output" in sys.argv else None
        output_dir = resolve_output_dir(requested)

        with open(os.path.join(AGENT_DIR, ".tmp", "candidates.json")) as f:
            candidates = json.load(f)
        render(candidates, index, output_dir, name)

    elif cmd == "render_all":
        top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 5
        requested = sys.argv[sys.argv.index("--output") + 1] if "--output" in sys.argv else None
        output_dir = resolve_output_dir(requested)

        with open(os.path.join(AGENT_DIR, ".tmp", "candidates.json")) as f:
            candidates = json.load(f)

        good = [c for c in candidates if c.get("af_ratio", 0) > 1.5]
        if not good:
            good = [c for c in candidates if c.get("af_ratio", 0) > 1.0]
        to_render = good[:top_n]

        print(f"Rendering top {len(to_render)} candidates (Ch4/Ch2 ratio > 1.5)...\n")
        for i, c in enumerate(to_render):
            orig_idx = candidates.index(c)
            render(candidates, orig_idx, output_dir, f"candidate_{i}")

    else:
        print(f"Unknown command: {cmd}")
        print(__doc__)

if __name__ == "__main__":
    main()
