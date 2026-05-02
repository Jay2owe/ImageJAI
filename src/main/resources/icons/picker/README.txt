Phase D of the multi-provider rollout draws tier badges, status icons, and
pin stars directly via Graphics2D in ModelMenuItem (Unicode glyphs + filled
ovals). No PNG bundles ship yet; this directory is reserved so Phase G/H can
drop pre-rendered crisp-edge icons here without needing pom.xml changes.

Source spec: docs/multi_provider/05_ui_design.md §3.2.
