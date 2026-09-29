"""Pseudonymised browse-model tests (S1.34, S2.27, S2.28, S6.13, S6.31-S6.33).

The metadata reader is faked in every scan test, so no image is ever decoded
and the suite does not depend on Pillow being able to read a real file.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import browse


def fake_reader(series):
    """Return a reader that reports ``series`` for every file."""

    def read(_path):
        return [dict(item) for item in series]

    return read


def one_plane(**overrides):
    plane = {
        "metadata": {},
        "format": "TIFF",
        "size_x": 512,
        "size_y": 512,
        "size_z": 1,
        "size_c": 1,
        "size_t": 1,
    }
    plane.update(overrides)
    return plane


# --------------------------------------------------------- S6.13 token shapes


def test_path_token_shape_and_stability(tmp_path):
    token_map = browse.PathTokenMap(salt=b"fixed-salt")
    image = tmp_path / "patient_A_4wk.lif"
    first = token_map.token_for_path(image)
    assert first.startswith("image-")
    assert first.endswith(".lif")
    assert len(first.split("-", 1)[1].split(".")[0]) == browse.PATH_TOKEN_HEX_CHARS
    assert token_map.token_for_path(image) == first
    assert browse.TOKEN_PATTERN.fullmatch(first)


def test_token_never_contains_the_real_name(tmp_path):
    token_map = browse.PathTokenMap()
    token = token_map.token_for_path(tmp_path / "MRN123456_secret.tif")
    assert "MRN123456" not in token and "secret" not in token


def test_series_token_and_reverse_lookup(tmp_path):
    token_map = browse.PathTokenMap()
    image = tmp_path / "stack.lif"
    token = token_map.token_for_series(image, 3)
    assert token.endswith(":3")
    resolved = token_map.resolve(token)
    assert resolved is not None
    assert resolved.series == 3
    assert resolved.path == image.resolve()
    assert token_map.resolve("image-deadbeef.lif") is None
    assert token_map.resolve("  ") is None


def test_text_token_shape():
    token_map = browse.PathTokenMap()
    token = token_map.token_for_sensitive_text("Patient A", "label")
    assert token.startswith("label-")
    assert len(token.split("-", 1)[1]) == browse.TEXT_TOKEN_HEX_CHARS
    assert token_map.token_for_sensitive_text("Patient A", "label") == token


def test_reverse_replaces_tokens_with_real_paths(tmp_path):
    token_map = browse.PathTokenMap()
    image = tmp_path / "a.tif"
    token = token_map.token_for_path(image)
    macro = f'open("{token}");'
    assert str(image.resolve()) in token_map.reverse(macro)
    assert token_map.mapping()[token] == image.resolve()


def test_path_token_cap_is_enforced(tmp_path):
    token_map = browse.PathTokenMap()
    browse_max = browse.MAX_PATH_TOKENS
    for i in range(browse_max):
        token_map.token_for_path(tmp_path / f"f{i}.tif")
    with pytest.raises(RuntimeError, match="Path token capacity"):
        token_map.token_for_path(tmp_path / "one-too-many.tif")
    assert token_map.rejected == 1


def test_over_long_path_is_rejected():
    token_map = browse.PathTokenMap()
    with pytest.raises(ValueError, match="Path exceeds"):
        token_map.token_for_path("x" * (browse.MAX_PATH_CHARS + 1))


# ------------------------------------------------ S6.32 tags from local labels


def test_default_rules_parse_a_typical_label():
    tags = browse.parse_label("mouse_4wk_WT_M_control_01")
    assert tags == {
        "timepoint": "4 weeks",
        "genotype": "wild-type",
        "sex": "male",
        "condition": "control",
    }


def test_days_timepoint_and_knockout():
    tags = browse.parse_label("10d_KO_female_treated")
    assert tags["timepoint"] == "10 days"
    assert tags["genotype"] == "knockout"
    assert tags["sex"] == "female"
    assert tags["condition"] == "treated"


def test_suggest_keeps_only_common_tags():
    assert browse.suggest_tag(["4wk_WT_control", "4wk_WT_treated"]) == (
        "4 weeks, wild-type"
    )


def test_suggest_orders_tags_and_falls_back():
    assert browse.suggest_tag(["control_M_WT_4wk"]).startswith("4 weeks, ")
    assert browse.suggest_tag(["dish1", "dish2"]) == "selected series"
    assert browse.suggest_tag([]) == ""


# ------------------------------------------- S2.28 per-folder tag rule file


def test_tag_rules_from_a_folder_override(tmp_path):
    (tmp_path / ".imagejai-tags.yml").write_text(
        "patterns:\n"
        "  - name: condition\n"
        "    pattern: '(?i)(hypoxia|normoxia)'\n"
        "    format: '{1}'\n",
        encoding="utf-8",
    )
    rules = browse.load_tag_rules(tmp_path)
    assert [rule.name for rule in rules] == ["condition"]
    assert browse.parse_label("dish_hypoxia_01", rules) == {"condition": "hypoxia"}


def test_tag_rules_accept_a_top_level_list(tmp_path):
    (tmp_path / ".imagejai-tags.yml").write_text(
        "- name: genotype\n  pattern: '(?i)(cre)'\n", encoding="utf-8"
    )
    assert [rule.name for rule in browse.load_tag_rules(tmp_path)] == ["genotype"]


def test_broken_or_missing_rule_file_falls_back_to_defaults(tmp_path):
    assert len(browse.load_tag_rules(tmp_path)) == len(browse.default_rules())
    (tmp_path / ".imagejai-tags.yml").write_text(": not yaml :\n", encoding="utf-8")
    assert len(browse.load_tag_rules(tmp_path)) == len(browse.default_rules())
    assert browse.load_tag_rules(None) == browse.default_rules()


def test_empty_rule_list_falls_back_to_defaults(tmp_path):
    (tmp_path / ".imagejai-tags.yml").write_text("patterns: []\n", encoding="utf-8")
    assert len(browse.load_tag_rules(tmp_path)) == len(browse.default_rules())


def test_a_single_bad_regex_does_not_kill_the_rule_file(tmp_path):
    (tmp_path / ".imagejai-tags.yml").write_text(
        "patterns:\n"
        "  - name: broken\n    pattern: '([unclosed'\n"
        "  - name: sex\n    pattern: '(?i)(male|female)'\n",
        encoding="utf-8",
    )
    assert [rule.name for rule in browse.load_tag_rules(tmp_path)] == ["sex"]


# ----------------------------------------- S6.33 metadata-only series scanning


def test_scan_file_single_plane(tmp_path):
    image = tmp_path / "plate.tif"
    image.write_bytes(b"II*\x00")
    token_map = browse.PathTokenMap()
    entries = browse.scan_file(image, token_map=token_map, reader=fake_reader([one_plane()]))
    assert len(entries) == 1
    entry = entries[0]
    assert entry.series == -1
    assert entry.label == "plate"
    assert entry.dimensions_label() == "512x512"
    assert entry.status() == "ready"
    assert "path" not in entry.safe_metadata()


def test_multi_series_file_gets_series_tokens(tmp_path):
    image = tmp_path / "experiment.lif"
    image.write_bytes(b"\x00")
    token_map = browse.PathTokenMap()
    series = [
        one_plane(metadata={"Image name": "Dish 1"}),
        one_plane(metadata={"Series name": "Dish 2"}, size_z=8, size_t=5, size_c=2),
    ]
    entries = browse.scan_file(image, token_map=token_map, reader=fake_reader(series))
    assert [e.series for e in entries] == [1, 2]
    assert [e.label for e in entries] == ["Dish 1", "Dish 2"]
    assert entries[1].dimensions_label() == "512x512x8, 5t"
    assert entries[0].token.endswith(":1")


def test_container_format_with_one_series_still_gets_a_series_token(tmp_path):
    image = tmp_path / "single.czi"
    image.write_bytes(b"\x00")
    entries = browse.scan_file(
        image, token_map=browse.PathTokenMap(), reader=fake_reader([one_plane()])
    )
    assert entries[0].series == 1


def test_unreadable_file_becomes_a_row_with_the_error(tmp_path):
    image = tmp_path / "broken.tif"
    image.write_bytes(b"\x00")

    def boom(_path):
        raise browse.MetadataUnavailable("needs Bio-Formats")

    entries = browse.scan_file(image, token_map=browse.PathTokenMap(), reader=boom)
    assert len(entries) == 1
    assert not entries[0].readable
    assert entries[0].status() == "needs Bio-Formats"
    assert entries[0].dimensions_label() == "unreadable"


def test_default_reader_refuses_container_formats(tmp_path):
    with pytest.raises(browse.MetadataUnavailable):
        browse.default_metadata_reader(tmp_path / "x.lif")


def test_scan_folder_sorts_and_skips_unsupported(tmp_path):
    for name in ("b.tif", "a.tif", "notes.txt", "sub"):
        target = tmp_path / name
        if name == "sub":
            target.mkdir()
        else:
            target.write_bytes(b"\x00")
    entries = browse.scan_folder(
        tmp_path, token_map=browse.PathTokenMap(), reader=fake_reader([one_plane()])
    )
    assert [entry.label for entry in entries] == ["a", "b"]


def test_scan_folder_rejects_a_file(tmp_path):
    image = tmp_path / "a.tif"
    image.write_bytes(b"\x00")
    with pytest.raises(browse.ScanError) as excinfo:
        browse.scan_folder(image, token_map=browse.PathTokenMap())
    assert excinfo.value.code == "not_a_folder"


def test_scan_folder_of_a_missing_path_is_empty(tmp_path):
    assert browse.scan_folder(tmp_path / "gone", token_map=browse.PathTokenMap()) == []
    assert browse.scan_folder(None, token_map=browse.PathTokenMap()) == []


def test_ome_tiff_base_name():
    assert browse.base_name("sample_01.ome.tif") == "sample_01"
    assert browse.base_name("sample_01.ome.tiff") == "sample_01"
    assert browse.base_name("plain.tif") == "plain"


# ------------------------------------------------------ S2.27 dialog model


def entries_for(tmp_path, names):
    token_map = browse.PathTokenMap()
    out = []
    for name in names:
        path = tmp_path / name
        path.write_bytes(b"\x00")
        out.extend(
            browse.scan_file(
                path,
                token_map=token_map,
                reader=fake_reader([one_plane(size_c=2)]),
            )
        )
    return out


def test_browse_rows_have_the_java_columns(tmp_path):
    entries = entries_for(tmp_path, ["4wk_WT_male_control.tif"])
    rows = browse.browse_rows(entries)
    assert set(browse.BROWSE_COLUMNS) <= set(rows[0])
    assert rows[0]["Timepoint"] == "4 weeks"
    assert rows[0]["Genotype"] == "wild-type"
    assert rows[0]["Status"] == "ready"
    assert rows[0]["Token"] == entries[0].token


def test_filter_rows_is_local_and_case_insensitive(tmp_path):
    entries = entries_for(tmp_path, ["alpha_wt.tif", "beta_ko.tif"])
    rows = browse.browse_rows(entries)
    assert len(browse.filter_rows(rows, "ALPHA")) == 1
    assert len(browse.filter_rows(rows, "")) == 2
    assert browse.filter_rows(rows, "nothing here") == []


def test_preview_text():
    assert browse.preview_text([]) == "No selection."
    assert browse.preview_text(["a", "b"]) == "tokens: a, b"
    assert browse.preview_text(["a"], "4 weeks") == "tokens: a\ntag: 4 weeks"


def test_posture_controls_the_outbound_name(tmp_path):
    entry = entries_for(tmp_path, ["patient_A.tif"])[0]
    assert browse.entry_display(entry, "PSEUDONYMISED") == entry.token
    assert browse.entry_display(entry, "ON_PREMISES") == entry.token
    assert browse.entry_display(entry, "STANDARD") == entry.label
    assert browse.requires_pseudonym(None) is True
    assert browse.requires_pseudonym("on-premises") is True


# ------------------------------------------------------------- S6.31 brief


def test_build_brief_sends_tokens_only(tmp_path):
    entries = entries_for(tmp_path, ["patient_A_4wk.tif", "patient_B_4wk.tif"])
    brief = browse.build_brief("sess1", entries, "4 weeks")
    assert brief["session_id"] == "sess1"
    assert brief["metadata"]["count"] == 2
    assert brief["metadata"]["channels"] == 2
    assert brief["metadata"]["dimensions"] == "512x512"
    blob = repr(brief)
    assert "patient_A" not in blob and "patient_B" not in blob
    for token in brief["tokens"]:
        assert browse.TOKEN_PATTERN.fullmatch(token)


def test_build_brief_marks_mixed_dimensions(tmp_path):
    token_map = browse.PathTokenMap()
    entries = []
    for name, plane in (
        ("a.tif", one_plane(size_c=1)),
        ("b.tif", one_plane(size_x=256, size_c=3)),
    ):
        path = tmp_path / name
        path.write_bytes(b"\x00")
        entries.extend(
            browse.scan_file(path, token_map=token_map, reader=fake_reader([plane]))
        )
    brief = browse.build_brief("s", entries, "")
    assert brief["metadata"]["dimensions"] == "mixed dimensions"
    assert brief["metadata"]["channels"] == 0


def test_build_brief_needs_a_readable_selection(tmp_path):
    with pytest.raises(ValueError, match="Select at least one file or series."):
        browse.build_brief("s", [], "")


def test_build_brief_drops_a_non_token_string(tmp_path):
    entry = entries_for(tmp_path, ["a.tif"])[0]
    forged = browse.SeriesEntry(
        file=entry.file, series=-1, token="C:/real/path/a.tif", label="a"
    )
    brief = browse.build_brief("s", [entry, forged], "")
    assert len(brief["tokens"]) == 1
    assert "C:/real/path" not in repr(brief["tokens"])


def test_build_brief_defaults_the_session_id(tmp_path):
    entries = entries_for(tmp_path, ["a.tif"])
    assert browse.build_brief("  ", entries, "")["session_id"] == "default"


def test_compose_nudge_matches_the_java_sentence():
    brief = {
        "tokens": ["image-aa.tif", "image-bb.tif"],
        "tag": "4 weeks",
        "metadata": {"channels": 3, "dimensions": "512x512"},
    }
    assert browse.compose_nudge(brief) == (
        "I've selected 2 images/series for analysis: image-aa.tif, image-bb.tif "
        '(tagged "4 weeks", 3-channel, 512x512). '
        "Please call get_pending_brief for details."
    )


def test_compose_nudge_singular_and_untagged():
    brief = {"tokens": ["image-aa.tif"], "tag": "", "metadata": {}}
    assert browse.compose_nudge(brief) == (
        "I've selected 1 image/series for analysis: image-aa.tif. "
        "Please call get_pending_brief for details."
    )


def test_delivery_choices_match_the_dialog():
    assert browse.DELIVERY_CHOICES == ("Embedded terminal", "Clipboard", "TCP polling")


# --------------------------------------------- "@" mentions in the chat input


def make_files(tmp_path, names):
    for name in names:
        (tmp_path / name).write_bytes(b"\x00")
    return tmp_path


def test_mention_candidates_put_images_first(tmp_path):
    make_files(tmp_path, ["notes.md", "b_image.tif", "a_image.lif", "table.csv"])
    found = browse.mention_candidates(tmp_path)
    assert [c.label for c in found] == ["a_image.lif", "b_image.tif", "notes.md", "table.csv"]
    assert [c.kind for c in found[:2]] == ["image", "image"]


def test_mention_prefix_matches_name_and_stem(tmp_path):
    make_files(tmp_path, ["patient_A.lif", "other.tif"])
    assert [c.label for c in browse.mention_candidates(tmp_path, "pat")] == ["patient_A.lif"]
    assert [c.label for c in browse.mention_candidates(tmp_path, "@PAT")] == ["patient_A.lif"]
    assert browse.mention_candidates(tmp_path, "zzz") == []


def test_mention_candidates_are_bounded_and_stable(tmp_path):
    make_files(tmp_path, [f"img_{i:03d}.tif" for i in range(40)])
    token_map = browse.PathTokenMap()
    first = browse.mention_candidates(tmp_path, limit=5, token_map=token_map)
    assert [c.label for c in first] == [f"img_{i:03d}.tif" for i in range(5)]
    # same map, same input -> byte-identical list, so the popup does not jump
    assert browse.mention_candidates(tmp_path, limit=5, token_map=token_map) == first
    assert browse.mention_candidates(tmp_path, limit=0) == []


def test_mention_skips_hidden_and_unknown_types(tmp_path):
    make_files(tmp_path, [".hidden.tif", "script.exe", "real.tif"])
    (tmp_path / "subdir").mkdir()
    assert [c.label for c in browse.mention_candidates(tmp_path)] == ["real.tif"]


def test_mention_insert_text_follows_the_posture(tmp_path):
    make_files(tmp_path, ["patient_A.lif"])
    token_map = browse.PathTokenMap()
    tokenised = browse.mention_candidates(
        tmp_path, token_map=token_map, posture="PSEUDONYMISED"
    )[0]
    assert tokenised.insert_text == tokenised.token
    assert "patient_A" not in tokenised.insert_text
    assert browse.TOKEN_PATTERN.fullmatch(tokenised.insert_text)

    plain = browse.mention_candidates(tmp_path, token_map=token_map)[0]
    assert plain.insert_text == str((tmp_path / "patient_A.lif").resolve())


def test_mention_token_matches_the_browse_token(tmp_path):
    make_files(tmp_path, ["shared.tif"])
    token_map = browse.PathTokenMap()
    entry = browse.scan_file(
        tmp_path / "shared.tif", token_map=token_map, reader=fake_reader([one_plane()])
    )[0]
    mention = browse.mention_candidates(
        tmp_path, token_map=token_map, posture="PSEUDONYMISED"
    )[0]
    assert mention.token == entry.token


def test_mention_candidates_ignore_a_missing_folder(tmp_path):
    assert browse.mention_candidates(tmp_path / "gone") == []
    assert browse.mention_candidates(None) == []
    assert browse.mention_candidates(tmp_path / "f.tif") == []


def test_mention_replacement_keeps_the_rest_of_the_sentence(tmp_path):
    make_files(tmp_path, ["a.tif"])
    candidate = browse.mention_candidates(tmp_path, posture="STANDARD")[0]
    text = "open @a.t and measure"
    replaced = browse.mention_replacement(text, 5, candidate)
    assert replaced == f"open {candidate.insert_text} and measure"


def test_mention_replacement_rejects_a_bad_index(tmp_path):
    make_files(tmp_path, ["a.tif"])
    candidate = browse.mention_candidates(tmp_path)[0]
    with pytest.raises(ValueError):
        browse.mention_replacement("no at sign", 2, candidate)
