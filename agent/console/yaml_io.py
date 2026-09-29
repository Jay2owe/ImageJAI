"""Use PyYAML's compiled safe parser when installed; portable fallback stays safe."""
def safe_load(text):
    import yaml
    return yaml.load(text, Loader=getattr(yaml, "CSafeLoader", yaml.SafeLoader))
