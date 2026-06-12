# VLM Integration Research for ImageJAI

## 1. Current State of VLMs for Microscopy

### 1.1 Benchmark Results: VLMs on Microscopy Tasks

The field has been rigorously benchmarked by two key studies:

**"Beyond Human Vision" (IEEE, 2024)** evaluated ChatGPT, LLaVA, Gemini, and SAM on
classification, segmentation, counting, and visual question answering (VQA) tasks using
microscopy images. Key findings:
- ChatGPT and Gemini demonstrated impressive comprehension of microscopy image features
- SAM proved capable at isolating artifacts in a general sense
- However, performance was NOT close to that of a domain expert
- Models struggled with impurities, defects, artifact overlaps, and diversity in image types

**mu-Bench (NeurIPS, 2024)** is an expert-curated benchmark encompassing 22 biomedical
tasks across biology and pathology, covering electron, fluorescence, and light microscopy at
subcellular, cellular, and tissue scales. Key findings:
- Current models struggle on ALL categories, even basic tasks like distinguishing microscopy
  modalities
- Specialist models fine-tuned on biomedical data often perform WORSE than generalist models
- Fine-tuning causes "catastrophic forgetting" -- domain specialization erodes prior biomedical
  knowledge encoded in the base model
- Weight interpolation between fine-tuned and pre-trained models offers one mitigation strategy

**MicroVQA** tests challenging scientific reasoning by recruiting experts to create questions
reflecting real research tasks (analyzing microscopy images in novel experimental contexts,
evaluating hypotheses).

### 1.2 Implications for ImageJAI

VLMs are NOT ready to replace domain expertise for quantitative microscopy. However, they
excel at:
- Qualitative description of image content (modality identification, structure recognition)
- Suggesting appropriate analysis approaches based on visual assessment
- Natural language interaction about image properties
- Generating narrative summaries of quantitative results produced by traditional methods

The practical path is: use VLMs for understanding and guidance, but rely on ImageJ's proven
algorithms for actual measurement and quantification.


## 2. VLM Model Landscape for Microscopy

### 2.1 Cloud/API Models (Best Quality)

#### Gemini (Google) -- Currently Integrated in ImageJAI
- **Gemini 2.5 Flash**: Free tier available; $0.30/M input tokens, $2.50/M output tokens paid
- **Gemini 3 Flash**: Free tier available; $0.50/M input tokens, $3.00/M output tokens paid
- Images consume ~1290 tokens for 1024x1024 (the size ImageJAI already captures)
- Free tier: ~250,000 TPM, 5-15 RPM depending on model
- ALREADY SUPPORTS vision via `chatWithVision()` in ImageJAI
- Best option for microscopy because: free tier, good vision, already integrated
- Gemini 2.0 Flash is deprecated (shutdown June 2026), update to 2.5 or 3 Flash

#### GPT-4o / GPT-4V (OpenAI)
- Studies show human-like performance on biomedical image classification
- Evaluated on cell type classification from light microscopy, cell state classification from
  DAPI fluorescence images
- Good at modality and anatomy recognition, struggles with disease diagnosis and localization
- Cost: GPT-4o-mini is cheapest at ~$0.15/M input, $0.60/M output
- Already supported via ImageJAI's `OpenAICompatibleBackend`

#### Claude (Anthropic)
- Claude Sonnet 4.6: $3/M input, $15/M output; Claude Haiku 4.5: $1/M input, $5/M output
- All current Claude models support vision input
- Accessible via ImageJAI's `OpenAICompatibleBackend` with Anthropic's OpenAI-compatible endpoint
- Batch API (50% off) + prompt caching (90% savings) can reduce costs up to 95%

### 2.2 Open-Source Models via Ollama (Local, Private)

ImageJAI already has Ollama integration. These vision-capable models are available:

