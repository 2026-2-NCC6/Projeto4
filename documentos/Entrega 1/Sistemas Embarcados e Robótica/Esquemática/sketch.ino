#include <Arduino.h>
#include <Wire.h>
#include <WiFi.h>
#include <math.h>

//Hardware

const uint8_t MPU_ADDR = 0x68;

const int SDA_PIN = 21;
const int SCL_PIN = 22;
// Analog input
const int PIEZO_PIN = 34;      
// Botão para testes
const int IMPACT_BUTTON = 4;   
const int LED_PIN = 2;
// Registradores MPU-6050
const uint8_t PWR_MGMT_1   = 0x6B;
const uint8_t GYRO_CONFIG  = 0x1B;
const uint8_t ACCEL_CONFIG = 0x1C;
const uint8_t ACCEL_XOUT_H = 0x3B;
// Configuração MPU
// ±16 g
const float ACCEL_SCALE = 2048.0;

// ±2000 degraus/segundo
const float GYRO_SCALE = 16.4;

// Configuração PIEZO

const int IMPACT_THRESHOLD = 2500;

// Evita multiplas detecções
const unsigned long IMPACT_COOLDOWN = 300;

unsigned long lastImpactTime = 0;

//Wifi

const char* WIFI_SSID = "Wokwi-GUEST";
const char* WIFI_PASSWORD = "";

//Dados do sensor

struct MotionData {

    float ax;
    float ay;
    float az;

    float gx;
    float gy;
    float gz;

    float accelerationMagnitude;
    float rotationMagnitude;
};

MotionData motion;

// MPU-6050 WRITE

bool writeRegister(uint8_t reg, uint8_t value) {

    Wire.beginTransmission(MPU_ADDR);

    Wire.write(reg);
    Wire.write(value);

    return Wire.endTransmission() == 0;
}

// MPU-6050 READ

bool readMotionData() {

    Wire.beginTransmission(MPU_ADDR);
    Wire.write(ACCEL_XOUT_H);

    if (Wire.endTransmission(false) != 0) {
        return false;
    }

    uint8_t received = Wire.requestFrom(
        MPU_ADDR,
        (uint8_t)14,
        (uint8_t)true
    );

    if (received != 14) {
        return false;
    }

    int16_t rawAx =
        (Wire.read() << 8) | Wire.read();

    int16_t rawAy =
        (Wire.read() << 8) | Wire.read();

    int16_t rawAz =
        (Wire.read() << 8) | Wire.read();

    // Temperature
    Wire.read();
    Wire.read();

    int16_t rawGx =
        (Wire.read() << 8) | Wire.read();

    int16_t rawGy =
        (Wire.read() << 8) | Wire.read();

    int16_t rawGz =
        (Wire.read() << 8) | Wire.read();

    // Convert raw values

    motion.ax = rawAx / ACCEL_SCALE;
    motion.ay = rawAy / ACCEL_SCALE;
    motion.az = rawAz / ACCEL_SCALE;

    motion.gx = rawGx / GYRO_SCALE;
    motion.gy = rawGy / GYRO_SCALE;
    motion.gz = rawGz / GYRO_SCALE;

    // Calculate acceleration magnitude

    motion.accelerationMagnitude =
        sqrt(
            motion.ax * motion.ax +
            motion.ay * motion.ay +
            motion.az * motion.az
        );

    // Calculate rotation magnitude

    motion.rotationMagnitude =
        sqrt(
            motion.gx * motion.gx +
            motion.gy * motion.gy +
            motion.gz * motion.gz
        );

    return true;
}

// WIFI

void connectWiFi() {

    Serial.println();
    Serial.println("Connecting to Wi-Fi...");

    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    int attempts = 0;

    while (
        WiFi.status() != WL_CONNECTED &&
        attempts < 50
    ) {

        delay(200);

        Serial.print(".");

        attempts++;
    }

    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {

        Serial.println("Wi-Fi connected!");

        Serial.print("IP address: ");
        Serial.println(WiFi.localIP());

    } else {

        Serial.println("Wi-Fi connection failed.");
    }
}
// READ PIEZO

int readPiezo() {

    return analogRead(PIEZO_PIN);
}

// CHECK IMPACT

bool impactDetected() {

    int piezoValue = readPiezo();

    bool piezoImpact =
        piezoValue >= IMPACT_THRESHOLD;

    bool buttonImpact =
        digitalRead(IMPACT_BUTTON) == HIGH;

    bool cooldownFinished =
        millis() - lastImpactTime >
        IMPACT_COOLDOWN;

    if (
        (piezoImpact || buttonImpact) &&
        cooldownFinished
    ) {

        lastImpactTime = millis();

        return true;
    }

    return false;
}

// STROKE CLASSIFICATION

String classifyStroke() {

    /*
        FIRST PROTOTYPE ONLY

        Y acceleration:
        Positive → upward movement
        Negative → downward movement

        Z gyroscope:
        Rotation around the racket axis.

        These thresholds will need to be
        calibrated with real tennis data.
    */

    float upwardMotion = motion.ay;

    float spinMotion =
        fabs(motion.gz);

    // TOPSPIN

    if (
        upwardMotion > 0.40 &&
        spinMotion > 250
    ) {

        return "TOPSPIN";
    }

    // SLICE

    if (
        upwardMotion < -0.40 &&
        spinMotion > 250
    ) {

        return "SLICE";
    }

    // FLAT

    if (
        spinMotion < 250
    ) {

        return "FLAT";
    }

    return "UNKNOWN";
}

