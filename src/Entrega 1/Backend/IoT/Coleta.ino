#include <Wire.h>
#include <math.h>

// ========================================
// MPU6050
// ========================================

#define MPU_ADDR 0x68

#define SDA_PIN 21
#define SCL_PIN 22

#define PWR_MGMT_1   0x6B
#define GYRO_CONFIG  0x1B
#define ACCEL_CONFIG 0x1C
#define ACCEL_XOUT_H 0x3B

// ========================================
// MPU configuration
// ========================================

// ±16 g
const float ACCEL_SCALE = 2048.0;

// ±2000 °/s
const float GYRO_SCALE = 16.4;

// ========================================
// Sampling
// ========================================

const unsigned long SAMPLE_INTERVAL = 5; // 200 Hz

// ========================================
// Swing detection
// ========================================

const float ACCEL_THRESHOLD = 3.0;

// ========================================
// Capture window
// ========================================

// 300 ms BEFORE threshold
const int PRE_TRIGGER_SAMPLES = 60;

// 700 ms AFTER threshold
const int POST_TRIGGER_SAMPLES = 140;

// Total = 200 samples = 1 second
const int TOTAL_SAMPLES =
PRE_TRIGGER_SAMPLES + POST_TRIGGER_SAMPLES;

// ========================================
// Cooldown
// ========================================

const unsigned long COOLDOWN = 1000;


// ========================================
// Data structure
// ========================================

struct SensorData {

    unsigned long timestamp;

    float ax;
    float ay;
    float az;

    float gx;
    float gy;
    float gz;

    float accelerationMagnitude;
};


// ========================================
// Buffers
// ========================================

SensorData buffer[TOTAL_SAMPLES];

int bufferIndex = 0;

bool recording = false;

int postTriggerCount = 0;

unsigned long lastTrigger = 0;

unsigned long lastSampleTime = 0;


// ========================================
// Write MPU register
// ========================================

void writeRegister(uint8_t reg, uint8_t value) {

    Wire.beginTransmission(MPU_ADDR);

    Wire.write(reg);
    Wire.write(value);

    Wire.endTransmission();
}


// ========================================
// Read MPU
// ========================================

bool readMPU(
    int16_t &ax,
    int16_t &ay,
    int16_t &az,
    int16_t &gx,
    int16_t &gy,
    int16_t &gz
) {

    Wire.beginTransmission(MPU_ADDR);

    Wire.write(ACCEL_XOUT_H);

    if (Wire.endTransmission(false) != 0) {
        return false;
    }

    Wire.requestFrom(MPU_ADDR, 14, true);

    if (Wire.available() < 14) {
        return false;
    }

    ax = (Wire.read() << 8) | Wire.read();
    ay = (Wire.read() << 8) | Wire.read();
    az = (Wire.read() << 8) | Wire.read();

    // Temperature - ignored
    Wire.read();
    Wire.read();

    gx = (Wire.read() << 8) | Wire.read();
    gy = (Wire.read() << 8) | Wire.read();
    gz = (Wire.read() << 8) | Wire.read();

    return true;
}


// ========================================
// Add sample to buffer
// ========================================

void storeSample(
    unsigned long timestamp,
    float ax,
    float ay,
    float az,
    float gx,
    float gy,
    float gz
) {

    SensorData data;

    data.timestamp = timestamp;

    data.ax = ax;
    data.ay = ay;
    data.az = az;

    data.gx = gx;
    data.gy = gy;
    data.gz = gz;

    data.accelerationMagnitude =
    sqrt(
        ax * ax +
        ay * ay +
        az * az
    );

    // Circular buffer before trigger
    if (!recording) {

        buffer[bufferIndex] = data;

        bufferIndex++;

        if (bufferIndex >= PRE_TRIGGER_SAMPLES) {
            bufferIndex = 0;
        }
    }

    // Recording after trigger
    else {

        if (
            PRE_TRIGGER_SAMPLES +
            postTriggerCount <
            TOTAL_SAMPLES
        ) {

            buffer[
                PRE_TRIGGER_SAMPLES +
                postTriggerCount
            ] = data;

            postTriggerCount++;
        }
    }
}


// ========================================
// Print captured swing
// ========================================

