# Data Governance Manual Test Checklist

[ ] Fresh install: open a folder with no .imagejai-posture.json ->
    banner appears, default Pseudonymised.
[ ] Apply on banner -> .imagejai-posture.json created.
[ ] Re-open folder -> banner does NOT appear; posture loaded.
[ ] Posture badge colour and label match folder posture.
[ ] In Pseudonymised, run get_state -> egress lamp blinks, Receipts
    row appears, [show] reveals redacted payload only.
[ ] Configuration Pane expander shows current posture, audit-log
    path, session call counts, Generate button.
[ ] "View Audit Log" opens CSV in OS default app.
[ ] "Generate Data Handling Statement" produces a PDF with all 7
    sections.
[ ] Set posture to On-premises -> dropdown shows only local
    binaries.
[ ] Launch Ollama with gemma4:31b-cloud in On-premises -> error
    dialog with documented refusal.
[ ] Open second folder marked On-premises while in Standard ->
    downshift banner; badge updates.
[ ] Open Standard folder while On-premises -> NO upshift.
[ ] Click Override (logged) on downshift banner -> audit row with
    free-text reason.
[ ] Open Browse Files dialog -> series of a .lif file populate;
    search "8w wt" filters correctly.
[ ] Select 3 series, click Send -> external CLI: clipboard toast.
    Embedded PTY: text auto-typed.
[ ] Agent receives brief, opens each token via
    open_image_by_token -> images appear in Fiji.
[ ] capture_image of active microscopy image: lamp blinks, base64
    appears in receipt but ≤512px.
[ ] capture_image of a dialog: receipt shows placeholder, no
    image bytes.
[ ] request_visual flow: agent calls, user sees toast, clicks
    Allow -> next capture full-res; subsequent capture back to
    downsampled.
[ ] Type a sensitive name into the embedded PTY -> toast appears,
    underlying bytes contain the token.
[ ] Audit CSV header line matches the documented column order
    exactly.
[ ] PDF §4 free-tier Gemini line shows the uppercase warning.
[ ] PDF §3 mentions series-within-file and differentiated capture.
[ ] PDF §7 mentions external-CLI typed-filename limitation.
