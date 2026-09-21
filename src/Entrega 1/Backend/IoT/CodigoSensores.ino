#include <Arduino.h>
#include <Wire.h>
#include <math.h>
#include "BluetoothSerial.h"

// =====================================================
// BLUETOOTH
// =====================================================

BluetoothSerial SerialBT;

const char* BLUETOOTH_NAME = "SmartTennis";

// =====================================================
// MPU6050
// =====================================================

const uint8_t MPU_ADDR = 0x68;

const int SDA_PIN = 21;
const int SCL_PIN = 22;

// Registers
#define PWR_MGMT_1   0x6B
#define GYRO_CONFIG  0x1B
#define ACCEL_CONFIG 0x1C
#define ACCEL_XOUT_H 0x3B

// =====================================================
// CONFIGURATION
// =====================================================

// Sampling frequency
const unsigned long SAMPLE_INTERVAL_US = 5000; // 200 Hz

// Stroke detection
const float TRIGGER_ACCEL = 3.0;

// Capture duration
const int CAPTURE_SAMPLES = 200;

// Cooldown after a stroke
const unsigned long COOLDOWN_MS = 1000;

// MPU6050 scales
// Accelerometer ±16g
const float ACCEL_SCALE = 2048.0;

// Gyroscope ±2000 °/s
const float GYRO_SCALE = 16.4;

// =====================================================
// SENSOR VARIABLES
// =====================================================

float ax, ay, az;
float gx, gy, gz;

float accelMagnitude;

// =====================================================
// CAPTURE VARIABLES
// =====================================================

bool capturing = false;

int sampleCount = 0;

float maxAccel = 0.0;
float maxAbsGz = 0.0;

unsigned long lastSampleTime = 0;
unsigned long lastCaptureTime = 0;

// =====================================================
// I2C
// =====================================================

void writeRegister(uint8_t reg, uint8_t value) {

  Wire.beginTransmission(MPU_ADDR);
  Wire.write(reg);
  Wire.write(value);
  Wire.endTransmission();
}

// =====================================================
// INITIALIZE MPU6050
// =====================================================

void setupMPU6050() {

  // Wake up MPU6050
  writeRegister(PWR_MGMT_1, 0x00);

  // Gyroscope ±2000 °/s
  writeRegister(GYRO_CONFIG, 0x18);

  // Accelerometer ±16g
  writeRegister(ACCEL_CONFIG, 0x18);

  Serial.println("MPU6050 configurado.");
}

// =====================================================
// READ MPU6050
// =====================================================

void readMPU6050() {

  Wire.beginTransmission(MPU_ADDR);
  Wire.write(ACCEL_XOUT_H);
  Wire.endTransmission(false);

  Wire.requestFrom(MPU_ADDR, (uint8_t)14);

  if (Wire.available() < 14) {
    return;
  }

  int16_t rawAx = (Wire.read() << 8) | Wire.read();
  int16_t rawAy = (Wire.read() << 8) | Wire.read();
  int16_t rawAz = (Wire.read() << 8) | Wire.read();

  // Temperature
  Wire.read();
  Wire.read();

  int16_t rawGx = (Wire.read() << 8) | Wire.read();
  int16_t rawGy = (Wire.read() << 8) | Wire.read();
  int16_t rawGz = (Wire.read() << 8) | Wire.read();

  // Convert accelerometer
  ax = rawAx / ACCEL_SCALE;
  ay = rawAy / ACCEL_SCALE;
  az = rawAz / ACCEL_SCALE;

  // Convert gyroscope
  gx = rawGx / GYRO_SCALE;
  gy = rawGy / GYRO_SCALE;
  gz = rawGz / GYRO_SCALE;

  // Acceleration magnitude
  accelMagnitude = sqrt(
    ax * ax +
    ay * ay +
    az * az
  );
}

// =====================================================
// STROKE CLASSIFIER
// =====================================================

String classificarGolpe(float acceleration, float gyroZ) {

  // ---------------------------------------------------
  // MODEL GENERATED FROM THE 44 COLLECTED STROKES
  // ---------------------------------------------------

  if (gyroZ <= 837.26) {

    if (acceleration <= 3.79) {

      if (gyroZ <= 631.10) {
        return "BACKHAND";
      }
      else {
        return "SAQUE";
      }

    }
    else {
      return "BACKHAND";
    }

  }
  else {

    if (acceleration <= 5.96) {
      return "SAQUE";
    }

    else {

      if (gyroZ <= 1439.70) {
        return "FOREHAND";
      }
      else {
        return "SAQUE";
      }
    }
  }
}

