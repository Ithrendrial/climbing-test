# ClimbSpike — Project Context and Handoff

**Prepared 6 August 2026.** Self-contained briefing for report writing. Everything below is
either established fact, a measured result, or an explicitly-flagged open question.

---

## 1. What the project is

A native Android application that analyses video of indoor bouldering attempts. The climber
imports a video; the app detects the route's holds by colour, tracks the climber's body with
pose estimation, discards the source video, and retains only a synthetic reconstruction. From
that reconstruction it identifies periods of hesitation, the crux, and the climbing techniques
used, and maintains a training log supporting comparison between attempts on the same route.

Academic context: MSc Mobile Development module. The stated requirement is a mobile application
with a light machine learning component.

---

## 2. Feature set

| # | Feature | Priority |
|---|---------|----------|
| F1 | **Create a route.** Upload a video to define a new route; this becomes its first attempt and its reference frame. | Must |
| F2 | **Add an attempt** to an existing route. | Must |
| F3 | **The vault.** Home page listing attempts, with searchable fields and tags. | Must |
| F4 | **Process on upload.** Hold detection by colour thresholding, plus homography alignment when the attempt belongs to an existing route; pose estimation via MediaPipe. Produce a synthetic reconstruction and store *only* that — no video file is retained. | Must |
| F5 | **Analyse.** Identify hesitation periods, the crux, and techniques (flag, dyno, heel hook, high step, mantle). Clicking a technique jumps to that section of the reconstruction. Each classification accepts user confirmation or correction. | Must |
| F6 | **Ghost comparison.** Overlay a previous attempt against the current one. | Must |
| F7 | **Share card.** Strava-style summary with basic stats and a route path, exportable to social media. | Non-essential |

### 2.1 Three consequences of F4 worth stating explicitly

**Storage.** Approximately 90 MB of video becomes approximately 100 KB of pose and hold data —
a reduction of roughly three orders of magnitude. This is what makes an unbounded training log
practical on a phone.

**Privacy.** Footage of the user is never persisted. This is a stronger position than
"we don't upload your video", and it is architecturally enforced rather than promised.

**Requirements coherence.** An earlier draft contained a direct contradiction — discard the
video, yet click a tag to jump to a moment *in the video*. The reconstruction resolves it: pose
plus holds is sufficient to render a scrubable playback, so there is something to jump to.
Arguably it is clearer than footage, since nothing is occluded and the viewing angle is
normalised.

### 2.2 A design question left open in F4

"Create a synthetic video" can mean either rendering an actual video file, or storing the pose
and hold data and rendering playback on demand.

**Recommendation: store the data, render on demand.** A rendered file would be larger, could not
support the ghost overlay of F6 without re-rendering, and could not be re-analysed if thresholds
change. Storing the data keeps all three open. The user-facing behaviour is identical.

### 2.3 A tension in F7

F7 exports to social media, which is the only part of the system that sends user data off the
device. It does not undermine the privacy position — it is user-initiated, and only derived
statistics leave — but the distinction between *automatic retention* and *explicit export*
should be drawn deliberately rather than by omission.

---

## 3. Feasibility: what the spike established

A throwaway spike was built and run on a real device before requirements were fixed. Its purpose
was to retire the single largest risk: that pose estimation trained on upright, forward-facing
subjects would fail on a climber oriented towards a wall.

**Conditions.** One indoor bouldering attempt, graded V6, 36.2 s, filmed handheld in portrait.
Analysed on a Redmi Note 14 Pro 5G. MediaPipe Pose Landmarker (BlazePose), `full` model, VIDEO
running mode, sampled at 5 Hz.

| Measure | Result |
|---------|--------|
| Frames sampled | 182 |
| Frames with pose detected | 182 (100%) |
| Frames with resolvable torso reference | 182 (100%) |
| Mean landmark visibility | 0.939 |
| Wall-clock analysis time | 44.7 s — 1.24× video duration |
| Hesitation periods | 5, totalling 9.2 s (25% of the attempt) |
| Crux identified | 16.6 s – 21.4 s |

