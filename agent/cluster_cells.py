"""
Cluster-based 3D cell segmentation — vectorized, robust I/O.
"""

import sys, csv, base64, time
import numpy as np
from sklearn.cluster import DBSCAN
from scipy.spatial import ConvexHull, Delaunay
from matplotlib.path import Path as MplPath
from pathlib import Path

try:
    from .ij import imagej_command as _imagej_command
except ImportError:
    from ij import imagej_command as _imagej_command

AGENT_DIR = Path(__file__).parent
TMP_DIR = AGENT_DIR / ".tmp"
TMP_DIR.mkdir(exist_ok=True)

PX_SIZE = 0.284
Z_STEP = 1.0
W, H, NZ = 1024, 1024, 13
SINGLE_R = 5


def tcp(cmd, timeout=120):
    return _imagej_command(cmd, timeout=timeout)


def macro(code, timeout=120):
    return tcp({"command": "execute_macro", "code": code}, timeout=timeout)


def cap(name):
    r = tcp({"command": "capture_image", "maxSize": 1024})
    if r.get("ok"):
        (TMP_DIR / f"{name}.png").write_bytes(base64.b64decode(r["result"]["image"]))


def load_csv():
    rows = []
    with open(TMP_DIR / "stats_work.csv", 'r') as f:
        for row in csv.DictReader(f, delimiter='\t'):
            rows.append([float(row['X']), float(row['Y']), float(row['Z']),
                         float(row['Nb of obj. voxels'])])
    a = np.array(rows)
    return a[:, :3], a[:, 3]


def do_dbscan(pts, eps=8.0, ms=2):
    scaled = pts * [PX_SIZE, PX_SIZE, Z_STEP]
    db = DBSCAN(eps=eps, min_samples=ms).fit(scaled)
    labels = db.labels_.copy()
    n_raw = len(set(labels) - {-1})
    noise = np.sum(labels == -1)
    print(f"DBSCAN: {n_raw} clusters, {noise} noise")

    next_id = labels.max() + 1 if n_raw > 0 else 0
    for i in np.where(labels == -1)[0]:
        labels[i] = next_id
        next_id += 1

    nc = next_id
    sizes = np.bincount(labels)
    multi = np.sum(sizes > 1)
    single = np.sum(sizes == 1)
    print(f"Total: {nc} cells ({multi} multi, {single} single)")
    return labels, nc


def fill_mask(centroids, labels, nc, use_alpha=False, alpha_val=0.08):
    t0 = time.time()
    mask = np.zeros((NZ, H, W), dtype=np.uint16)

    # Disk template
    r = SINGLE_R
    dy, dx = np.mgrid[-r:r+1, -r:r+1]
    disk_mask = (dx**2 + dy**2) <= r**2
    disk_dy, disk_dx = np.where(disk_mask)
    disk_dy -= r
    disk_dx -= r

    for cid in range(nc):
        members = centroids[labels == cid]
        n = len(members)
        z0 = max(0, int(members[:, 2].min()))
        z1 = min(NZ - 1, int(members[:, 2].max()))
        lab = cid + 1

        if n == 1:
            cx, cy = int(members[0, 0]), int(members[0, 1])
            ys = cy + disk_dy
            xs = cx + disk_dx
            v = (ys >= 0) & (ys < H) & (xs >= 0) & (xs < W)
            ys, xs = ys[v], xs[v]
            for z in range(z0, z1 + 1):
                f = mask[z, ys, xs] == 0
                mask[z, ys[f], xs[f]] = lab
            continue

        pts2d = members[:, :2]
        if n == 2:
            _capsule(mask, pts2d, z0, z1, lab)
            continue

        path = None
        if use_alpha:
            path = _alpha_path(pts2d, alpha_val)
        if path is None:
            try:
                hull = ConvexHull(pts2d)
                path = MplPath(pts2d[hull.vertices])
            except:
                continue

        x0 = max(0, int(pts2d[:, 0].min()) - 2)
        x1i = min(W - 1, int(pts2d[:, 0].max()) + 2)
        y0 = max(0, int(pts2d[:, 1].min()) - 2)
        y1i = min(H - 1, int(pts2d[:, 1].max()) + 2)

        yy, xx = np.mgrid[y0:y1i+1, x0:x1i+1]
        grid = np.column_stack([xx.ravel(), yy.ravel()]).astype(float)
        inside = path.contains_points(grid).reshape(yy.shape)

        for z in range(z0, z1 + 1):
            reg = mask[z, y0:y1i+1, x0:x1i+1]
            f = (reg == 0) & inside
            reg[f] = lab

    dt = time.time() - t0
    px = np.sum(mask > 0)
    nl = len(np.unique(mask)) - 1
    tag = "alpha" if use_alpha else "hull"
    print(f"  {tag}: {nl} cells, {px:,} px ({dt:.1f}s)")
    return mask


