/**
  Two-Way Ranging Controlee — Portenta UWB Shield
  ==================================================
  This is the object being located. No WiFi, no networking of any kind —
  Arduino support has confirmed WiFi/UDP does not reliably coexist with UWB
  on the Portenta C33, so this device does one job only: respond to ranging
  requests from Stella. This is your original working sketch, unmodified.

  METHOD_SEL: 0=DS_TWR, 1=SS_TWR_NO_DEFER, 2=SS_TWR. Must match Stella!
*/
#include <PortentaUWBShield.h>

// ===== IDENTIC cu Stella =====
const int METHOD_SEL = 0;   // 0=DS, 1=SS_NO_DEFER, 2=SS
// =============================

uwb::RangingMethod methodEnum() {
  switch (METHOD_SEL) {
    case 0:  return uwb::RangingMethod::DS_TWR;
    case 1:  return (uwb::RangingMethod)3;        // SS_TWR_NO_DEFER
    default: return uwb::RangingMethod::SS_TWR;
  }
}
const char* methodLabel() {
  switch (METHOD_SEL) {
    case 0:  return "DS";
    case 1:  return "SSND";
    default: return "SS";
  }
}

void rangingHandler(UWBRangingData &rangingData) {
  if (rangingData.measureType() == (uint8_t)uwb::MeasurementType::TWO_WAY) {
    RangingMeasures twr = rangingData.twoWayRangingMeasure();
    for (int j = 0; j < rangingData.available(); j++) {
      if (twr[j].status == 0 && twr[j].distance != 0xFFFF) {
        Serial.print("Distance(cm):");
        Serial.println(twr[j].distance);
      }
    }
  }
}

void setup() {
  Serial.begin(115200);

#if defined(ARDUINO_PORTENTA_C33)
  pinMode(LEDR, OUTPUT);
  pinMode(LEDG, OUTPUT);
  pinMode(LEDB, OUTPUT);
  digitalWrite(LEDR, LOW);
  digitalWrite(LEDG, HIGH);
  digitalWrite(LEDB, HIGH);
#endif

  Serial.print("- Shield Controlee started, method=");
  Serial.print(methodLabel());
  Serial.print(" (enum ");
  Serial.print((int)methodEnum());
  Serial.println(")");

  uint8_t devAddr[] = {0x11, 0x11};
  uint8_t destination[] = {0x22, 0x22};
  UWBMacAddress srcAddr(UWBMacAddress::Size::SHORT, devAddr);
  UWBMacAddress dstAddr(UWBMacAddress::Size::SHORT, destination);

  UWB.registerRangingCallback(rangingHandler);
  UWB.begin();
  while (UWB.state() != 0) delay(10);

  UWBRangingControlee myControlee(0x11223344, srcAddr, dstAddr);
  UWBSessionManager.addSession(myControlee);
  myControlee.rangingParams.rangingRoundUsage(methodEnum());

  uwb::Status initRes = myControlee.init();
  Serial.print("- init() status (0=SUCCESS): ");
  Serial.println((int)initRes);

  uwb::Status startRes = myControlee.start();
  Serial.print("- start() status (0=SUCCESS): ");
  Serial.println((int)startRes);

  if (initRes != uwb::Status::SUCCESS || startRes != uwb::Status::SUCCESS) {
    Serial.println("- !!! init/start a ESUAT !!!");
  }

  Serial.println("- Initialization complete!");
#if defined(ARDUINO_PORTENTA_C33)
  digitalWrite(LEDR, HIGH);
#endif
}

void loop() {
  delay(10);
}
