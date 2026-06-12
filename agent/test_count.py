from ij import imagej_command
import json

def test_count():
    macro = """
    run('Blobs (25K)');
    setAutoThreshold('Otsu');
    run('Convert to Mask');
    run('Analyze Particles...', 'summarize');
    count = Table.get('Count', 0, 'Summary');
    print('RESULT_COUNT: ' + count);
    """
    resp = imagej_command({"command": "execute_macro", "code": macro})
    print("Macro Response:", json.dumps(resp, indent=2))

    resp = imagej_command({"command": "get_log"})
    print("Log:", resp.get("result", ""))

if __name__ == "__main__":
    test_count()