**The risk was not realised.** Detection was perfect and confidence high on exactly the footage
that was expected to be hardest.

### 3.1 Cross-backend agreement

The spike was implemented first against ML Kit Pose Detection, then re-implemented against
MediaPipe. The two produced overlapping crux windows — 15.6–21.8 s and 16.6–21.4 s — and agreed
on hesitation periods at 2–3 s and 22–24 s.

This is genuine evidence that the segmentation reflects a property of the attempt rather than an
artefact of one model. **It establishes consistency, not correctness** — see risk R1. The
distinction should be preserved in the report; overclaiming here is the easiest way to lose
credibility under questioning.

### 3.2 Decoding dominates cost, not inference

At 245 ms per sampled frame against an expected model inference cost of 30–50 ms, roughly 80% of
analysis time is video seek and decode. Performance work should target the decoding strategy —
sequential MediaCodec decode rather than per-frame seeking — not model selection. Choosing a
smaller pose model would barely help.

### 3.3 Camera motion affects displacement metrics

Height gained measured 3.65 torso-lengths (≈ 2 m) on a wall exceeding that height. Where footage
is handheld and panned to follow the climber, image-space displacement understates real travel.

Metrics depending on absolute displacement are unreliable under camera motion. Metrics depending
on *relative rates* of movement — hesitation and crux detection among them — are not affected.

---

## 4. Machine learning versus classical methods

Exactly one stage requires a neural network. This is not a weakness; it is what an applied ML
system normally looks like, and it is worth stating plainly in the report rather than
disguising.

| Stage | Approach | How it works |
|-------|----------|--------------|
| Frame decoding | Classical | Sample one frame every 200 ms |
| **Pose estimation** | **Pretrained CNN** | BlazePose: detect person, then locate 33 landmarks within the box. VIDEO mode reuses the previous frame's box for tracking |
| Hold detection | Classical CV | RGB → HSV, threshold on hue, morphological open/close, connected components, filter by area and shape |
| Route alignment | Classical CV | Match hold constellations between attempts, fit homography with RANSAC |
| Derived signals | Arithmetic | Centre of mass from hip midpoint; speed as distance ÷ time ÷ torso length; joint angles from vector dot products; contact as proximity to a hold |
| Phase segmentation | Signal processing | Smooth the speed curve, threshold it, find runs below threshold lasting over a second |
| Technique tagging | Geometric rules | Per-window predicates; optionally upgraded to a learned classifier |
| Comparison | Dynamic programming | Dynamic time warping for differing attempt durations |

### 4.1 Why HSV rather than RGB for hold detection

HSV separates *which* colour from *how bright*. A blue hold in shadow retains its hue; in RGB it
becomes a numerically different colour entirely. Gym lighting, not the algorithm, will be the
practical difficulty.

### 4.2 Why the crux detector is not machine learning

Label density decides it. Crux labels are **per attempt** — thirty videos yield thirty examples,
which will never train anything. Technique labels are **per move** — roughly fifteen an attempt,
so thirty videos yield around 450 examples, which is sufficient for a classical classifier.

This asymmetry is the whole argument, and it is a stronger methodological statement than
retrofitting a model would be. Rule-based crux detection is also interpretable, which matters
when the output is coaching feedback the user must be able to disagree with.

### 4.3 Why flagging needs hold detection

A flag is a leg extended for counterbalance **that is not on a hold**. From pose alone, a flagged
leg and a leg resting on a volume are close to identical — the distinguishing information is not
in the skeleton, so no quantity of training data resolves it. Given hold positions it becomes a
geometric test.

**F4 is therefore a prerequisite for F5, not a parallel feature.**

### 4.4 Where a learned classifier would earn its place

Not accuracy — maintainability. Five hand-written rules are readable; twelve, with interacting
thresholds and precedence between overlapping labels, are not. The upgrade path is the same
geometric features fed to a Random Forest, exported to TFLite, running on-device.

Keeping the rules as a baseline gives a genuine evaluation chapter: two approaches, honest
numbers, discussion of where each wins.