#### Top Tier (Best for Microscopy)
| Model | Sizes | VRAM Needed | Notes |
|-------|-------|-------------|-------|
| **Qwen3-VL** | 2B-235B | 4-48GB+ | Best open-source VLM; OCR, charts, visual reasoning |
| **Qwen2.5-VL** | 3B-72B | 4-48GB+ | Previous generation, still very capable |
| **Gemma 4** | 26B, 31B | 16-24GB | Frontier-level multimodal understanding |
| **Llama 3.2 Vision** | 11B, 90B | 8-48GB+ | Meta's instruction-tuned image reasoning |

#### Practical Tier (Runs on Consumer GPUs)
| Model | Sizes | VRAM Needed | Notes |
|-------|-------|-------------|-------|
| **Qwen3-VL:8B** | 8B | 6-8GB | Best quality/size ratio for local |
| **Gemma 3** | 270M-27B | 2-16GB | Broad size range, multiple image support |
| **LLaVA-Llama3** | 8B | 6-8GB | Well-established, good baseline |
| **MiniCPM-V** | 8B | 6-8GB | Compact, designed for vision-language |
| **Granite3.2-Vision** | 2B | 2-4GB | Minimal footprint, document-focused |

**Recommendation**: Qwen3-VL:8B is the best local option. It runs on a single consumer GPU
(8GB VRAM), supports up to 256K token context, and outperforms LLaVA variants at every
benchmark. ImageJAI should update its default Ollama model suggestion from `llama3` to
`qwen3-vl:8b` for vision tasks.

### 2.3 Domain-Specific Models

#### BiomedCLIP (Microsoft)
- Pretrained on PMC-15M: 15 million figure-caption pairs from PubMed Central
- Covers microscopy (light, electron), radiography, histology, digital pathology
- Architecture: PubMedBERT (text) + ViT (image)
- Best for: cross-modal retrieval, zero-shot classification, image-text matching
- NOT a generative model -- cannot have conversations, only computes similarity scores
- Use case in ImageJAI: ranking/classifying microscopy images, suggesting analysis type

#### LLaVA-Med (Microsoft)
- Fine-tuned LLaVA for biomedicine using PMC-15M + GPT-4 self-instruction
- Can have conversations about biomedical images
- Available on HuggingFace: `microsoft/llava-med-v1.5-mistral-7b`
- Primarily trained on radiology/pathology -- fluorescence microscopy is NOT its strength
- Not available on Ollama natively (would need custom model setup)

#### CellViT++ (2025)
- Foundation model specifically for cell segmentation and classification
- Uses frozen pretrained ViT + lightweight classifier for rapid adaptation
- Available on PyPI and GitHub (open source)
- Excellent zero-shot segmentation performance across 7 datasets
- Integration path: Python subprocess via `CrossToolRunner`

#### MedSAM / MedSAM2 (2025)
- Segment Anything adapted for medical images (1.57M image-mask pairs)
- MedSAM2: 3D volume + video segmentation (April 2025)
- Supports point, box, and mask prompts
- Integration path: SAMJ plugin already exists for Fiji

#### MedCLIP-SAMv2 (MedIA 2025)
- Combines BiomedCLIP with SAM for text-prompted segmentation
- "Segment the tumor" in natural language generates bounding boxes for SAM
- Zero-shot DSC improved from 64.5% to 77.6% over v1
- Validated on ultrasound, MRI, X-ray, and CT
- Potential for microscopy adaptation with fine-tuning

### 2.4 Visual Grounding Models

#### Grounding DINO
- Open-vocabulary object detection from text queries
- "a red fire hydrant" -> bounding boxes around all matching objects
- Architecture: DETR transformer + Swin backbone + language-guided cross-attention
- Highly relevant for "find all cells" type queries in microscopy
- Integration: Python-based, would connect via `CrossToolRunner` or TCP server

#### SAMJ (Fiji Plugin)
- ALREADY exists as a Fiji plugin for SAM-based annotation
- Supports SAM-2, EfficientSAM, EfficientViTSAM
- Uses Appose to bridge Java (Fiji) and Python (SAM)
- Two modes: Live (interactive one-click) and Batch (multiple seed points)
- ImageJAI could invoke SAMJ programmatically via macro commands


