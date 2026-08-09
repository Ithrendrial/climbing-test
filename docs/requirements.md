# ClimbSpike — Requirements Specification

**Version 0.1 — 5 August 2026**
Android application for pose-based analysis of indoor bouldering attempts.

---

## 1. Purpose and scope

This document specifies the functional and non-functional requirements for a native Android
application that analyses video of indoor bouldering attempts. The application uses on-device
pose estimation to track a climber through an attempt, segments the attempt into movement
phases, identifies the crux and periods of hesitation, classifies climbing techniques, and
maintains a training log supporting longitudinal comparison between attempts on the same route.

Requirements marked **[E]** are supported by empirical evidence from the technical spike
described in §6, rather than by assumption. This distinction matters: the spike was conducted
specifically to retire feasibility risk before requirements were fixed, and several
non-functional requirements are stated as measured thresholds rather than aspirations.

### 1.1 In scope

Video import from device storage; on-device pose estimation; movement phase segmentation;
crux and hesitation identification; technique classification; persistent training log;
comparison between attempts on the same route.

### 1.2 Out of scope

Live camera capture and real-time analysis; cloud synchronisation or multi-device accounts;
social sharing; route/hold detection by colour (see §7, deferred); coaching prescription or
injury-risk assessment; outdoor climbing; multi-climber analysis.

---

## 2. Personas

### P1 — Improving boulderer (primary)

Climbs indoors two to three times a week at V3–V6. Works projects across multiple sessions
and multiple weeks. Currently tracks progress by memory and by whether a route "felt easier",
which is unreliable across sessions separated by days. Films occasional attempts on a phone
but rarely reviews the footage, because scrubbing a video to find the moment they got stuck
is more effort than the insight is worth.

**Primary need:** objective evidence that a specific section of a route is improving, not just
a general sense that the route feels better.

### P2 — Climbing partner / informal coach (secondary)

Climbs with P1 and offers feedback between attempts. Watches attempts in real time but cannot
recall specifics reliably once several attempts have passed. Wants to point at a specific
moment and discuss technique.

**Primary need:** a shared, concrete reference point for discussing an attempt, rather than
competing recollections of what happened.

---

## 3. Epics

| ID | Epic | Description |
|----|------|-------------|
| E1 | Attempt capture | Getting video of an attempt into the application |
| E2 | Movement analysis | Deriving structure from raw video |
| E3 | Training log | Persisting attempts and comparing them over time |
| E4 | Transparency | Enabling the user to judge whether the analysis is correct |
| E5 | Platform qualities | Offline operation, privacy, performance |

---

## 4. Functional requirements — user stories

Prioritised using MoSCoW. Acceptance criteria are given in Given/When/Then form.

### E1 — Attempt capture

---

**US-01 — Import an attempt video** · *Must*

> As an improving boulderer, I want to select a video of my attempt from my phone, so that I
> can have it analysed without transferring files to a computer.

**Acceptance criteria**

- **Given** the application is open, **when** I choose to add an attempt, **then** the system
  photo picker opens filtered to video files only.
- **Given** I select a video, **when** the picker closes, **then** I am prompted for route
  identity before analysis begins.
- **Given** I decline to grant broad storage access, **when** I import a video, **then** import
  still succeeds. *The Android Photo Picker grants scoped access to the selected item only, so
  no storage permission is requested at any point.*
- **Given** I select a file that is not decodable video, **when** analysis is attempted,
  **then** an error is shown and no entry is written to the log.

---

**US-02 — Label an attempt** · *Must*

> As an improving boulderer, I want to record which route an attempt belongs to, so that
> attempts can later be grouped and compared.

**Acceptance criteria**

- **Given** I have selected a video, **when** I am prompted, **then** I can enter a route name
  and a grade.
- **Given** I leave the route name blank, **when** I confirm, **then** a placeholder is applied
  and analysis proceeds. *Labelling friction at the wall is high; the flow must never block on it.*
- **Given** I enter a route name matching an existing entry, **when** analysis completes,
  **then** the new attempt is grouped with the existing attempts for that route.

