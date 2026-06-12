import json
from ij import imagej_command

def run_test():
    # Open blobs
    print("Opening Blobs...")
    imagej_command({"command": "execute_macro", "code": "run('Blobs (25K)');"})

    # Process
    print("Processing...")
    macro = """
    setAutoThreshold('Otsu');
    run('Convert to Mask');
    run('Analyze Particles...', 'summarize');
    """
    imagej_command({"command": "execute_macro", "code": macro})

    # Check results
    print("Checking Results...")
    resp = imagej_command({"command": "get_results_table"})
    print(json.dumps(resp, indent=2))

    # Check state context (maybe it has more info)
    # print("Checking State Context...")
    # resp = imagej_command({"command": "get_state_context"})
    # print(json.dumps(resp, indent=2))

if __name__ == "__main__":
    run_test()
