# ImageJAI Recipes

Standardized analysis workflows for the ImageJAI agent. Each YAML file
describes a complete, reproducible image analysis pipeline.

## How to use recipes

The agent reads these recipes when a user requests an analysis. The recipe
provides the macro code, parameter defaults, decision points, and known
issues so the agent can execute reliably without trial-and-error.

### From the agent

```python
import yaml
with open("recipes/cell_counting.yaml") as f:
    recipe = yaml.safe_load(f)
# recipe["steps"] has the ordered macro code
# recipe["parameters"] has tunable defaults
# recipe["known_issues"] has pitfalls to avoid
```

### Recipe file location

All recipes live in `agent/recipes/` as `.yaml` files.

## Schema

```yaml
name: Human-readable name
id: snake_case_unique_id
description: What this analysis does and when to use it
domain: cell_biology | neuroscience | histology | fluorescence | tracking | 3d | publication | preprocessing | measurement
difficulty: beginner | intermediate | advanced

preconditions:
  image_type: [8-bit, 16-bit, 32-bit, RGB]  # accepted bit depths
  min_channels: 1                             # minimum channel count
  needs_stack: false                          # true if z-stack required
  needs_calibration: false                    # true if pixel size must be set
  notes: "Any additional requirements"

parameters:
  - name: parameter_name
    label: "Human label"
    type: numeric | string | choice | boolean
    default: value
    range: [min, max]       # for numeric
    options: [a, b, c]      # for choice
    description: "What this controls"

steps:
  - id: 1
    description: "What this step does"
    macro: |
      run("Command...", "args");
    decision_point: false       # true if step needs conditional logic
    decision_logic: ""          # when to choose different paths
    capture_after: true         # agent should visually inspect after this step
    validate: ""                # validation check after execution

outputs:
  - type: results_table | image | roi_set | measurement
    description: "What this produces"

known_issues:
  - condition: "When this happens"
    symptom: "What goes wrong"
    fix: "How to fix it"

tags: [keyword1, keyword2, keyword3]
```

## Field reference

### `domain` values
| Value | Description |
|-------|-------------|
| `cell_biology` | Cell counting, morphology, proliferation |
| `neuroscience` | Neurite analysis, brain regions, electrophysiology |
| `histology` | Tissue sections, staining quantification |
| `fluorescence` | Fluorescence intensity, colocalization |
| `tracking` | Particle/cell tracking over time |
| `3d` | Z-stack analysis, 3D rendering |
| `publication` | Figure preparation, montages, scale bars |
| `preprocessing` | Denoising, background correction, channel ops |
| `measurement` | Quantification, line profiles, distances |

### `difficulty` levels
| Level | Meaning |
|-------|---------|
| `beginner` | Single command or short pipeline, minimal decisions |
| `intermediate` | Multiple steps, some parameter tuning needed |
| `advanced` | Complex pipeline, decision points, domain expertise needed |

### Step fields
- **`decision_point`**: If true, the agent must evaluate a condition before
  proceeding. The `decision_logic` field explains what to check and which
  path to take.
- **`capture_after`**: If true, the agent should run `python ij.py capture`
  and visually inspect the result before continuing.
- **`validate`**: A human-readable check the agent should perform (e.g.,
  "particle count should be between 10 and 5000").

## Customizing recipes for your lab

Use `train_agent.py` to profile your lab's images and discover which
parameters work best for your data. This makes recipe execution more
reliable by pre-tuning defaults.

```bash
# From the agent/ directory:
python train_agent.py /path/to/your/images              # train on your images
python train_agent.py /path/to/your/images --domain neuro  # specify domain
python train_agent.py --profile                          # view current profile
python train_agent.py --reset                            # clear and retrain
```

### What training does

1. **Characterizes your images** — bit depth, channels, z-stacks, calibration,
   SNR, modality (fluorescence, brightfield, confocal, etc.)
2. **Tests threshold methods** — tries Otsu, Triangle, Li, Huang, MaxEntropy,
   Yen, Default and records which works best for your data
3. **Tests segmentation approaches** — classical threshold+watershed, StarDist,
   3D Objects Counter (for z-stacks)
4. **Tunes parameters** — varies blur sigma and particle size filters to find
   the most consistent settings across your images
5. **Writes findings** — saves `lab_profile.json` (machine-readable) and
   appends lab-specific tips to `learnings.md`

### Using the profile

Other tools (like `recipe_search.py`) can load `lab_profile.json` to filter
recipes by your image characteristics and pre-fill optimal parameters:

```python
import json
with open("lab_profile.json") as f:
    profile = json.load(f)
# profile["best_threshold"]    — e.g. "Otsu"
# profile["optimal_params"]    — {"sigma": 1.5, "min_size": 80}
# profile["modality"]          — e.g. "confocal_fluorescence"
# profile["common_channels"]   — e.g. 2
```

### Tips

- Train on 10-20 representative images — more is not necessarily better
- Include different conditions/treatments if your images vary
- Re-train after changing microscope settings or switching to a new experiment
- The `--domain` flag helps the agent choose domain-specific recipes
- Training takes 5-15 minutes depending on image count and complexity

## Adding new recipes

1. Copy an existing recipe as a template
2. Fill in all fields — do not leave placeholders
3. Test the macro code in Fiji to verify it works
4. Add real known issues, not hypothetical ones
5. Use descriptive tags for searchability
