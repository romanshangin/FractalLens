# Explore Julia Parameters latency diagnostic

`JuliaExplorerLatencyBenchmark` opens **Render > Explore Julia Parameters…** in a
visible window and drives the production map handlers: opening the map, clicks
and drags. It separates the GPU work, the CPU image path and JavaFX publication
of every animation frame. The normal application leaves recording disabled.
Results are in [the measurement report](JULIA_EXPLORER_LATENCY_RESULTS.md).

## Workload

- The main window shows the Julia preset with the ICE palette at its initial
  view. The lower 16 logical points of the window hold the diagnostic strip.
- Logical canvas sizes `1200x760` and `1680x1000` (default
  `fractal.juliaLatency.sizes`); on a 2x Retina display these are 2400x1520
  and 3360x2000 physical pixels. The map window opens over the upper-left
  part of the main window, as in the application.
- Iteration limits 300 and 1000 (`fractal.juliaLatency.iterations`), with the
  default 50 iterations per zoom level, which adds nothing at the initial view.
- Trial endpoints alternate between map points (135, 83) and (190, 36), about
  72 logical map points apart: c ≈ −0.8065+0.1571i (dendrite) and
  c ≈ −0.1154+0.7476i (near the rabbit). The exact constants are saved in each
  CSV `# config` line.
- `click`: press at the target, release 80 ms later.
- `drag`: press at the current point, 48 drag events every 16 ms (the first
  5 map points along the path, beyond the 4-point click threshold, then evenly
  to the target), a 250 ms hold without events, release at the target.
- Each size and limit first closes the map, applies the limit and the start
  constant, waits for the main view to settle and records the opening of a new
  map (`open`). The first opening in a JVM is recorded as `cold`. With
  `fractal.juliaLatency.cold=drag` the first opening instead starts the drag in
  the same FX event as the opening (`open-drag`).
- Per size and limit: `fractal.juliaLatency.warmup` (2) and `samples` (10)
  rounds of the gestures in `fractal.juliaLatency.gestures` (`click,drag`).
- A trial ends when the map has committed the target constant, the view is idle
  and shows that constant. The driver then checks that the committed scene holds
  exactly the target constant; a missing commit or another constant fails the run.

Input is synthetic JavaFX event dispatch on the map's root node: mouse hardware
and OS input delivery are excluded. Real pointer motion is not uniform either,
so per-input numbers describe this fixed input pattern only.

## Measurement boundaries

Each frame carries the input count at its render start and a render id. The
response to input k is the first frame whose render started at or after input
k, so an earlier frame is never attributed to a later input.

- `input_map_press`, `input_map_drag`, `input_map_release`: entry into the map's
  JavaFX handlers. `map_committed`: the map commits the constant to the scene.
- `render_start`: the map's animation timer submits a frame to the controller.
- `gpu_worker_start`: the single Julia GPU worker takes the request.
- `gpu_call`: the whole runtime call, including session or kernel creation on
  first use. Within it, `gpu_setup` (buffer allocation), `gpu_upload` (axes and
  palette), `gpu_dispatch` (encoding, submission and fence wait, kernel included)
  and `gpu_readback` (copy of the ARGB output from shared memory into the
  `int[]`) are host wall-clock times reported by the kernel.
  `gpu_call − gpu_kernel_total` is runtime overhead outside the kernel.
- `bright_filter`: the CPU bright-point filter on the worker. Recorded only by
  the code state of PR #87; since the filter became a GPU pass, `gpu_dispatch`
  includes it and this stage no longer exists.
- `gpu_ready` → `gpu_callback`: the result waits in the FX queue.
- `approximate_copy`: the `int[]` copy into the visible `PixelBuffer` and its
  `updateBuffer` call on the FX thread.
- `approximate_publish`: the frame has become the displayed image.
- `approximate_publish_post_layout`: the main scene's post-layout pulse listener
  sees the published frame. Only the last frame before a pulse is attributed to
  it; a superseded frame has no pulse. **A JavaFX pulse is not a display
  timestamp**: Prism paints and presents after it.
- `approximate_publish_screen_observed` (screen runs only): JavaFX Robot capture
  decodes the frame's 12-bit code from the strip. This is a compositor-capture
  proxy, not panel scanout. Capture runs on the FX thread, takes tens of
  milliseconds on this display and slows the animation: screen runs are
  perturbed proxies, never a replacement for the control run.

Frame intervals are differences between consecutive frames of one trial at the
publication, pulse and screen boundaries. Release metrics use the last frame
published before the commit as the final frame.

### Prism painting

Prism texture upload, drawing and presentation run on the render thread after the
pulse and are not visible to the recorder. A separate diagnostic run sets
`-Djavafx.pulseLogger=true -Djavafx.pulseLogger.threshold=0`; the summary script
takes the *Painting* and *Presenting* phases of pulses whose dirty region is the
whole main window. The logger reports whole milliseconds and writes every pulse
to standard output, so its runs are not used for latency numbers.

## Running

Run on an active, unlocked desktop session, on AC power, without other tests or
benchmarks, under `caffeinate -di` (macOS) around the whole campaign:

```sh
./mvnw -q -DskipTests compile

JAVA_TOOL_OPTIONS='-Xmx2g -Djavafx.cachedir=${TEMP_DIR}/fractallens-fx-julia-latency -Dfractal.juliaLatency.noScreen=true -Dfractal.juliaLatency.output=<private>/control.csv' \
  caffeinate -di ./mvnw -q -Pjulia-explorer-latency javafx:run

python3 scripts/benchmarks/summarize_julia_explorer_latency.py <private>/control.csv --output <private>/control-summary.csv
python3 scripts/benchmarks/summarize_julia_explorer_latency.py <private>/cold-open-*.csv --output <private>/cold-open-summary.csv
python3 scripts/benchmarks/summarize_julia_explorer_latency.py --pulse-log <private>/pulse.log --output <private>/pulse-summary.csv
```

Cold starts use one JVM per trial, for example
`-Dfractal.juliaLatency.sizes=1680x1000 -Dfractal.juliaLatency.iterations=300 -Dfractal.juliaLatency.samples=0 -Dfractal.juliaLatency.warmup=0 -Dfractal.juliaLatency.cold=open`
(or `cold=drag`). The summary pools several inputs only when they share the
screen-probe setting, excludes warmups unless `--include-warmup` is given,
reports nearest-rank p95 and rejects failed or uncommitted recordings. A screen
run omits `noScreen` and needs screen-recording permission; the driver refuses to
start when it cannot read the strip's calibration sentinel.

The GPU runtime must be enabled (the default on macOS); without it the map does
not open and the run fails.
