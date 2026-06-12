"""
Approach #10: DBSCAN clustering + hull expansion + raw intensity threshold.

1. Load dense puncta centroids (min 20 vox)
2. DBSCAN clustering (eps=6 um)
3. Split oversized clusters
4. Per cluster: convex hull → dilate by ~5 um → intersect with raw image threshold
5. Save mask, create overlay
"""

import numpy as np, csv, time, sys
import tifffile
from sklearn.cluster import DBSCAN, KMeans
from scipy.spatial import ConvexHull
from scipy.ndimage import binary_dilation, binary_erosion, generate_binary_structure
from matplotlib.path import Path as MplPath
from pathlib import Path
from PIL import Image
import socket, struct, json, base64

AGENT_DIR = Path(__file__).parent
TMP = AGENT_DIR / ".tmp"
W, H, NZ = 1024, 1024, 13
PX = 0.284  # um/px
ZS = 1.0    # um/slice
EXPAND_UM = 5.0  # expand hull by this many microns
EXPAND_PX = int(EXPAND_UM / PX)  # ~18 pixels
SINGLE_R = 5  # radius for single-punctum cells
CELL_DIAM_UM = 15.0


def tcp(cmd, timeout=120):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect(("localhost", 7746))
    d = json.dumps(cmd).encode()
    s.sendall(struct.pack(">I", len(d)) + d)
    b = b""
    while len(b) < 4: b += s.recv(4 - len(b))
    n = struct.unpack(">I", b)[0]
    r = b""
    while len(r) < n: r += s.recv(min(65536, n - len(r)))
    s.close()
    return json.loads(r)


def macro(code):
    return tcp({"command": "execute_macro", "code": code})


def cap(name):
    r = tcp({"command": "capture_image", "maxSize": 1024})
    if r.get("ok"):
        (TMP / f"{name}.png").write_bytes(base64.b64decode(r["result"]["image"]))


def load_csv(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f, delimiter='\t'):
            rows.append([float(r['X']), float(r['Y']), float(r['Z']),
                         float(r['Nb of obj. voxels'])])
    a = np.array(rows)
    return a[:, :3], a[:, 3]