### 4.5 F5's feedback loop is the training corpus

Per-classification confirmation or correction turns ordinary use into labelled data, collected by
the application being assessed. This is the cleanest available answer to "where would your
dataset come from", and it is a stronger story than downloading one.

Note the practical dependency: hand-labelling raw video is prohibitive, whereas *correcting*
proposed labels is fast. The rule-based classifier must exist before the corpus can be collected
efficiently.

---

## 5. Architecture constraints, with evidence

Each of these is derived from a measurement, not from principle. That provenance is worth keeping
in the report — it is the difference between a justified architecture and an asserted one.

**C1 — Analysis cannot live in the UI layer.** Measured 1.24× video duration; a three-minute clip
is roughly four minutes of work. Analysis must survive backgrounding and process death, which
means `WorkManager` and a resumable or cleanly-failing job.

**C2 — The pose backend must sit behind an interface.** Demonstrated, not assumed: ML Kit was
replaced with MediaPipe and only one file changed. That is a proven seam and the natural place
for a dependency-inversion boundary.

**C3 — The analysis layer is pure Kotlin with no Android dependencies.** It unit-tests on the JVM
in seconds. It already constitutes a domain layer in the Clean Architecture sense; this should be
named and preserved rather than rediscovered.

**C4 — Persistence must not parse everything at start-up.** At roughly 100 KB of pose data per
attempt, two hundred attempts is 20 MB of JSON. Room, with frame data stored separately from
attempt summaries.

**C5 — Scale normalisation is a domain invariant.** All movement metrics are expressed in
torso-lengths per second rather than pixels, so that attempts filmed from different distances
remain comparable. Without this, F6 is meaningless.

**C6 — Each route owns a reference frame.** F1 establishes it from the first attempt's hold map;
F2 warps subsequent attempts into it by homography. Alignment is a property of the *route*, not
of a pair of attempts.

### 5.1 On C5 — the failure mode is silent

During the spike, this normalisation broke. Landmark coordinates changed from pixels to
normalised 0–1 values when the pose backend was swapped, and a sanity threshold written for pixel
scale rejected every frame. The scale reference fell back to a default of 1.

**Nothing crashed and no error was logged.** The application produced a plausible, well-formatted,
entirely incorrect analysis in which the whole attempt was classified as a single continuous
crux. The unit tests passed throughout, because their fixtures were still written in pixel
coordinates — they were testing a coordinate system the application no longer used.

Two points worth carrying into the report. First, this is the argument for making normalisation
explicit and testable rather than incidental. Second, it is the argument for F5's transparency
features: an analysis the user cannot inspect is one they cannot tell is wrong.

---

## 6. Literature anchors

Google's **Guide to App Architecture** is the canonical Android citation (UI, domain and data
layers with unidirectional data flow), pairing naturally with Martin's *Clean Architecture* and
the MVVM lineage from Fowler's presentation-model work. C2 is a textbook Ports and Adapters
boundary.

For the domain side: BlazePose is documented in Google's research publications, and ML Kit Pose
Detection is explicitly the same underlying model exposed through a different API — worth citing,
since it explains why the backend swap was a one-file change. Published climbing datasets are
sparse; **CIMI4D** (CVPR 2023) is the most substantial but uses LiDAR and point clouds on outdoor
rock, and **SPEED21** covers speed climbing. Neither matches indoor bouldering video, which is
the justification for collecting a corpus rather than using a public one.

---

## 7. Open risks

**R1 — The crux heuristic is unvalidated against human judgement.** Cross-backend agreement
establishes consistency, not correctness. No comparison has been made between the identified crux
and where the climber actually experienced difficulty. *This is the principal outstanding risk:
the central feature rests on an unvalidated heuristic.* Mitigation: comparative study over 20–30
attempts with climber-reported crux locations. Cost: roughly one session at the gym plus a
spreadsheet.

**R2 — Thresholds are untuned.** Hesitation and dynamic-movement thresholds were set by
inspection on a single attempt. Mitigation: fit against the corpus from R1. Stored pose data
permits re-analysis without reprocessing video.