// =====================================================
// SEND STROKE THROUGH BLUETOOTH
// =====================================================

void enviarGolpeBluetooth(String golpe) {

  // Send only if a phone is connected
  if (SerialBT.hasClient()) {

    SerialBT.println(golpe);

    Serial.print("Bluetooth -> ");
    Serial.println(golpe);

  } else {

    Serial.println("Bluetooth: nenhum celular conectado.");
  }
}

// =====================================================
// START CAPTURE
// =====================================================

void iniciarCaptura() {

  capturing = true;

  sampleCount = 0;

  maxAccel = 0.0;
  maxAbsGz = 0.0;

  Serial.println();
  Serial.println("================================");
  Serial.println("GOLPE DETECTADO!");
  Serial.println("Capturando...");
  Serial.println("================================");

  if (SerialBT.hasClient()) {
    SerialBT.println("CAPTURANDO");
  }
}

// =====================================================
// PROCESS SAMPLE
// =====================================================

void processarAmostra() {

  // Maximum acceleration
  if (accelMagnitude > maxAccel) {
    maxAccel = accelMagnitude;
  }

  // Maximum absolute Z gyro
  float absGz = fabs(gz);

  if (absGz > maxAbsGz) {
    maxAbsGz = absGz;
  }

  sampleCount++;

  // ---------------------------------------------------
  // END OF CAPTURE
  // ---------------------------------------------------

  if (sampleCount >= CAPTURE_SAMPLES) {

    capturing = false;

    Serial.println();
    Serial.println("================================");
    Serial.println("CAPTURA FINALIZADA");
    Serial.println("================================");

    Serial.print("Max aceleracao: ");
    Serial.print(maxAccel, 2);
    Serial.println(" g");

    Serial.print("Max |Gz|: ");
    Serial.print(maxAbsGz, 2);
    Serial.println(" deg/s");

    // Classify
    String golpe = classificarGolpe(
      maxAccel,
      maxAbsGz
    );

    Serial.println();
    Serial.println("--------------------------------");
    Serial.print("GOLPE IDENTIFICADO: ");
    Serial.println(golpe);
    Serial.println("--------------------------------");

    // -------------------------------------------------
    // SEND RESULT TO ANDROID
    // -------------------------------------------------

    enviarGolpeBluetooth(golpe);

    Serial.println();

    // Start cooldown
    lastCaptureTime = millis();
  }
}

// =====================================================
// SETUP
// =====================================================

void setup() {

  Serial.begin(115200);

  delay(1000);

  Serial.println();
  Serial.println("================================");
  Serial.println("       SMARTTENNIS");
  Serial.println(" Classificador de Golpes");
  Serial.println("================================");

  // ---------------------------------------------------
  // BLUETOOTH
  // ---------------------------------------------------

  if (!SerialBT.begin(BLUETOOTH_NAME)) {

    Serial.println("ERRO ao iniciar Bluetooth!");

  } else {

    Serial.println("Bluetooth iniciado!");
    Serial.print("Nome do dispositivo: ");
    Serial.println(BLUETOOTH_NAME);
  }

  // ---------------------------------------------------
  // I2C
  // ---------------------------------------------------

  Wire.begin(SDA_PIN, SCL_PIN);

  Wire.setClock(400000);

  // ---------------------------------------------------
  // MPU6050
  // ---------------------------------------------------

  setupMPU6050();

  delay(1000);

  Serial.println();
  Serial.println("Sistema pronto.");
  Serial.println("Aguardando golpe...");
  Serial.println("Bluetooth: SmartTennis");
}

// =====================================================
// LOOP
// =====================================================

void loop() {

  unsigned long currentMicros = micros();

  // Maintain approximately 200 Hz
  if (currentMicros - lastSampleTime < SAMPLE_INTERVAL_US) {
    return;
  }

  lastSampleTime = currentMicros;

  // ---------------------------------------------------
  // READ SENSOR
  // ---------------------------------------------------

  readMPU6050();

  // ---------------------------------------------------
  // CAPTURING
  // ---------------------------------------------------

  if (capturing) {

    processarAmostra();

    return;
  }

  // ---------------------------------------------------
  // COOLDOWN
  // ---------------------------------------------------

  if (millis() - lastCaptureTime < COOLDOWN_MS) {
    return;
  }

  // ---------------------------------------------------
  // DETECT NEW STROKE
  // ---------------------------------------------------

  if (accelMagnitude >= TRIGGER_ACCEL) {

    iniciarCaptura();
  }
}