def do_dbscan(pts, eps=6.0, ms=2, max_size=20):
    """DBSCAN + split oversized clusters."""
    scaled = pts * [PX, PX, ZS]
    db = DBSCAN(eps=eps, min_samples=ms).fit(scaled)
    raw = db.labels_.copy()
    n_raw = len(set(raw) - {-1})
    n_noise = np.sum(raw == -1)
    print(f"DBSCAN: {n_raw} clusters, {n_noise} noise")

    labels = np.full(len(pts), -1)
    nxt = 0

    for cid in range(n_raw):
        members = np.where(raw == cid)[0]
        n = len(members)
        if n <= max_size:
            labels[members] = nxt
            nxt += 1
        else:
            # Split with k-means
            cluster_pts = scaled[members]
            extent = cluster_pts.max(axis=0) - cluster_pts.min(axis=0)
            vol = max(extent[0], 1) * max(extent[1], 1)
            k = max(2, int(np.ceil(vol / (np.pi * (CELL_DIAM_UM/2)**2))))
            k = min(k, n // 2)
            km = KMeans(n_clusters=k, random_state=42, n_init=10).fit(cluster_pts)
            for sub in range(k):
                sub_m = members[km.labels_ == sub]
                if len(sub_m) > 0:
                    labels[sub_m] = nxt
                    nxt += 1
            print(f"  Split cluster {cid} ({n} pts) -> {k}")

    # Noise → individual clusters
    for i in np.where(raw == -1)[0]:
        labels[i] = nxt
        nxt += 1

    nc = nxt
    sizes = np.bincount(labels)
    multi = np.sum(sizes > 1)
    single = np.sum(sizes == 1)
    ms_arr = sizes[sizes > 1]
    print(f"Total: {nc} cells ({multi} multi [{np.median(ms_arr):.0f} median pts], {single} single)")
    return labels, nc


def build_expanded_mask(centroids, labels, nc, raw_stack):
    """
    Fast approach using distance transform:
    1. Build tight hull label mask (all clusters at once)
    2. Expand labels outward using scipy distance_transform_edt
    3. Within expanded zone, keep only raw pixels above per-cluster threshold
    """
    from scipy.ndimage import distance_transform_edt
    t0 = time.time()

    # Step 1: Build tight hull labels (2D, one copy for all slices)
    tight_labels = np.zeros((H, W), dtype=np.uint16)
    r = SINGLE_R
    dy, dx = np.mgrid[-r:r+1, -r:r+1]
    dm = (dx**2 + dy**2) <= r**2
    ddy, ddx = np.where(dm)
    ddy -= r; ddx -= r

    cluster_z = {}  # cid -> (z0, z1)
    cluster_thresh = {}  # cid -> intensity threshold

    for cid in range(nc):
        members = centroids[labels == cid]
        n = len(members)
        z0 = max(0, int(members[:, 2].min()))
        z1 = min(NZ - 1, int(members[:, 2].max()))
        cluster_z[cid] = (z0, z1)
        lab = cid + 1

        # Compute per-cluster intensity threshold from raw
        seed_vals = []
        for p in members:
            x, y, z = int(p[0]), int(p[1]), int(p[2])
            if 0 <= z < NZ and 0 <= y < H and 0 <= x < W:
                seed_vals.append(float(raw_stack[z, y, x]))
        if seed_vals:
            cluster_thresh[cid] = max(np.mean(seed_vals) * 0.25, 400)
        else:
            cluster_thresh[cid] = 500

        if n == 1:
            cx, cy = int(members[0, 0]), int(members[0, 1])
            ys, xs = cy + ddy, cx + ddx
            v = (ys >= 0) & (ys < H) & (xs >= 0) & (xs < W)
            free = tight_labels[ys[v], xs[v]] == 0
            tight_labels[ys[v][free], xs[v][free]] = lab
        elif n == 2:
            p1, p2 = members[0, :2], members[1, :2]
            d = p2 - p1; sl = max(np.linalg.norm(d), 1); dn = d / sl
            x0 = max(0, int(min(p1[0], p2[0])) - r - 1)
            x1 = min(W - 1, int(max(p1[0], p2[0])) + r + 1)
            y0 = max(0, int(min(p1[1], p2[1])) - r - 1)
            y1 = min(H - 1, int(max(p1[1], p2[1])) + r + 1)
            yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
            proj = np.clip((xx-p1[0])*dn[0]+(yy-p1[1])*dn[1], 0, sl)
            d2 = (xx-p1[0]-proj*dn[0])**2 + (yy-p1[1]-proj*dn[1])**2
            ins = d2 <= r**2
            reg = tight_labels[y0:y1+1, x0:x1+1]
            reg[(reg == 0) & ins] = lab
        else:
            pts2d = members[:, :2]
            try:
                hull = ConvexHull(pts2d)
                path = MplPath(pts2d[hull.vertices])
            except:
                continue
            x0 = max(0, int(pts2d[:, 0].min()) - 2)
            x1 = min(W - 1, int(pts2d[:, 0].max()) + 2)
            y0 = max(0, int(pts2d[:, 1].min()) - 2)
            y1 = min(H - 1, int(pts2d[:, 1].max()) + 2)
            yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
            grid = np.column_stack([xx.ravel(), yy.ravel()]).astype(float)
            ins = path.contains_points(grid).reshape(yy.shape)
            reg = tight_labels[y0:y1+1, x0:x1+1]
            reg[(reg == 0) & ins] = lab

    print(f"  Tight labels: {len(np.unique(tight_labels))-1} cells, {np.sum(tight_labels>0):,} px ({time.time()-t0:.1f}s)")

    # Step 2: Expand labels using nearest-label assignment within EXPAND_PX
    t1 = time.time()
    bg = tight_labels == 0
    dist, indices = distance_transform_edt(bg, return_indices=True)
    # nearest labeled pixel for each background pixel
    expanded_labels = tight_labels[indices[0], indices[1]]
    # Only keep within expansion distance
    expanded_labels[dist > EXPAND_PX] = 0
    print(f"  Distance transform expansion: {time.time()-t1:.1f}s")

    # Step 3: Build 3D mask with intensity gating
    t2 = time.time()
    mask = np.zeros((NZ, H, W), dtype=np.uint16)

    # Precompute per-label threshold array
    thresh_arr = np.zeros(nc + 1, dtype=np.float32)
    for cid in range(nc):
        thresh_arr[cid + 1] = cluster_thresh[cid]

    for z in range(NZ):
        raw_z = raw_stack[z]
        for cid in range(nc):
            lab = cid + 1
            z0, z1 = cluster_z[cid]
            if z < z0 or z > z1:
                continue
            # Pixels assigned to this label in expanded map
            label_zone = expanded_labels == lab
            thresh = thresh_arr[lab]
            # Keep if above threshold OR in tight hull
            keep = label_zone & ((raw_z >= thresh) | (tight_labels == lab))
            mask[z][keep & (mask[z] == 0)] = lab

    elapsed = time.time() - t0
    n_px = np.sum(mask > 0)
    n_labels = len(np.unique(mask)) - 1
    print(f"Expanded mask: {n_labels} cells, {n_px:,} px ({elapsed:.1f}s)")
    return mask


def _draw_capsule_bool(arr, pts2d, radius):
    """Draw capsule into boolean array."""
    p1, p2 = pts2d[0], pts2d[1]
    d = p2 - p1
    sl = max(np.linalg.norm(d), 1)
    dn = d / sl
    r = radius
    x0 = max(0, int(min(p1[0], p2[0])) - r - 1)
    x1 = min(W - 1, int(max(p1[0], p2[0])) + r + 1)
    y0 = max(0, int(min(p1[1], p2[1])) - r - 1)
    y1 = min(H - 1, int(max(p1[1], p2[1])) + r + 1)
    yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
    proj = np.clip((xx - p1[0]) * dn[0] + (yy - p1[1]) * dn[1], 0, sl)
    d2 = (xx - p1[0] - proj*dn[0])**2 + (yy - p1[1] - proj*dn[1])**2
    arr[y0:y1+1, x0:x1+1] |= (d2 <= r**2)


def get_raw_stack():
    """Load raw mCherry from pre-saved TIFF."""
    print("Loading raw image data...")
    raw_path = TMP / "raw_mcherry.tif"
    if not raw_path.exists():
        print(f"  Missing {raw_path} — save it first via ImageJ")
        sys.exit(1)
    stack = tifffile.imread(str(raw_path))
    print(f"  Raw stack: {stack.shape}, dtype={stack.dtype}, range={stack.min()}-{stack.max()}")
    return stack.astype(np.float32)


def main():
    # Load dense puncta
    pts, vols = load_csv(TMP / "stats_work_min20.csv")
    print(f"{len(pts)} puncta loaded")

    # Cluster
    labels, nc = do_dbscan(pts, eps=6.0, ms=2, max_size=20)

    # Get raw image
    raw = get_raw_stack()

    # Build expanded mask
    expanded = build_expanded_mask(pts, labels, nc, raw)

    # Also build hull-only mask for comparison
    print("\nBuilding hull-only mask for comparison...")
    hull_only = build_hull_only(pts, labels, nc)

    # Save both
    print("\nSaving masks...")
    tifffile.imwrite(str(TMP / "expanded_cells.tif"), expanded, imagej=True)
    tifffile.imwrite(str(TMP / "hull_only_cells.tif"), hull_only, imagej=True)

    # Render Python overlays (no ImageJ needed)
    print("\nRendering overlays...")
    # Use slice 7 of raw stack as mCherry background
    mch_s7 = np.clip(raw[6] / 3000 * 255, 0, 255).astype(np.uint8)

    for mask_arr, name in [(expanded, "expanded"), (hull_only, "hull_only")]:
        m7 = mask_arr[6]
        binary = m7 > 0
        outline = binary & ~binary_erosion(binary)
        filled = binary & ~outline

        # Full image overlay
        rgb = np.zeros((H, W, 3), dtype=np.uint8)
        rgb[:, :, 0] = mch_s7
        rgb[:, :, 1] = np.where(outline, 255, 0)
        rgb[:, :, 2] = np.where(outline, 255, 0)
        Image.fromarray(rgb).save(str(TMP / f"{name}_full.png"))

        # Cropped detail (densest region)
        from scipy.ndimage import uniform_filter
        dens = uniform_filter(binary.astype(float), size=100)
        cy, cx = np.unravel_index(dens.argmax(), dens.shape)
        sz = 250
        x0, y0 = max(0, cx-sz//2), max(0, cy-sz//2)
        crop_rgb = rgb[y0:y0+sz, x0:x0+sz]
        crop_img = Image.fromarray(crop_rgb).resize((500, 500), Image.NEAREST)
        crop_img.save(str(TMP / f"{name}_detail.png"))

    print("  Saved: expanded_full.png, expanded_detail.png, hull_only_full.png, hull_only_detail.png")

    # Summary
    hp = np.sum(hull_only > 0)
    ep = np.sum(expanded > 0)
    hl = len(np.unique(hull_only)) - 1
    el = len(np.unique(expanded)) - 1
    print(f"""
{'='*60}
COMPARISON
{'='*60}
Hull only:     {hl} cells, {hp:,} total pixels ({hp//(hl or 1):,} avg/cell)
Expanded:      {el} cells, {ep:,} total pixels ({ep//(el or 1):,} avg/cell)
Expansion:     {ep/max(hp,1):.1f}x
{'='*60}
""")


def build_hull_only(centroids, labels, nc):
    """Hull-only mask (no expansion) for comparison."""
    mask = np.zeros((NZ, H, W), dtype=np.uint16)
    r = SINGLE_R
    dy, dx = np.mgrid[-r:r+1, -r:r+1]
    dm = (dx**2 + dy**2) <= r**2
    ddy, ddx = np.where(dm)
    ddy -= r; ddx -= r

    for cid in range(nc):
        members = centroids[labels == cid]
        n = len(members)
        z0 = max(0, int(members[:, 2].min()))
        z1 = min(NZ - 1, int(members[:, 2].max()))
        lab = cid + 1

        if n == 1:
            cx, cy = int(members[0, 0]), int(members[0, 1])
            ys, xs = cy + ddy, cx + ddx
            v = (ys >= 0) & (ys < H) & (xs >= 0) & (xs < W)
            ys, xs = ys[v], xs[v]
            for z in range(z0, z1 + 1):
                f = mask[z, ys, xs] == 0
                mask[z, ys[f], xs[f]] = lab
        elif n == 2:
            p1, p2 = members[0, :2], members[1, :2]
            d = p2 - p1
            sl = max(np.linalg.norm(d), 1)
            dn = d / sl
            x0 = max(0, int(min(p1[0], p2[0])) - r - 1)
            x1 = min(W - 1, int(max(p1[0], p2[0])) + r + 1)
            y0 = max(0, int(min(p1[1], p2[1])) - r - 1)
            y1 = min(H - 1, int(max(p1[1], p2[1])) + r + 1)
            yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
            proj = np.clip((xx-p1[0])*dn[0]+(yy-p1[1])*dn[1], 0, sl)
            d2 = (xx-p1[0]-proj*dn[0])**2 + (yy-p1[1]-proj*dn[1])**2
            ins = d2 <= r**2
            for z in range(z0, z1 + 1):
                reg = mask[z, y0:y1+1, x0:x1+1]
                f = (reg == 0) & ins
                reg[f] = lab
        else:
            pts2d = members[:, :2]
            try:
                hull = ConvexHull(pts2d)
                path = MplPath(pts2d[hull.vertices])
            except:
                continue
            x0 = max(0, int(pts2d[:, 0].min()) - 2)
            x1 = min(W - 1, int(pts2d[:, 0].max()) + 2)
            y0 = max(0, int(pts2d[:, 1].min()) - 2)
            y1 = min(H - 1, int(pts2d[:, 1].max()) + 2)
            yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
            grid = np.column_stack([xx.ravel(), yy.ravel()]).astype(float)
            ins = path.contains_points(grid).reshape(yy.shape)
            for z in range(z0, z1 + 1):
                reg = mask[z, y0:y1+1, x0:x1+1]
                f = (reg == 0) & ins
                reg[f] = lab

    px = np.sum(mask > 0)
    nl = len(np.unique(mask)) - 1
    print(f"  Hull-only: {nl} cells, {px:,} px")
    return mask


if __name__ == "__main__":
    main()