**R3 — Single-condition evidence.** All measurements derive from one attempt, one route, one
climber, one device. Behaviour under poor lighting, greater camera distance, or crowded walls is
unknown.

**R4 — Camera motion.** See §3.3. Either constrain guidance to fixed-camera footage or restrict
metrics to those invariant under camera motion.

**R5 — The crux is subjective.** The hardest section is climber-specific and may shift between
attempts as technique changes. The application should present its identification as evidence for
the climber to interpret, not as ground truth.

**R6 — Homography assumes planarity.** Holds are treated as lying on a plane. True for a flat
vertical wall, false for overhangs and volumes, where alignment degrades to an approximation.
State as an assumption rather than discovering it during evaluation.

**R7 — Hold correspondence is non-trivial.** Twelve blue holds look alike, so colour alone cannot
establish which hold is which between two attempts. RANSAC over spatial configuration handles it,
but this is the genuinely difficult part of F4 and should not be underestimated.

**R8 — Hold detection under gym lighting is unproven.** Colour thresholding is straightforward in
principle; mixed artificial lighting, coloured LEDs and shadow are where it becomes difficult. No
spike has been run on this yet, so F4 carries the feasibility risk that F5's pose estimation no
longer does.

---

## 8. Build order

| Phase | Work | Rationale |
|-------|------|-----------|
| 1 | **Validate the crux** against 20–30 real attempts | No code. Determines whether anything downstream is worth building |
| 2 | **Hold detection** (HSV + blob detection) | Carries the remaining feasibility risk (R8); unblocks F4, F5 and F6 |
| 3 | **Contact detection and technique rules** | Depends on phase 2; flagging is impossible before it |
| 4 | **Room and WorkManager** | Satisfies C1 and C4; makes it a credible application rather than a spike |
| 5 | **Homography and DTW comparison** | Depends on phases 2 and 4 |
| 6 | *Optional:* learned classifier versus rule baseline | Depends on phase 3 for corpus collection |

Phase 1 is the one most easily skipped and the one that matters most. Building five phases atop
an unvalidated heuristic is the principal failure mode available to this project.

---

## 9. Current implementation state

A working spike exists on device. It is proof of concept, not production code.

**Implemented:** video import via the Android photo picker; MediaPipe pose extraction at 5 Hz;
torso-normalised speed; hesitation detection; crux scoring; high-step, dyno and mantle
classification; JSON persistence; a training log with comparison against previous attempts on the
same route; a skeleton renderer; tracking-quality metrics. Seven JVM unit tests pass, including a
regression guard for the coordinate-scale failure in §5.1.

**Not implemented:** everything in §8 phases 2 onward. No hold detection, no homography, no
reconstruction rendering, no Room, no WorkManager, no ghost overlay, no share card.

**Key files.** `Analysis.kt` — domain logic, pure Kotlin, all thresholds. `PoseExtractor.kt` —
the sole point of contact with MediaPipe, the seam described in C2. `MainActivity.kt` — Compose
UI. `AnalysisTest.kt` — JVM tests. `docs/requirements.md` — 18 user stories across 5 epics with
12 non-functional requirements, predating this feature list and requiring reconciliation with it.
`run.sh` — build, install and launch, working around a Xiaomi restriction that blocks
`gradlew installDebug`.

---

## 10. What a report can claim, and what it cannot

**Supported by evidence:** that pose estimation is viable on indoor climbing footage (100%
detection, 0.939 mean confidence); that on-device analysis is practical at 1.24× video duration;
that decoding rather than inference dominates cost; that the pose backend is genuinely swappable;
that the segmentation is consistent across two independent pose models.

**Not yet supported:** that the identified crux is *correct*; that thresholds generalise beyond
one attempt; that hold detection works under gym lighting; that homography alignment is accurate
on non-planar walls; any claim about technique classification accuracy.

The gap between these two lists is the work described in §8. Stating it explicitly is more
persuasive than eliding it — a report that names its own limitations is harder to attack than one
that leaves them for the reader to find.
