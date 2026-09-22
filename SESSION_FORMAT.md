# FractalUI session format

FractalUI restores the most recently closed scene at the next normal launch.
The session contains only reproducible scene state; window dimensions, render
priority, in-progress work and transient animation state are not persisted.

## Storage locations

- macOS: `~/Library/Application Support/FractalUI/last-session.json`
- Windows: `%LOCALAPPDATA%\FractalUI\last-session.json`, falling back to
  `%APPDATA%` and then the user profile
- Linux: `$XDG_STATE_HOME/fractalui/last-session.json`, or
  `~/.local/state/fractalui/last-session.json`

The file is saved when the main view closes. A missing or malformed session
does not prevent startup: FractalUI tries the previous valid generation and
then falls back to the default Mandelbrot scene.

## Schema contract

`schemaVersion` is required. Version 2 is the current write format and records:

- the fractal preset;
- viewport center and scale as decimal strings;
- base and per-zoom iteration settings;
- palette identity, editable ARGB stops, color scale, offset, histogram mode
  and orbit trap;
- interactive sampling pattern and render mode.

Decimal viewport values are strings so parsing never passes through binary
floating point. Enum values use stable Java enum identifiers rather than
display labels. Colors use `#AARRGGBB` strings.

Version 1 remains readable. Its palette stops default to the selected preset,
histogram coloring and orbit traps default to off, and interactive
antialiasing uses the regular refined mode. Unsupported future versions and
invalid required values are rejected rather than partially applied.

## Atomic publication and recovery

Each generation is written and forced to a temporary file in the session
directory, then published with an atomic same-directory replacement. FractalUI
does not fall back to a non-atomic replacement on file systems that cannot
provide this guarantee. Before replacement, the current valid primary file is
published independently as `last-session.json.backup`.

An interrupted write therefore leaves either the complete old primary or the
complete new primary. Temporary files are not considered during startup. If
the primary is malformed or unreadable as a scene, the last valid backup is
used. Both JSON documents are bounded to 1 MiB before parsing.
