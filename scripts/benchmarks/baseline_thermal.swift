import Foundation

// OS pressure state, not a temperature sensor or a CPU/GPU frequency measurement.
switch ProcessInfo.processInfo.thermalState {
case .nominal: print("nominal")
case .fair: print("fair")
case .serious: print("serious")
case .critical: print("critical")
@unknown default: print("unknown")
}
