# GPU runtime boundary

The application currently renders entirely on CPU. The
`com.shangin.fractal.gpu.GpuRuntime` API establishes the intended owner of future
native GPU state. `FractalRenderService` owns the runtime lifecycle and exposes
capability snapshots without exposing Vulkan handles to JavaFX or the controller.

The macOS implementation checks only whether the LWJGL Vulkan class is present.
It always reports `UNAVAILABLE` with no devices. Loading MoltenVK, discovering
physical devices, querying native features, and GPU-to-CPU fallback are still
pending in 8.1.

## Target matrix (hardware validation pending)

| Platform | Runtime | Initial GPU modes | Required numeric capability | Fallback |
| --- | --- | --- | --- | --- |
| macOS 13+ on Apple Silicon or a Metal-capable AMD GPU | MoltenVK through LWJGL 3 Vulkan bindings | Runtime scaffold only; GPU modes not enabled | `FLOAT32` for palette and base-pass work; `FLOAT64` is never assumed | Direct CPU, then perturbation CPU for deep zoom |
| Windows 10/11 with a Vulkan 1.2 driver | LWJGL 3 + native Vulkan | Not enabled during the macOS-first phase | Same explicit checks | Same matching CPU path |
| Other OS/hardware or unavailable runtime | None | Disabled | N/A | Same matching CPU path |

`FLOAT64` is deliberately not presumed on Apple GPUs. Deep zoom therefore
continues to use the existing perturbation CPU backend until a GPU backend
proves its required precision and conformance. Any device-loss signal changes
the runtime state to `DEVICE_LOST`, so later backend selection must immediately
use the CPU fallback for the active request.

## Packaging

The macOS distribution will need matching LWJGL macOS natives and MoltenVK.
The current Maven profile activates on macOS and includes LWJGL bindings plus
the `natives-macos` classifier. ARM64 natives and MoltenVK packaging and loading
remain pending; the current runtime does not attempt to load native libraries.
