import Foundation
import CoreBluetooth
import Combine

/// Scans for, connects to, and receives distance notifications from Stella.
final class BLEManager: NSObject, ObservableObject {
    static let serviceUUID = CBUUID(string: "A1E8F400-EE45-4832-A144-6F3E7F0D6B01")
    static let distanceCharUUID = CBUUID(string: "A1E8F401-EE45-4832-A144-6F3E7F0D6B01")

    @Published var distanceM: Double? = nil
    @Published var connectionState: ConnectionState = .disconnected

    enum ConnectionState: Equatable {
        case disconnected
        case scanning
        case connecting
        case connected
        case bluetoothOff
    }

    private var centralManager: CBCentralManager!
    private var stellaPeripheral: CBPeripheral?

    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    func startScanning() {
        guard centralManager.state == .poweredOn else { return }
        connectionState = .scanning
        centralManager.scanForPeripherals(withServices: [Self.serviceUUID], options: nil)
    }

    func stopScanning() {
        centralManager.stopScan()
    }
}

extension BLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            startScanning()
        case .poweredOff, .unauthorized, .unsupported:
            connectionState = .bluetoothOff
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager,
                         didDiscover peripheral: CBPeripheral,
                         advertisementData: [String: Any],
                         rssi RSSI: NSNumber) {
        stopScanning()
        stellaPeripheral = peripheral
        peripheral.delegate = self
        connectionState = .connecting
        central.connect(peripheral, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectionState = .connected
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectionState = .disconnected
        distanceM = nil
        // Auto-retry - Stella may have gone briefly out of range.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.startScanning()
        }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectionState = .disconnected
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.startScanning()
        }
    }
}

extension BLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == Self.serviceUUID {
            peripheral.discoverCharacteristics([Self.distanceCharUUID], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        for char in chars where char.uuid == Self.distanceCharUUID {
            peripheral.setNotifyValue(true, for: char)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == Self.distanceCharUUID,
              let data = characteristic.value,
              data.count == 4 else { return }
        let value = data.withUnsafeBytes { $0.load(as: Float32.self) }
        DispatchQueue.main.async {
            self.distanceM = Double(value)
        }
    }
}