## 3. VLM Inference: Local vs API

### 3.1 API-Based Inference

**Advantages:**
- Best model quality (Gemini 3, GPT-4o, Claude Sonnet)
- No GPU required on user's machine
- Always up to date with latest models
- Free tier available (Gemini)

**Disadvantages:**
- Latency: 1-5 seconds per request (acceptable for interactive use)
- Privacy: images sent to cloud (see Section 5)
- Cost at scale: batch processing 1000 images could cost $5-50
- Rate limits: free tier is 5-15 RPM (sufficient for interactive, too slow for batch)
- Requires internet connection

**Cost Estimate for Microscopy Workflow:**
- 1 image analysis query: ~1290 input tokens (image) + 200 tokens (text) = ~1490 tokens
- Gemini 2.5 Flash paid: $0.30/M * 1490 = $0.000447 per query
- 100 images/day: ~$0.045/day or ~$1.35/month
- Free tier: 250K TPM = ~167 queries per minute (effectively unlimited for interactive use)

### 3.2 Local Inference via Ollama

**Advantages:**
- Complete privacy -- no data leaves the machine
- No per-query cost after hardware investment
- No rate limits
- No internet required
- Full control over model version

**Disadvantages:**
- Requires GPU (8GB+ VRAM for usable quality)
- Slower: 5-30 seconds per query on consumer hardware
- Lower quality than best API models
- Model updates require manual download
- Memory pressure alongside Fiji/ImageJ (both are memory-hungry)

**Hardware Requirements:**
| Model | VRAM | Speed (approx) | Quality |
|-------|------|-----------------|---------|
| Qwen3-VL:2B | 2-4GB | 2-5s | Basic |
| Qwen3-VL:8B | 6-8GB | 5-15s | Good |
| Qwen2.5-VL:32B | 24GB | 15-30s | Very good |
| Qwen3-VL:72B | 48GB+ | 30-60s | Near-API |

### 3.3 Hybrid Approach (Recommended)

ImageJAI already supports both Ollama and Gemini. The recommended strategy:
1. **Interactive analysis**: Use Gemini free tier (best quality, acceptable latency)
2. **Batch processing**: Use Ollama local (no rate limits, no cost)
3. **Sensitive data**: Always use Ollama local (no cloud transmission)
4. **User choice**: Let users pick per-query (already possible via settings)


## 4. VLM Capabilities Relevant to Microscopy

### 4.1 Natural Language Image Description
VLMs can describe:
- Imaging modality (fluorescence, brightfield, confocal, EM)
- Visible structures (cells, nuclei, processes, tissue architecture)
- Image quality (noise, saturation, artifacts, out-of-focus regions)
- Staining patterns (nuclear, cytoplasmic, membrane, punctate)
- Relative abundance and distribution of labeled structures

Current accuracy: moderate for general description, unreliable for quantification.

### 4.2 Parameter Suggestion
VLMs can suggest:
- Appropriate threshold method based on histogram shape
- Filter parameters based on noise characteristics
- Segmentation approach based on cell morphology
- Channel assignments based on staining patterns

This works because VLMs understand the visual context that determines optimal parameters,
even if they cannot compute the parameters themselves.

### 4.3 ROI-Level Analysis Guidance
VLMs can identify:
- Regions of interest (tissue boundaries, cell clusters, artifacts)
- Areas to exclude (folds, tears, bubbles, out-of-focus regions)
- Heterogeneous regions requiring separate analysis
- Landmarks for registration or orientation

### 4.4 Result Interpretation
VLMs excel at:
- Generating narrative descriptions of quantitative results
- Identifying unexpected patterns in measurement data
- Suggesting follow-up analyses based on initial results
- Providing biological context for observed phenotypes

### 4.5 Quality Control
VLMs can detect:
- Saturated/clipped pixels
- Uneven illumination
- Chromatic aberration
- Bleedthrough between channels
- Tissue processing artifacts