---

**US-03 — Record an attempt in-app** · *Could*

> As an improving boulderer, I want to film an attempt from within the application, so that I
> do not have to switch to the camera app and back.

*Deferred. Import satisfies the underlying need; in-app capture is a convenience whose value
should be assessed after the analysis pipeline is validated.*

---

### E2 — Movement analysis

---

**US-04 — Track the climber through the attempt** · *Must* · **[E]**

> As an improving boulderer, I want the application to follow my body through the whole
> attempt, so that subsequent analysis reflects what I actually did.

**Acceptance criteria**

- **Given** a video of a single climber, **when** analysis runs, **then** body landmarks are
  estimated at a minimum of 5 samples per second.
- **Given** a climber is oriented away from the camera or partially occluded, **when** analysis
  runs, **then** tracking continues rather than terminating.
- **Given** analysis completes, **when** results are presented, **then** the proportion of
  frames in which a pose was successfully detected is reported to the user.
- **Given** no person is detected in any frame, **when** analysis completes, **then** the user
  is informed and no entry is written to the log.

*Evidence: measured 182/182 frames tracked (100%) at mean landmark visibility 0.939 on a 36.2 s
V6 attempt. See §6.*

---

**US-05 — Identify periods of hesitation** · *Must*

> As an improving boulderer, I want to see where I paused during an attempt, so that I can
> distinguish sections I climb fluently from sections that cost me time and energy.

**Acceptance criteria**

- **Given** a completed analysis, **when** results are presented, **then** each period where
  body movement fell below the hesitation threshold for at least the minimum duration is
  reported with a start and end time.
- **Given** an attempt with continuous movement, **when** analysis completes, **then** no
  hesitation periods are reported.
- **Given** hesitation periods are reported, **when** I view the attempt, **then** their total
  duration and count are shown alongside the attempt duration.

*Measurement must be invariant to camera distance and climber body size. See NFR-07.*

---

**US-06 — Identify the crux** · *Must*

> As an improving boulderer, I want the single hardest section of my attempt identified, so
> that I know where to focus practice rather than repeating the whole route.

**Acceptance criteria**

- **Given** an attempt containing at least one hesitation period, **when** analysis completes,
  **then** exactly one period is designated the crux.
- **Given** an attempt where the climber pauses to rest and separately pauses while stuck,
  **when** analysis completes, **then** the section where the climber was stuck is preferred
  over the rest. *A crux is characterised by the body being static while the limbs continue
  searching for holds; duration alone is insufficient to distinguish it from a rest.*
- **Given** an attempt with no detectable hesitation, **when** analysis completes, **then** no
  crux is claimed rather than an arbitrary section being designated.

---

**US-07 — Classify techniques used** · *Should*

> As an improving boulderer, I want to see which techniques an attempt involved, so that I can
> relate my training to specific movement skills.

**Acceptance criteria**

- **Given** a completed analysis, **when** results are presented, **then** zero or more
  technique labels are shown from a defined vocabulary.
- **Given** an attempt in which the climber's knee rises above hip height, **when** analysis
  completes, **then** a high-step is reported.
- **Given** an attempt containing a dynamic movement of the whole body, **when** analysis
  completes, **then** a dynamic movement is reported.
- **Given** no technique is confidently identified, **when** results are presented, **then** a
  neutral default is shown rather than a speculative label.

*Note: classification of whole-body translation (dynamic movement) and classification of body
posture (high-step) draw on different coordinate representations and must not be conflated.
See §6.3.*

---

### E3 — Training log

---

**US-08 — Persist analysed attempts** · *Must*

> As an improving boulderer, I want my analysed attempts kept, so that a session's work is
> still available weeks later when I return to a project.

**Acceptance criteria**

- **Given** an attempt has been analysed, **when** analysis completes, **then** it is written
  to persistent storage before results are presented.
- **Given** the application is closed and reopened, **when** I view the log, **then** previously
  analysed attempts are present.
- **Given** stored data is corrupt or unreadable, **when** the application starts, **then** it
  starts with an empty log rather than failing.