void printSwing() {

    Serial.println();
    Serial.println("========================================");
    Serial.println("           SWING CAPTURED");
    Serial.println("========================================");

    Serial.println(
        "timestamp,ax,ay,az,gx,gy,gz,accelMagnitude"
    );

    // ----------------------------------------
    // Print circular buffer in correct order
    // ----------------------------------------

    int startIndex = bufferIndex;

    for (int i = 0; i < PRE_TRIGGER_SAMPLES; i++) {

        int index =
        (startIndex + i) %
        PRE_TRIGGER_SAMPLES;

        SensorData &d = buffer[index];

        Serial.print(d.timestamp);
        Serial.print(",");

        Serial.print(d.ax, 4);
        Serial.print(",");

        Serial.print(d.ay, 4);
        Serial.print(",");

        Serial.print(d.az, 4);
        Serial.print(",");

        Serial.print(d.gx, 2);
        Serial.print(",");

        Serial.print(d.gy, 2);
        Serial.print(",");

        Serial.print(d.gz, 2);
        Serial.print(",");

        Serial.println(
            d.accelerationMagnitude,
            4
        );
    }

    // ----------------------------------------
    // Print post-trigger data
    // ----------------------------------------

    for (int i = 0; i < postTriggerCount; i++) {

        SensorData &d =
        buffer[PRE_TRIGGER_SAMPLES + i];

        Serial.print(d.timestamp);
        Serial.print(",");

        Serial.print(d.ax, 4);
        Serial.print(",");

        Serial.print(d.ay, 4);
        Serial.print(",");

        Serial.print(d.az, 4);
        Serial.print(",");

        Serial.print(d.gx, 2);
        Serial.print(",");

        Serial.print(d.gy, 2);
        Serial.print(",");

        Serial.print(d.gz, 2);
        Serial.print(",");

        Serial.println(
            d.accelerationMagnitude,
            4
        );
    }

    Serial.println("========================================");
    Serial.println("Waiting for next swing...");
    Serial.println();
}


// ========================================
// Setup
// ========================================

void setup() {

    Serial.begin(115200);

    Wire.begin(SDA_PIN, SCL_PIN);

    delay(100);

    // Wake MPU6050
    writeRegister(
        PWR_MGMT_1,
        0x00
    );

    // Gyroscope ±2000 °/s
    writeRegister(
        GYRO_CONFIG,
        0x18
    );

    // Accelerometer ±16 g
    writeRegister(
        ACCEL_CONFIG,
        0x18
    );

    delay(100);

    Serial.println();
    Serial.println("========================================");
    Serial.println("       SMART TENNIS DATA LOGGER");
    Serial.println("========================================");
    Serial.println();

    Serial.println("Sampling: 200 Hz");
    Serial.println("Accelerometer: ±16 g");
    Serial.println("Gyroscope: ±2000 °/s");

    Serial.print("Trigger: ");
    Serial.print(ACCEL_THRESHOLD);
    Serial.println(" g");

    Serial.println();

    Serial.println(
        "Waiting for a swing..."
    );
}


// ========================================
// Main loop
// ========================================

void loop() {

    unsigned long now = millis();

    // ----------------------------------------
    // Maintain 200 Hz
    // ----------------------------------------

    if (
        now - lastSampleTime <
        SAMPLE_INTERVAL
    ) {
        return;
    }

    lastSampleTime = now;

    // ----------------------------------------
    // Read MPU
    // ----------------------------------------

    int16_t rawAX;
    int16_t rawAY;
    int16_t rawAZ;

    int16_t rawGX;
    int16_t rawGY;
    int16_t rawGZ;

    if (
        !readMPU(
            rawAX,
            rawAY,
            rawAZ,
            rawGX,
            rawGY,
            rawGZ
        )
    ) {
        return;
    }

    // ----------------------------------------
    // Convert values
    // ----------------------------------------

    float ax =
    rawAX / ACCEL_SCALE;

    float ay =
    rawAY / ACCEL_SCALE;

    float az =
    rawAZ / ACCEL_SCALE;

    float gx =
    rawGX / GYRO_SCALE;

    float gy =
    rawGY / GYRO_SCALE;

    float gz =
    rawGZ / GYRO_SCALE;

    float accelerationMagnitude =
    sqrt(
        ax * ax +
        ay * ay +
        az * az
    );


    // ========================================
    // NOT CURRENTLY RECORDING
    // ========================================

    if (!recording) {

        // Store continuously in circular buffer
        SensorData data;

        data.timestamp = now;

        data.ax = ax;
        data.ay = ay;
        data.az = az;

        data.gx = gx;
        data.gy = gy;
        data.gz = gz;

        data.accelerationMagnitude =
        accelerationMagnitude;

        buffer[bufferIndex] = data;

        bufferIndex++;

        if (
            bufferIndex >=
            PRE_TRIGGER_SAMPLES
        ) {
            bufferIndex = 0;
        }


        // ----------------------------------------
        // Detect swing
        // ----------------------------------------

        if (
            accelerationMagnitude >=
            ACCEL_THRESHOLD
            &&
            now - lastTrigger >=
            COOLDOWN
        ) {

            lastTrigger = now;

            recording = true;

            postTriggerCount = 0;

            Serial.println();
            Serial.println(
                ">>> SWING DETECTED <<<"
            );
        }
    }


    // ========================================
    // CURRENTLY RECORDING
    // ========================================

    else {

        SensorData data;

        data.timestamp = now;

        data.ax = ax;
        data.ay = ay;
        data.az = az;

        data.gx = gx;
        data.gy = gy;
        data.gz = gz;

        data.accelerationMagnitude =
        accelerationMagnitude;

        if (
            postTriggerCount <
            POST_TRIGGER_SAMPLES
        ) {

            buffer[
                PRE_TRIGGER_SAMPLES +
                postTriggerCount
            ] = data;

            postTriggerCount++;
        }


        // ----------------------------------------
        // Finished capture
        // ----------------------------------------

        if (
            postTriggerCount >=
            POST_TRIGGER_SAMPLES
        ) {

            recording = false;

            printSwing();
        }
    }
}
