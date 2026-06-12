from ij import imagej_command
import json
import re

def get_count_from_log():
    resp = imagej_command({"command": "get_log"})
    log_text = resp.get("result", "")
    matches = re.findall(r"RESULT_COUNT: (\d+)", log_text)
    if matches:
        return int(matches[-1])
    return None

def run_experiment():
    # Clear the log first
    imagej_command({"command": "execute_macro", "code": "print('\\\\Clear');"})

    combinations = [
        ("Gaussian Blur (sigma=1)", "run('Gaussian Blur...', 'sigma=1');"),
        ("Gaussian Blur (sigma=2)", "run('Gaussian Blur...', 'sigma=2');"),
        ("Gaussian Blur (sigma=5)", "run('Gaussian Blur...', 'sigma=5');"),
        ("Median Filter (radius=1)", "run('Median...', 'radius=1');"),
        ("Median Filter (radius=2)", "run('Median...', 'radius=2');"),
        ("Median Filter (radius=5)", "run('Median...', 'radius=5');"),
        ("Mean Filter (radius=1)", "run('Mean...', 'radius=1');"),
        ("Mean Filter (radius=2)", "run('Mean...', 'radius=2');"),
        ("Mean Filter (radius=5)", "run('Mean...', 'radius=5');"),
        ("Gaussian(1) + Median(1)", "run('Gaussian Blur...', 'sigma=1'); run('Median...', 'radius=1');")
    ]

    results = []

    for name, macro_part in combinations:
        print(f"Running: {name}...")
        full_macro = f"""
        print('\\\\Clear');
        run('Blobs (25K)');
        {macro_part}
        setAutoThreshold('Otsu');
        run('Convert to Mask');
        run('Analyze Particles...', 'summarize');
        count = Table.get('Count', 0, 'Summary');
        print('RESULT_COUNT: ' + count);
        close();
        if (isOpen('Summary')) {{
            selectWindow('Summary');
            run('Close');
        }}
        """
        imagej_command({"command": "execute_macro", "code": full_macro})
        count = get_count_from_log()
        results.append({"Filter": name, "Count": count})
        print(f"  Count: {count}")

    # Baseline Original for accuracy comparison
    print("Running: Original (Baseline)...")
    full_macro = """
    print('\\\\Clear');
    run('Blobs (25K)');
    setAutoThreshold('Otsu');
    run('Convert to Mask');
    run('Analyze Particles...', 'summarize');
    count = Table.get('Count', 0, 'Summary');
    print('RESULT_COUNT: ' + count);
    close();
    if (isOpen('Summary')) {
        selectWindow('Summary');
        run('Close');
    }
    """
    imagej_command({"command": "execute_macro", "code": full_macro})
    orig_count = get_count_from_log()

    print("\n--- RESULTS ---")
    print("| Filter Combination | Count | Difference from Original |")
    print("| --- | --- | --- |")

    sum_counts = 0
    for res in results:
        diff = res['Count'] - orig_count if res['Count'] is not None else "N/A"
        print(f"| {res['Filter']} | {res['Count']} | {diff} |")
        sum_counts += res['Count']

    mean_count = sum_counts / len(results)
    print(f"\nMean count (across combinations): {mean_count:.2f}")
    print(f"Original count (baseline): {orig_count}")

if __name__ == "__main__":
    run_experiment()