---

**US-09 — Review the training log** · *Must*

> As an improving boulderer, I want to see my past attempts listed, so that I can navigate my
> history without recalling dates.

**Acceptance criteria**

- **Given** attempts exist, **when** I open the log, **then** they are listed most recent first,
  showing route, grade, date, and a summary of the analysis.
- **Given** I select an attempt, **when** it opens, **then** the full analysis is shown.

---

**US-10 — Compare attempts on the same route** · *Must*

> As an improving boulderer, I want to compare an attempt against my previous attempts on the
> same route, so that I have objective evidence of whether I am improving.

**Acceptance criteria**

- **Given** two or more attempts exist for a route, **when** I view one, **then** the others are
  shown with their key metrics.
- **Given** a comparison is shown, **when** I read it, **then** the direction of change is
  indicated, with reduced hesitation presented as improvement.
- **Given** only one attempt exists for a route, **when** I view it, **then** this is stated
  plainly rather than shown as an empty comparison.

---

**US-11 — Compare where on the route improvement occurred** · *Should*

> As an improving boulderer, I want to know *which section* of a route improved, so that I can
> tell real progress from simply climbing the whole route faster.

*Attempts on the same route differ in duration, so section-level comparison requires temporal
alignment of the two attempts rather than comparison of summary statistics. Candidate approach:
dynamic time warping over the pose sequences.*

---

**US-12 — Delete an attempt** · *Should*

> As an improving boulderer, I want to remove attempts, so that failed recordings and test data
> do not distort my comparisons.

---

### E4 — Transparency

---

**US-13 — See the tracked movement** · *Should*

> As an improving boulderer, I want to see the skeleton the application tracked, so that I can
> judge whether an analysis I disagree with is based on sound tracking.

**Acceptance criteria**

- **Given** an analysed attempt, **when** I view it, **then** I can scrub through the attempt
  and see the estimated pose at each point.
- **Given** I scrub to a point inside the crux, **when** the pose is shown, **then** it is
  indicated as being within the crux.

*Rationale: the crux is determined heuristically and will sometimes be wrong. A user who cannot
inspect the basis of a conclusion cannot calibrate their trust in it, and will either over-trust
or abandon the feature.*

---

**US-14 — See analysis quality** · *Should* · **[E]**

> As an improving boulderer, I want to know how well the application tracked a particular
> video, so that I can discount conclusions drawn from poor footage.

**Acceptance criteria**

- **Given** an analysed attempt, **when** I view it, **then** detection rate and mean landmark
  confidence are shown.
- **Given** detection rate is low, **when** results are presented, **then** this is surfaced
  rather than buried.

---

**US-15 — Understand why a section was called the crux** · *Could*

> As an improving boulderer, I want to see why a section was identified as the crux, so that I
> can judge whether the reasoning matches my experience.

---

### E5 — Platform qualities

---

**US-16 — Analyse without a network connection** · *Must*

> As an improving boulderer, I want analysis to work in the gym, so that connectivity in a
> basement training facility does not prevent use.

**Acceptance criteria**

- **Given** the device is offline, **when** I analyse an attempt, **then** analysis completes
  normally.
- **Given** the application is installed, **when** it is first run, **then** no model download
  is required. *The pose model is bundled in the application package.*

---

**US-17 — Keep my video on my device** · *Must*

> As an improving boulderer, I want my climbing videos to stay on my phone, so that I am not
> uploading footage of myself to a third party.

**Acceptance criteria**

- **Given** any application operation, **when** it executes, **then** no video, frame, or pose
  data is transmitted off the device.

---

**US-18 — Continue using my phone during analysis** · *Must* · **[E]**

> As an improving boulderer, I want to leave the application while an attempt is analysed, so
> that a minute of processing does not lock up my phone between climbs.

**Acceptance criteria**

- **Given** analysis is running, **when** I leave the application, **then** analysis continues.
- **Given** analysis is running, **when** the application process is terminated by the system,
  **then** analysis resumes or fails cleanly rather than leaving a partial entry.