def _capsule(mask, pts2d, z0, z1, lab):
    p1, p2 = pts2d[0], pts2d[1]
    d = p2 - p1
    sl = max(np.linalg.norm(d), 1)
    dn = d / sl
    r = SINGLE_R
    x0 = max(0, int(min(p1[0], p2[0])) - r - 1)
    x1 = min(W - 1, int(max(p1[0], p2[0])) + r + 1)
    y0 = max(0, int(min(p1[1], p2[1])) - r - 1)
    y1 = min(H - 1, int(max(p1[1], p2[1])) + r + 1)
    yy, xx = np.mgrid[y0:y1+1, x0:x1+1]
    vx, vy = xx - p1[0], yy - p1[1]
    proj = np.clip(vx * dn[0] + vy * dn[1], 0, sl)
    d2 = (xx - p1[0] - proj*dn[0])**2 + (yy - p1[1] - proj*dn[1])**2
    ins = d2 <= r**2
    for z in range(z0, z1 + 1):
        reg = mask[z, y0:y1+1, x0:x1+1]
        f = (reg == 0) & ins
        reg[f] = lab


def _alpha_path(pts2d, alpha):
    if len(pts2d) < 3:
        return None
    try:
        tri = Delaunay(pts2d)
    except:
        return None
    edges = set()
    for sx in tri.simplices:
        p = pts2d[sx]
        a = np.linalg.norm(p[0]-p[1])
        b = np.linalg.norm(p[1]-p[2])
        c = np.linalg.norm(p[2]-p[0])
        s = (a+b+c)/2
        a2 = s*(s-a)*(s-b)*(s-c)
        if a2 <= 0: continue
        cr = (a*b*c)/(4*np.sqrt(a2))
        if cr < 1.0/alpha:
            for i,j in [(0,1),(1,2),(2,0)]:
                e = tuple(sorted([sx[i],sx[j]]))
                edges.symmetric_difference_update({e})
    if len(edges) < 3:
        return None
    adj = {}
    for i,j in edges:
        adj.setdefault(i,[]).append(j)
        adj.setdefault(j,[]).append(i)
    st = next(iter(edges))[0]
    poly = [st]
    vis = {st}
    cur = st
    while True:
        nxt = next((n for n in adj.get(cur,[]) if n not in vis), None)
        if nxt is None: break
        poly.append(nxt)
        vis.add(nxt)
        cur = nxt
    return MplPath(pts2d[poly]) if len(poly) >= 3 else None


def save_and_import(stack, title):
    """Save as ImageJ-compatible TIFF via tifffile, open in ImageJ."""
    import tifffile
    tif_path = str(TMP_DIR / f"{title}.tif")
    tifffile.imwrite(tif_path, stack.astype(np.uint16), imagej=True)
    tcp({"command": "open_image", "path": tif_path})
    macro(f'rename("{title}");')
    print(f"  Opened '{title}'")


def overlay(mask_title, label):
    macro(f"""
        selectImage("{mask_title}");
        run("Duplicate...", "title={label}_ol duplicate");
        setThreshold(1, 65535);
        run("Convert to Mask", "background=Dark black");
        run("Outline", "stack");
        selectImage("GFAP.CK1.lif - hAPP14Week8_RH_SCN2 - C=2");
        run("Duplicate...", "title={label}_m duplicate");
        run("8-bit");
        run("Merge Channels...", "c2={label}_ol c6={label}_m create");
        rename("{label}_overlay");
    """)
    cap(f"{label}_overlay")
    print(f"  {label}_overlay done")


def main():
    centroids, volumes = load_csv()
    print(f"{len(centroids)} puncta")

    labels, nc = do_dbscan(centroids, eps=8.0, ms=2)

    print("\nConvex hull:")
    hull = fill_mask(centroids, labels, nc, use_alpha=False)

    print("\nAlpha shape:")
    alpha = fill_mask(centroids, labels, nc, use_alpha=True, alpha_val=0.08)

    print("\nImporting to ImageJ...")
    save_and_import(hull, "hull_cells")
    save_and_import(alpha, "alpha_cells")

    print("\nOverlays...")
    overlay("hull_cells", "hull")
    overlay("alpha_cells", "alpha")

    hp = np.sum(hull > 0)
    ap = np.sum(alpha > 0)
    print(f"\n{'='*50}")
    print(f"Hull:  {hp:,} px   Alpha: {ap:,} px")
    print(f"Ratio: {hp/max(ap,1):.2f}x")
    print(f"{'='*50}")


if __name__ == "__main__":
    main()
