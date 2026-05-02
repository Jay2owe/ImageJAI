# Macro sets

Drop any saved ImageJ macro (`.ijm`) file in this folder to expose it through
the embedded terminal rail's **Macro sets...** button.

The rail sends the selected file body directly to the local ImageJAI TCP server
as `execute_macro` with `source=rail:macro-set`. The agent is not involved, so
this is intended for known-good repeat macros that you want to run quickly.