- **Given** analysis is running, **when** I view its state, **then** progress is reported.

*Evidence: measured 44.7 s to analyse a 36.2 s attempt (1.24× video duration). Analysis
durations of this order cannot be tied to a foreground UI component. See §6.*

---

## 5. Non-functional requirements

| ID | Requirement | Threshold | Basis |
|----|-------------|-----------|-------|
| NFR-01 | Analysis throughput | ≤ 1.5× video duration on a mid-range device | **[E]** Measured 1.24× |
| NFR-02 | Pose detection rate | ≥ 90% of sampled frames on well-framed footage | **[E]** Measured 100% |
| NFR-03 | Landmark confidence | Mean visibility ≥ 0.75 on well-framed footage | **[E]** Measured 0.939 |
| NFR-04 | Sampling resolution | ≥ 5 samples/sec | Hesitations of ~1 s must be resolvable |
| NFR-05 | Offline operation | No network dependency at any point | US-16, US-17 |
| NFR-06 | Data residency | No off-device transmission of user media | US-17 |
| NFR-07 | Measurement invariance | Movement metrics invariant to camera distance and climber size | Required for US-10 to be meaningful |
| NFR-08 | Durability | Analysis survives backgrounding and process death | **[E]** Derived from NFR-01 |
| NFR-09 | Storage growth | Bounded; must not degrade start-up as the log grows | **[E]** ~100 KB pose data per attempt |
| NFR-10 | Testability | Analysis logic executable on the JVM without a device or emulator | **[E]** Realised in spike |
| NFR-11 | Device support | Android 8.0 (API 26) and above | Coverage vs. API surface required |
| NFR-12 | Graceful degradation | Undecodable or person-free video fails without corrupting the log | US-01, US-08 |

### 5.1 Note on NFR-07

Landmark coordinates are expressed relative to the video frame, so a climber filmed from ten
metres away produces smaller coordinate displacements than the same climber filmed from three
metres. Comparing attempts filmed on different days, from different positions, therefore
requires that all movement metrics be normalised against a body-scale reference derived from
the pose itself.

This is a domain invariant rather than an implementation detail. The spike demonstrated that
when this normalisation silently failed, the system did not error — it produced a plausible but
entirely incorrect analysis in which an entire attempt was classified as a single crux. Any
architecture must therefore make this normalisation explicit and testable rather than incidental.

---

## 6. Evidence base — technical spike

A throwaway spike was implemented before this specification was fixed, to establish whether
pose estimation is viable on indoor climbing footage. Requirements marked **[E]** derive from
its measurements.

### 6.1 Conditions

Single attempt, indoor bouldering, graded V6, 36.2 s duration, filmed on a handheld phone in
portrait orientation. Analysis performed on a Redmi Note 14 Pro 5G. Pose estimation by
MediaPipe Pose Landmarker (BlazePose), `full` model variant, VIDEO running mode, sampled at
5 Hz.

### 6.2 Results

| Measure | Result |
|---------|--------|
| Frames sampled | 182 |
| Frames with pose detected | 182 (100%) |
| Frames with resolvable torso reference | 182 (100%) |
| Mean landmark visibility | 0.939 |
| Wall-clock analysis time | 44.7 s (1.24× video duration) |
| Hesitation periods identified | 5, totalling 9.2 s (25% of attempt) |
| Crux identified | 16.6 s – 21.4 s |

The primary feasibility risk — that a pose model trained on upright, forward-facing subjects
would fail on a climber oriented towards a wall — was not realised.

### 6.3 Incidental findings

**Cross-backend agreement.** The spike was implemented first against ML Kit Pose Detection and
subsequently against MediaPipe. The two backends independently produced overlapping crux windows
(15.6–21.8 s and 16.6–21.4 s respectively) and agreed on hesitation periods at 2–3 s and
22–24 s. This provides limited but genuine evidence that the segmentation reflects a property
of the attempt rather than an artefact of one pose model. It does not establish that the
segmentation is *correct*; see §8.

