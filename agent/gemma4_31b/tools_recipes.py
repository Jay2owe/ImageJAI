"""Phase 9 tools — offer and save a reusable YAML recipe.

Exposes two Ollama tools:

- offer_recipe_save: opens the save flow with a single yes/no
  question. Called by loop.py when the user sends a positive
  signal or types /save-recipe. Speaks the question to the user.
- save_recipe: drafts the YAML, writes it to Fiji.app/ImageJAI/recipes
  when launched by the plugin (falling back to agent/recipes), and
  returns the absolute path. Must only be called after the user
  has explicitly approved the draft in chat.

The hard rule: every literal number stays image_specific: true
unless the user named it in the `promote` list. The agent never
promotes a parameter on its own.
"""

from __future__ import annotations

from . import harvest_recipe
from .registry import tool


@tool
def offer_recipe_save() -> str:
    """Ask the user whether the current workflow should be saved as a reusable recipe.

    Returns the prompt text the agent speaks to the user. The
    user answers yes or no; on yes the agent collects a short
    display name and a one-line description, drafts the YAML
    with draft_recipe, shows it in chat for edits, and calls
    save_recipe once the user approves.
    """
    return (
        "Want to save this workflow as a reusable recipe? (yes/no)\n"
        "If yes, please tell me:\n"
        "  1. A short display name (e.g. 'Blob counting on fluorescence').\n"
        "  2. A one-line description of what the workflow does.\n"
        "I will draft the YAML and show it to you before writing anything.\n"
        "Every literal number stays marked image_specific: true unless you\n"
        "explicitly name the ones to promote to reusable."
    )


@tool
def save_recipe(name: str, description: str, promote: list[str]) -> str:
    """Write the current workflow to the configured recipe folder.

    Must only be called after the user has approved the drafted
    YAML. Use offer_recipe_save first to open the flow and
    collect a display name and description from the user.

    Args:
        name: Short display name, used to derive the file slug.
        description: One-line description of what the workflow does.
        promote: Parameter names the user approved to flip from
            image_specific:true to image_specific:false. Pass an
            empty list to keep every number image-specific.
    """
    recipe = harvest_recipe.draft_recipe(name, description, promote)
    steps = recipe.get("steps") if isinstance(recipe, dict) else None
    if not isinstance(steps, list) or not steps:
        return (
            "ERROR: no successful workflow steps were found in the current session. "
            "Finish one clean run first, then save the recipe."
        )
    path = harvest_recipe.save_recipe_file(recipe)
    return "Saved recipe to {}".format(path)