## 5. Privacy Considerations

### 5.1 Regulatory Landscape

**GDPR (EU/UK):**
- Microscopy images containing patient-derived tissue are personal data under GDPR
- Transfer to non-EU cloud APIs requires legal basis (adequacy decision, SCCs, etc.)
- Animal tissue images are NOT personal data under GDPR
- Cell line images are generally NOT personal data
- Research exemption exists but requires safeguards

**Institutional Policies:**
- Most universities and research institutes have data classification policies
- Human tissue images are typically classified as sensitive/restricted
- Review institutional policies before using cloud APIs for human-derived samples

### 5.2 Risk Assessment by Image Type

| Image Type | GDPR Risk | Cloud API OK? |
|------------|-----------|---------------|
| Animal tissue (mouse brain, etc.) | None | Yes |
| Cell lines (HeLa, etc.) | None | Yes |
| Sample images / test data | None | Yes |
| Human tissue (de-identified) | Medium | Check policy |
| Human tissue (identifiable) | High | No -- use local |
| Patient-derived organoids | Medium | Check policy |
| Clinical pathology slides | High | No -- use local |

### 5.3 API Provider Data Policies

**Google Gemini (free tier):**
- WARNING: Free tier data may be used to improve products
- Paid tier: data is NOT used for training (with appropriate settings)
- Data processed in Google Cloud (US/EU regions available)

**Google Gemini (paid tier):**
- Enterprise-grade data handling with no training data usage
- Can be configured for EU-only processing

**OpenAI:**
- API data is NOT used for training by default (since March 2023)
- Data retention: 30 days for abuse monitoring, then deleted
- SOC 2 Type II compliant

**Anthropic (Claude):**
- API data is NOT used for training
- Prompt-level opt-in required for any data usage
- SOC 2 Type II compliant

**Ollama (Local):**
- No data transmission whatsoever
- Complete privacy guaranteed
- Recommended for any sensitive data

### 5.4 Practical Recommendations

1. **Default to local models** for any human-derived samples
2. **Use Gemini paid tier** (not free) for non-sensitive research images
3. **Strip metadata** before sending images to any API (EXIF, DICOM headers)
4. **Log all API calls** for audit trail (timestamp, image hash, provider)
5. **Inform research participants** if cloud AI analysis is used on their samples
6. **Consult your DPO** (Data Protection Officer) before implementing cloud VLM features


## 6. Key References

### Benchmarks
- "Beyond Human Vision: The Role of Large Vision Language Models in Microscope Image
  Analysis" -- IEEE 2024 (arXiv:2405.00876)
- "mu-Bench: A Vision-Language Benchmark for Microscopy Understanding" --
  NeurIPS 2024 (arXiv:2407.01791)
- "MicroVQA: A Multimodal Reasoning Benchmark for Microscopy-Based Scientific Research"

### Models
- BiomedCLIP: arXiv:2303.00915 (Microsoft, PMC-15M dataset)
- LLaVA-Med: arXiv:2306.00890 (Microsoft, biomedical VQA)
- CellViT++: arXiv:2501.05269 (cell segmentation foundation model)
- MedSAM: Nature Communications 2024 (universal medical segmentation)
- MedCLIP-SAMv2: MedIA 2025 (text-driven medical segmentation)
- Grounding DINO: ECCV 2024 (open-vocabulary detection)
- SAMJ: arXiv:2506.02783 (SAM integration for ImageJ/Fiji)

### Reviews
- "Vision-language foundation models for medical imaging: a review" -- Biomedical
  Engineering Letters 2025
- "Multimodal generative AI for medical image interpretation" -- Nature 2025
- "Vision-Language Models in medical image analysis" -- Information Fusion 2025

### Privacy
- "Privacy-Preserving in Medical Image Analysis: A Review" -- arXiv:2412.03924
- "Rethinking Privacy in Medical Imaging AI" -- Radiology: AI 2025
