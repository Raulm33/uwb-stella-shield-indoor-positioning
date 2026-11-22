import Foundation
import CoreLocation
import Combine

/// Provides the phone's compass heading (0-360, 0 = North).
final class HeadingManager: NSObject, ObservableObject {
    @Published var headingDeg: Double? = nil
    @Published var authorizationDenied = false

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
    }

    func start() {
        manager.requestWhenInUseAuthorization()
        if CLLocationManager.headingAvailable() {
            manager.startUpdatingHeading()
        }
    }
}

extension HeadingManager: CLLocationManagerDelegate {
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            manager.startUpdatingHeading()
        case .denied, .restricted:
            authorizationDenied = true
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard newHeading.headingAccuracy >= 0 else { return }
        let heading = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        DispatchQueue.main.async {
            self.headingDeg = heading
        }
    }
}
