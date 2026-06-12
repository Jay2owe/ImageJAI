import json
import subprocess
import os

def run_macro(macro_code):
    cmd = ["python", "ij.py", "macro", macro_code]
    result = subprocess.run(cmd, capture_output=True, text=True)
    try:
        return json.loads(result.stdout)
    except:
        return {"ok": False, "error": result.stdout}

def get_summary():
    cmd = ["python", "ij.py", "results"]
    result = subprocess.run(cmd, capture_output=True, text=True)
    lines = result.stdout.strip().split('\n')
    if len(lines) <= 1:
        return 0, 0

    # Simple parsing of Area,Circ.,AR,Round,Solidity
    # Header: Area,Circ.,AR,Round,Solidity
    count = len(lines) - 1
    total_circ = 0
    for line in lines[1:]:
        parts = line.split(',')
        if len(parts) >= 2:
            total_circ += float(parts[1])

    return count, total_circ / count if count > 0 else 0

pipelines = [
    ("Baseline (Raw)", "run('Blobs (25K)'); setAutoThreshold('Li'); run('Convert to Mask');"),
    ("Gaussian Blur (1.0)", "run('Blobs (25K)'); run('Gaussian Blur...', 'sigma=1'); setAutoThreshold('Li'); run('Convert to Mask');"),
    ("Median Filter (2.0)", "run('Blobs (25K)'); run('Median...', 'radius=2'); setAutoThreshold('Li'); run('Convert to Mask');"),
    ("Background Subtraction (50)", "run('Blobs (25K)'); run('Subtract Background...', 'rolling=50'); setAutoThreshold('Li'); run('Convert to Mask');"),
    ("Unsharp Mask", "run('Blobs (25K)'); run('Unsharp Mask...', 'radius=1 mask=0.60'); setAutoThreshold('Li'); run('Convert to Mask');"),
    ("Despeckle", "run('Blobs (25K)'); run('Despeckle'); setAutoThreshold('Li'); run('Convert to Mask');"),
    ("Watershed (Split)", "run('Blobs (25K)'); setAutoThreshold('Li'); run('Convert to Mask'); run('Watershed');"),
    ("Morphological Open", "run('Blobs (25K)'); setAutoThreshold('Li'); run('Convert to Mask'); run('Open');"),
    ("Morphological Close", "run('Blobs (25K)'); setAutoThreshold('Li'); run('Convert to Mask'); run('Close');"),
    ("Blur + Watershed", "run('Blobs (25K)'); run('Gaussian Blur...', 'sigma=1'); setAutoThreshold('Li'); run('Convert to Mask'); run('Watershed');")
]

results = []

print(f"{'Pipeline':<30} | {'Count':<6} | {'Mean Circ.':<10}")
print("-" * 55)

for name, steps in pipelines:
    # Run macro and analyze particles
    macro = steps + " run('Analyze Particles...', 'display clear summarize');"
    run_macro(macro)
    count, avg_circ = get_summary()
    results.append((name, count, avg_circ))
    print(f"{name:<30} | {count:<6} | {avg_circ:<10.3f}")

# Find "best" - Let's say high circularity while maintaining a reasonable count (60-75)
# Blobs usually has ~65 objects.
best = max(results, key=lambda x: x[2] if 60 <= x[1] <= 80 else 0)
print("\nBest Performing Pipeline:")
print(f"Name: {best[0]}")
print(f"Object Count: {best[1]}")
print(f"Mean Circularity: {best[2]:.3f}")