**Coordinate representations are not interchangeable.** MediaPipe exposes landmarks both in
normalised image space and as world landmarks in metres. World landmarks are expressed relative
to the hip centre, and therefore cannot represent translation of the body through space: a
climber performing a dynamic movement appears stationary in them. Whole-body movement must be
derived from image-space coordinates, while body posture is better derived from world
coordinates, where thresholds are physical distances requiring no normalisation.

**Decoding dominates cost.** At 245 ms per sampled frame against an expected model inference
cost of 30–50 ms, approximately 80% of analysis time is video seek and decode rather than
inference. Performance work should therefore target the decoding strategy, not model selection.

**Camera motion affects displacement metrics.** Height gained was measured at 3.65 torso-lengths
(≈ 2 m) for an attempt on a wall exceeding that height. Where footage is filmed handheld and
panned to follow the climber, image-space displacement understates actual travel. Metrics
depending on absolute displacement are unreliable under camera motion; metrics depending on
*relative* rates of movement — including hesitation and crux detection — are not affected.

---

## 7. Deferred scope

| Item | Rationale for deferral |
|------|------------------------|
| Route and hold detection by colour | Substantial independent computer-vision problem. Would enable move-sequence extraction and hold-level feedback. Highest-value extension. |
| Learned technique classification | Requires a labelled corpus. Viable because technique labels are per-move (~15 per attempt) rather than per-attempt; heuristic implementation provides a baseline for comparison. |
| Section-level attempt comparison (US-11) | Requires temporal alignment; depends on US-10 being validated first. |
| In-app video capture (US-03) | Convenience only; import satisfies the need. |
| Additional techniques (heel hook, drop knee, flag) | Extends an established mechanism rather than proving a new one. |

---

## 8. Assumptions and risks

**A1.** Exactly one climber is prominent in frame. Analysis of footage containing multiple
climbers is undefined.

**A2.** The climber remains within frame for the majority of the attempt.

**A3.** Route identity is supplied by the user. The application does not identify routes
independently.

---

**R1 — The crux heuristic is unvalidated against human judgement.** Cross-backend agreement
(§6.3) establishes consistency, not correctness. No comparison has yet been made between the
identified crux and where the climber experienced difficulty. *This is the principal
outstanding risk: the central feature of the application rests on an unvalidated heuristic.*
Mitigation: comparative study over 20–30 attempts with climber-reported crux locations.

**R2 — Thresholds are untuned.** Hesitation and dynamic-movement thresholds were set by
inspection on a single attempt and have not been fitted to a corpus. Mitigation: tune against
labelled attempts collected under R1. Stored pose data permits re-analysis without
re-processing video.

**R3 — Single-condition evidence.** All measurements in §6 derive from one attempt, one route,
one climber, one device. Detection rate under poor lighting, greater camera distance, or
crowded walls is unknown. Mitigation: broaden the corpus during R1.

**R4 — Camera motion.** See §6.3. Mitigation: either constrain guidance to fixed-camera
footage, or restrict metrics to those invariant under camera motion.

**R5 — Subjectivity of the crux.** The hardest section of a route is climber-specific and may
vary between attempts by the same climber as technique changes. The application should present
its identification as evidence for the climber to interpret, not as ground truth.

---

## 9. Traceability

| Original requirement | Realised by |
|----------------------|-------------|
| Users should be able to upload climbing videos | US-01, US-02 |
| Track the climber using pose estimation | US-04, NFR-02, NFR-03, NFR-04 |
| Identify key phases, including crux and hesitation | US-05, US-06, NFR-07 |
| Classify climbing techniques | US-07 |
| Store analyses in a training log and compare with previous attempts | US-08, US-09, US-10, US-11 |

Stories US-13 to US-15 (transparency) and US-16 to US-18 (platform qualities) do not derive
from the original requirements. They were introduced in response to spike findings: US-18 from
measured analysis duration, US-14 from measured tracking-quality variation, and US-13 from the
observation that a silently incorrect analysis is indistinguishable from a correct one without
inspection.
