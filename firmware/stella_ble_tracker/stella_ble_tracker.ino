/**
  Stella — UWB Ranging + BLE Distance Broadcast
  ================================================
  Carried by the user. Does two jobs concurrently:
    1. UWB DS-TWR ranging against the Shield (unchanged ranging logic from
       your original power-calibration sketch, just without the one-shot
       20-sample collection loop — this runs continuously).
    2. Advertises as a BLE peripheral and notifies the current distance (in
       meters, as a float) to whichever phone connects.

  Unlike the Portenta C33 + WiFi combination (confirmed broken by Arduino
  support), Stella's nRF52840 handles BLE and the SR040 UWB chip as two
  separate radios/peripherals with no known coexistence issue - this is
  the board's intended use case as a UWB+BLE tag.

  GATT layout (must match the iOS app):
    Service UUID:        A1E8F400-EE45-4832-A144-6F3E7F0D6B01
    Distance Char UUID:  A1E8F401-EE45-4832-A144-6F3E7F0D6B01  (float, notify)
*/
#include <StellaUWB.h>
#include <ArduinoBLE.h>

#define LED_PIN p37

// Pick your best power setting from the paper's characterization
const uint8_t POWER_ID = 12;   // e.g. +11 dB, worked well at 2m LOS

uint8_t devAddr[]     = {0x22, 0x22};
uint8_t destination[] = {0x11, 0x11};
UWBMacAddress srcAddr(UWBMacAddress::Size::SHORT, devAddr);
UWBMacAddress dstAddr(UWBMacAddress::Size::SHORT, destination);

// ---------- BLE setup ----------
BLEService uwbService("A1E8F400-EE45-4832-A144-6F3E7F0D6B01");
BLEFloatCharacteristic distanceChar("A1E8F401-EE45-4832-A144-6F3E7F0D6B01", BLERead | BLENotify);

volatile float latestDistanceCm = -1;
unsigned long lastBlePush = 0;
const unsigned long BLE_PUSH_PERIOD_MS = 120;   // ~8 Hz, smooth without flooding BLE

void rangingHandler(UWBRangingData &rangingData) {
  if (rangingData.measureType() == (uint8_t)uwb::MeasurementType::TWO_WAY) {
    RangingMeasures twr = rangingData.twoWayRangingMeasure();
    for (int j = 0; j < rangingData.available(); j++) {
      if (twr[j].status == 0 && twr[j].distance != 0xFFFF) {
        latestDistanceCm = (float)twr[j].distance;
      }
    }
  }
}

void setup() {
  Serial.begin(115200);
  // Give Serial Monitor a moment to attach without permanently blocking if
  // nothing's connected (Stella needs to boot untethered too). If a monitor
  // IS open, this makes sure you actually see the boot messages below
  // instead of them firing before the port finishes attaching.
  unsigned long serialWaitStart = millis();
  while (!Serial && millis() - serialWaitStart < 3000) { delay(10); }
  delay(300);

  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);

  // ---- UWB ----
  UWB.registerRangingCallback(rangingHandler);
  UWB.begin();
  while (UWB.state() != 0) delay(10);

  UWBTracker myTracker(0x11223344, srcAddr, dstAddr);
  UWBSessionManager.addSession(myTracker);
  myTracker.rangingParams.rangingRoundUsage(uwb::RangingMethod::DS_TWR);
  myTracker.appParams.powerId(POWER_ID);
  myTracker.init();
  myTracker.start();

  Serial.println("UWB ranging started.");

  // ---- BLE ----
  if (!BLE.begin()) {
    Serial.println("!!! BLE.begin() failed !!!");
    while (1) { digitalWrite(LED_PIN, !digitalRead(LED_PIN)); delay(100); }  // fast blink = fatal
  }

  BLE.setLocalName("UWB-Stella");
  BLE.setAdvertisedService(uwbService);
  uwbService.addCharacteristic(distanceChar);
  BLE.addService(uwbService);
  distanceChar.writeValue(0.0f);
  BLE.advertise();

  Serial.println("BLE advertising as 'UWB-Stella'.");
  digitalWrite(LED_PIN, LOW);   // solid off = running normally
}

unsigned long lastHeartbeat = 0;
const unsigned long HEARTBEAT_MS = 2000;

void printHeartbeat(bool connected) {
  if (millis() - lastHeartbeat < HEARTBEAT_MS) return;
  lastHeartbeat = millis();
  Serial.print("[heartbeat] BLE=");
  Serial.print(connected ? "connected" : "advertising, waiting for phone");
  Serial.print("  latestDistanceCm=");
  Serial.println(latestDistanceCm);
}

void loop() {
  BLEDevice central = BLE.central();

  if (central) {
    Serial.print("Connected to phone: ");
    Serial.println(central.address());
    digitalWrite(LED_PIN, HIGH);  // solid on = phone connected

    while (central.connected()) {
      BLE.poll();
      unsigned long now = millis();
      if (latestDistanceCm >= 0 && now - lastBlePush >= BLE_PUSH_PERIOD_MS) {
        float distM = latestDistanceCm / 100.0f;
        distanceChar.writeValue(distM);
        lastBlePush = now;
      }
      printHeartbeat(true);
    }

    Serial.println("Phone disconnected.");
    digitalWrite(LED_PIN, LOW);
  } else {
    BLE.poll();
    printHeartbeat(false);
  }
}