// PROCESS SHOT

void processImpact() {

    // Read the sensor at impact

    if (!readMotionData()) {

        Serial.println(
            "ERROR: Could not read MPU-6050."
        );

        return;
    }

    int piezoValue =
        readPiezo();

    String stroke =
        classifyStroke();

    // LED ON

    digitalWrite(
        LED_PIN,
        HIGH
    );

    Serial.println();
    Serial.println(
        "================================"
    );

    Serial.println(
        "       TENNIS SHOT"
    );

    Serial.println(
        "================================"
    );

    // --------------------------------
    // IMPACT
    // --------------------------------

    Serial.print(
        "Impact detected by: "
    );

    if (
        piezoValue >=
        IMPACT_THRESHOLD
    ) {

        Serial.println(
            "PIEZO"
        );

    } else {

        Serial.println(
            "BUTTON"
        );
    }

    Serial.print(
        "Piezo value: "
    );

    Serial.println(
        piezoValue
    );

    // CLASSIFICATION

    Serial.print(
        "Detected stroke: "
    );

    Serial.println(
        stroke
    );

    Serial.println();

    // ACCELEROMETER

    Serial.print(
        "Acceleration X: "
    );

    Serial.print(
        motion.ax,
        3
    );

    Serial.println(
        " g"
    );

    Serial.print(
        "Acceleration Y: "
    );

    Serial.print(
        motion.ay,
        3
    );

    Serial.println(
        " g"
    );

    Serial.print(
        "Acceleration Z: "
    );

    Serial.print(
        motion.az,
        3
    );

    Serial.println(
        " g"
    );

    Serial.print(
        "Acceleration magnitude: "
    );

    Serial.print(
        motion.accelerationMagnitude,
        3
    );

    Serial.println(
        " g"
    );

    // GYROSCOPE

    Serial.print(
        "Gyro X: "
    );

    Serial.print(
        motion.gx,
        1
    );

    Serial.println(
        " deg/s"
    );

    Serial.print(
        "Gyro Y: "
    );

    Serial.print(
        motion.gy,
        1
    );

    Serial.println(
        " deg/s"
    );

    Serial.print(
        "Gyro Z: "
    );

    Serial.print(
        motion.gz,
        1
    );

    Serial.println(
        " deg/s"
    );

    Serial.print(
        "Rotation magnitude: "
    );

    Serial.print(
        motion.rotationMagnitude,
        1
    );

    Serial.println(
        " deg/s"
    );

    // CAMERA

    Serial.println();

    Serial.println(
        "Camera data:"
    );

    Serial.println(
        "Ball speed: waiting..."
    );

    Serial.println(
        "Ball spin: waiting..."
    );

    Serial.println(
        "================================"
    );

    Serial.println();

    // Keep LED on briefly

    delay(300);

    digitalWrite(
        LED_PIN,
        LOW
    );
}

// SETUP

void setup() {

    Serial.begin(115200);

    // GPIO

    pinMode(
        IMPACT_BUTTON,
        INPUT_PULLDOWN
    );

    pinMode(
        LED_PIN,
        OUTPUT
    );

    digitalWrite(
        LED_PIN,
        LOW
    );

    // ADC

    pinMode(
        PIEZO_PIN,
        INPUT
    );

    // I2C

    Wire.begin(
        SDA_PIN,
        SCL_PIN
    );

    Serial.println();

    Serial.println(
        "================================"
    );

    Serial.println(
        "       SMART TENNIS RACKET"
    );

    Serial.println(
        "================================"
    );

    // MPU POWER

    if (
        !writeRegister(
            PWR_MGMT_1,
            0x00
        )
    ) {

        Serial.println(
            "ERROR: MPU-6050 not found!"
        );

        return;
    }

    // GYRO ±2000 deg/s

    writeRegister(
        GYRO_CONFIG,
        0x18
    );

    // ACCEL ±16g

    writeRegister(
        ACCEL_CONFIG,
        0x18
    );

    Serial.println(
        "MPU-6050 initialized."
    );

    Serial.println(
        "Accelerometer: ±16g"
    );

    Serial.println(
        "Gyroscope: ±2000 deg/s"
    );

    Serial.println();

    // WIFI

    connectWiFi();

    Serial.println();

    Serial.println(
        "System ready."
    );

    Serial.println(
        "Turn the piezo simulation"
    );

    Serial.println(
        "above the threshold to"
    );

    Serial.println(
        "simulate a racket impact."
    );

    Serial.println();

    Serial.print(
        "Impact threshold: "
    );

    Serial.println(
        IMPACT_THRESHOLD
    );
}

// LOOP

void loop() {

    // Keep MPU data updated

    readMotionData();

    // Print piezo value occasionally

    static unsigned long lastPrint = 0;

    if (
        millis() - lastPrint >= 500
    ) {

        lastPrint = millis();

        Serial.print(
            "Piezo: "
        );

        Serial.println(
            readPiezo()
        );
    }

    // Detect impact

    if (
        impactDetected()
    ) {

        processImpact();

        // Wait for the button to be released
        // if the button caused the event.

        while (
            digitalRead(IMPACT_BUTTON) == HIGH
        ) {

            delay(10);
        }
    }

    delay(5);
}