/*
 * ============================================================================
 *  DETECTOR DE GOLPES DE TENIS  -  ESP32 + MPU-6050 + Bluetooth (SPP)
 *  Classes: SAQUE / FOREHAND / BACKHAND
 *  >>> Versao com LOG DE GOLPES: permite excluir amostras individuais <<<
 * ----------------------------------------------------------------------------
 *  NOVIDADE NESTA VERSAO
 *    Cada golpe de treino agora e gravado individualmente (ate 90 golpes).
 *    Os somatorios do modelo sao RECALCULADOS a partir dessa lista, entao
 *    excluir um golpe ruim e seguro e reversivel na pratica (basta refazer).
 *
 *    g            lista os golpes gravados
 *    g s|f|b      lista so os de uma classe
 *    o            lista os golpes mais discrepantes (candidatos a exclusao)
 *    x u          exclui o ULTIMO golpe gravado
 *    x 12         exclui o golpe 12
 *    x 12-15      exclui a faixa 12 a 15
 *    x s|f|b      exclui TODOS os golpes de uma classe (so os do log)
 *
 *  BLOCO LEGADO
 *    O treino feito no sketch anterior ficou em formato acumulado e nao pode
 *    ser editado golpe a golpe. Ele continua valendo no modelo. Use 'z o'
 *    para descarta-lo inteiro, se quiser recomecar so com o log editavel.
 *
 *  NVS: namespace "tenis" | chaves: cal (calibracao), trn (legado),
 *       mdl (modelo), log (golpes individuais).
 *
 *  IDE: Partition Scheme = "Huge APP (3MB No OTA)"
 *       Erase Flash = "Only Sketch Uploaded"
 *  Ligacao: VCC->3V3  GND->GND  SDA->GPIO21  SCL->GPIO22
 * ============================================================================
 */

#include <Wire.h>
#include <Preferences.h>
#include <math.h>
#include <stdarg.h>
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error "Bluetooth Classico nao habilitado. Use uma placa ESP32 classica."
#endif

static const char *BT_NAME = "RAQUETE-TENIS";

// ------------------------- configuracao de hardware -------------------------
static const uint8_t  PIN_SDA  = 21;
static const uint8_t  PIN_SCL  = 22;
static const uint8_t  MPU_ADDR = 0x68;
static const uint32_t I2C_HZ   = 400000;

#define REG_SMPLRT_DIV    0x19
#define REG_CONFIG        0x1A
#define REG_GYRO_CONFIG   0x1B
#define REG_ACCEL_CONFIG  0x1C
#define REG_ACCEL_XOUT_H  0x3B
#define REG_PWR_MGMT_1    0x6B
#define REG_WHO_AM_I      0x75

static const float ACC_LSB = 2048.0f;
static const float GYR_LSB = 16.4f;

static const uint16_t FS_HZ = 500;
static const uint32_t DT_US = 1000000UL / FS_HZ;
static const uint16_t PRE  = 150;
static const uint16_t POST = 350;
static const uint16_t WIN  = PRE + POST;

// --------------------------- features e classes -----------------------------
#define NFEAT  11
#define NCLS   3
#define MAXLOG 90            // limite do log na NVS (90 x 48 B = 4.3 kB)

const char *CLS[NCLS]   = { "SAQUE", "FOREHAND", "BACKHAND" };
const char *CLSAB[NCLS] = { "SAQ", "FH", "BH" };
const char *FEATNAME[NFEAT] = {
  "aPico_g", "gPico_dps", "gxPico", "gyPico", "gzPico",
  "rotX_deg", "rotY_deg", "rotZ_deg", "pitch_prep", "roll_prep", "dur_ms"
};

// ------------------------------- estruturas ---------------------------------
struct Sample { int16_t ax, ay, az, gx, gy, gz; };

struct Calib { uint32_t magic; float aOff[3], aScl[3], gOff[3]; };
static const uint32_t CAL_MAGIC = 0x544E5331;

struct TrainAcc {                    // somatorios (formato legado / de trabalho)
  uint32_t magic;
  uint16_t n[NCLS];
  float sum[NCLS][NFEAT];
  float sumsq[NCLS][NFEAT];
};
static const uint32_t TRN_MAGIC = 0x544E5254;

struct Model {
  uint32_t magic;
  float mean[NFEAT];
  float inv[NFEAT];
  float cent[NCLS][NFEAT];
  uint16_t n[NCLS];
};
static const uint32_t MDL_MAGIC = 0x544E4D44;

struct LogEntry { uint8_t cls; float f[NFEAT]; };
struct SwingLog { uint32_t magic; uint16_t n; LogEntry e[MAXLOG]; };
static const uint32_t LOG_MAGIC = 0x544E4C47;

enum Mode { MODE_IDLE, MODE_RAW, MODE_FEAT, MODE_TRAIN, MODE_CLASSIFY };

// ------------------------------ estado global -------------------------------
BluetoothSerial SerialBT;
bool btReady = false, btWasConnected = false;

Preferences prefs;
Calib     cal;
TrainAcc  leg;                 // bloco legado (nao editavel golpe a golpe)
SwingLog  lg;                  // golpes individuais (editaveis)
TrainAcc  acc;                 // legado + log, usado para treinar o modelo
Model     mdl;
bool      mdlOK = false;

Sample ring[PRE];
uint16_t ringIdx = 0;
bool ringFull = false;

Sample win[WIN];
uint16_t winCount = 0;
bool capturing = false;

Mode mode = MODE_IDLE;
uint32_t tPrev = 0, lastTrigMs = 0;

float trigAccG = 6.0f, trigGyrDps = 700.0f;
uint32_t refracMs = 600;
float rejectDist = 4.5f;

int curClass = 1;
uint32_t swingNum = 0;
uint32_t sessCount[NCLS] = {0,0,0};
uint32_t sessUnknown = 0;
bool verbose = true;

char cmdU[64]; uint8_t lenU = 0;
char cmdB[64]; uint8_t lenB = 0;

// --------------------------- saida dupla (USB + BT) -------------------------
void out(const char *fmt, ...) {
  char buf[220]; va_list ap; va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
  Serial.print(buf);
  if (btReady && SerialBT.hasClient()) SerialBT.print(buf);
}
void outU(const char *fmt, ...) {
  char buf[220]; va_list ap; va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
  Serial.print(buf);
}

// ----------------------------- driver MPU-6050 ------------------------------
void mpuWrite(uint8_t reg, uint8_t val) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(reg); Wire.write(val); Wire.endTransmission();
}
uint8_t mpuRead(uint8_t reg) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(reg); Wire.endTransmission(false);
  Wire.requestFrom((int)MPU_ADDR, 1);
  return Wire.available() ? Wire.read() : 0xFF;
}
bool mpuInit() {
  Wire.begin(PIN_SDA, PIN_SCL, I2C_HZ); delay(50);
  uint8_t who = mpuRead(REG_WHO_AM_I);
  if (who != 0x68 && who != 0x69 && who != 0x70 && who != 0x98) {
    Serial.printf("ERRO: WHO_AM_I = 0x%02X\n", who); return false;
  }
  mpuWrite(REG_PWR_MGMT_1, 0x80); delay(100);
  mpuWrite(REG_PWR_MGMT_1, 0x01); delay(20);
  mpuWrite(REG_CONFIG, 0x01);
  mpuWrite(REG_SMPLRT_DIV, (1000 / FS_HZ) - 1);
  mpuWrite(REG_GYRO_CONFIG, 0x18);
  mpuWrite(REG_ACCEL_CONFIG, 0x18);
  delay(20);
  Serial.printf("MPU-6050 OK @ %u Hz\n", FS_HZ);
  return true;
}
bool readRaw(Sample &s) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(REG_ACCEL_XOUT_H);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)MPU_ADDR, 14) != 14) return false;
  s.ax = (Wire.read() << 8) | Wire.read();
  s.ay = (Wire.read() << 8) | Wire.read();
  s.az = (Wire.read() << 8) | Wire.read();
  Wire.read(); Wire.read();
  s.gx = (Wire.read() << 8) | Wire.read();
  s.gy = (Wire.read() << 8) | Wire.read();
  s.gz = (Wire.read() << 8) | Wire.read();
  return true;
}
inline void toUnits(const Sample &s, float a[3], float g[3]) {
  float raw[3] = { s.ax / ACC_LSB, s.ay / ACC_LSB, s.az / ACC_LSB };
  for (int i = 0; i < 3; i++) a[i] = (raw[i] - cal.aOff[i]) * cal.aScl[i];
  g[0] = s.gx / GYR_LSB - cal.gOff[0];
  g[1] = s.gy / GYR_LSB - cal.gOff[1];
  g[2] = s.gz / GYR_LSB - cal.gOff[2];
}

// ------------------------------- persistencia -------------------------------
void calDefaults() {
  cal.magic = CAL_MAGIC;
  for (int i = 0; i < 3; i++) { cal.aOff[i] = 0; cal.aScl[i] = 1; cal.gOff[i] = 0; }
}
void rebuildAcc();

void loadAll() {
  prefs.begin("tenis", true);

  if (prefs.getBytesLength("cal") == sizeof(Calib)) {
    prefs.getBytes("cal", &cal, sizeof(Calib));
    if (cal.magic != CAL_MAGIC) calDefaults(); else Serial.println("Calibracao carregada.");
  } else { calDefaults(); Serial.println("ATENCAO: sem calibracao!"); }

  memset(&leg, 0, sizeof(leg)); leg.magic = TRN_MAGIC;
  if (prefs.getBytesLength("trn") == sizeof(TrainAcc)) {
    prefs.getBytes("trn", &leg, sizeof(TrainAcc));
    if (leg.magic != TRN_MAGIC) { memset(&leg, 0, sizeof(leg)); leg.magic = TRN_MAGIC; }
  }

  memset(&lg, 0, sizeof(lg)); lg.magic = LOG_MAGIC;
  if (prefs.getBytesLength("log") == sizeof(SwingLog)) {
    prefs.getBytes("log", &lg, sizeof(SwingLog));
    if (lg.magic != LOG_MAGIC || lg.n > MAXLOG) { memset(&lg, 0, sizeof(lg)); lg.magic = LOG_MAGIC; }
  }

  mdlOK = false;
  if (prefs.getBytesLength("mdl") == sizeof(Model)) {
    prefs.getBytes("mdl", &mdl, sizeof(Model));
    mdlOK = (mdl.magic == MDL_MAGIC);
  }
  prefs.end();

  rebuildAcc();
  Serial.printf("Legado: %u/%u/%u | Log editavel: %u golpes | Modelo: %s\n",
                leg.n[0], leg.n[1], leg.n[2], lg.n, mdlOK ? "OK" : "ausente");
}

void saveLegacy() { prefs.begin("tenis", false); prefs.putBytes("trn", &leg, sizeof(TrainAcc)); prefs.end(); }
void saveModel()  { prefs.begin("tenis", false); prefs.putBytes("mdl", &mdl, sizeof(Model));    prefs.end(); }

bool saveLog() {
  prefs.begin("tenis", false);
  size_t w = prefs.putBytes("log", &lg, sizeof(SwingLog));
  prefs.end();
  if (w != sizeof(SwingLog)) { out("!! FALHA ao gravar o log na NVS\n"); return false; }
  return true;
}

// -------------------- combinacao: legado + log -> acumulador ----------------
void rebuildAcc() {
  memset(&acc, 0, sizeof(acc));
  acc.magic = TRN_MAGIC;
  // parte legada
  for (int c = 0; c < NCLS; c++) {
    acc.n[c] = leg.n[c];
    for (int j = 0; j < NFEAT; j++) {
      acc.sum[c][j]   = leg.sum[c][j];
      acc.sumsq[c][j] = leg.sumsq[c][j];
    }
  }
  // parte editavel
  for (uint16_t i = 0; i < lg.n; i++) {
    int c = lg.e[i].cls;
    if (c < 0 || c >= NCLS) continue;
    for (int j = 0; j < NFEAT; j++) {
      float v = lg.e[i].f[j];
      acc.sum[c][j]   += v;
      acc.sumsq[c][j] += v * v;
    }
    acc.n[c]++;
  }
}

// ----------------------------- modelo ---------------------------------------
bool computeModel(Model &m, const TrainAcc &a, bool quiet) {
  for (int c = 0; c < NCLS; c++) {
    if (a.n[c] < 5) {
      if (!quiet) out("ERRO: %s tem so %u amostras (min 5).\n", CLS[c], a.n[c]);
      return false;
    }
  }
  uint32_t N = 0;
  for (int c = 0; c < NCLS; c++) N += a.n[c];
  for (int j = 0; j < NFEAT; j++) {
    double s = 0, sq = 0;
    for (int c = 0; c < NCLS; c++) { s += a.sum[c][j]; sq += a.sumsq[c][j]; }
    double mu = s / N;
    double sd = sqrt(fmax(sq / N - mu * mu, 0.0));
    m.mean[j] = mu;
    m.inv[j]  = (sd > 1e-4) ? (float)(1.0 / sd) : 0.0f;
  }
  for (int c = 0; c < NCLS; c++) {
    for (int j = 0; j < NFEAT; j++)
      m.cent[c][j] = (a.sum[c][j] / a.n[c] - m.mean[j]) * m.inv[j];
    m.n[c] = a.n[c];
  }
  m.magic = MDL_MAGIC;
  return true;
}

int classifyWith(const Model &m, const float f[NFEAT], float d[NCLS]) {
  float z[NFEAT];
  for (int j = 0; j < NFEAT; j++) z[j] = (f[j] - m.mean[j]) * m.inv[j];
  int best = 0;
  for (int c = 0; c < NCLS; c++) {
    float s = 0;
    for (int j = 0; j < NFEAT; j++) { float dd = z[j] - m.cent[c][j]; s += dd * dd; }
    d[c] = sqrtf(s);
    if (d[c] < d[best]) best = c;
  }
  return best;
}

bool buildModel() {
  rebuildAcc();
  if (!computeModel(mdl, acc, false)) return false;
  mdlOK = true;
  saveModel();
  out("\n=== MODELO CONSTRUIDO ===\n");
  out("amostras: SAQ=%u FH=%u BH=%u\n", mdl.n[0], mdl.n[1], mdl.n[2]);
  int pa[3] = {0,0,1}, pb[3] = {1,2,2};
  for (int p = 0; p < 3; p++) {
    float d = 0;
    for (int j = 0; j < NFEAT; j++) {
      float dd = mdl.cent[pa[p]][j] - mdl.cent[pb[p]][j];
      d += dd * dd;
    }
    d = sqrtf(d);
    out("  %s x %s: %.2f %s\n", CLSAB[pa[p]], CLSAB[pb[p]], d,
        d > 3.0f ? "otimo" : (d > 1.8f ? "ok" : "RUIM"));
  }
  out("\n");
  return true;
}

// ------------------------- log: adicionar / listar / excluir ----------------
bool logAdd(int c, const float f[NFEAT]) {
  if (lg.n >= MAXLOG) {
    out("!! log cheio (%u golpes). Use 'x' para abrir espaco ou 'c' para consolidar.\n", MAXLOG);
    return false;
  }
  lg.e[lg.n].cls = c;
  for (int j = 0; j < NFEAT; j++) lg.e[lg.n].f[j] = f[j];
  lg.n++;
  saveLog();
  rebuildAcc();
  return true;
}

// distancia de cada golpe ao centroide da propria classe (score de discrepancia)
bool logScores(float *scoreOut) {
  Model tmp;
  if (!computeModel(tmp, acc, true)) return false;
  for (uint16_t i = 0; i < lg.n; i++) {
    float d[NCLS];
    classifyWith(tmp, lg.e[i].f, d);
    scoreOut[i] = d[lg.e[i].cls];
  }
  return true;
}

void logList(int filter) {
  if (lg.n == 0) { out("# log vazio (so ha o bloco legado)\n"); return; }
  static float sc[MAXLOG];
  bool haveSc = logScores(sc);

  out("\n idx cls   aPico   gPico     gz   pitch   dist\n");
  for (uint16_t i = 0; i < lg.n; i++) {
    if (filter >= 0 && lg.e[i].cls != filter) continue;
    const float *f = lg.e[i].f;
    out("%4u %-4s %6.1f %7.0f %6.0f %7.0f %6.2f\n",
        i + 1, CLSAB[lg.e[i].cls], f[0], f[1], f[4], f[8],
        haveSc ? sc[i] : 0.0f);
  }
  out("total no log: %u | legado: %u/%u/%u\n\n",
      lg.n, leg.n[0], leg.n[1], leg.n[2]);
}

void logOutliers() {
  if (lg.n == 0) { out("# log vazio\n"); return; }
  static float sc[MAXLOG];
  if (!logScores(sc)) { out("# dados insuficientes para avaliar\n"); return; }

  out("\n--- golpes mais discrepantes (candidatos a exclusao) ---\n");
  uint8_t used[MAXLOG]; memset(used, 0, sizeof(used));
  int show = lg.n < 8 ? lg.n : 8;
  for (int k = 0; k < show; k++) {
    int bi = -1;
    for (uint16_t i = 0; i < lg.n; i++)
      if (!used[i] && (bi < 0 || sc[i] > sc[bi])) bi = i;
    if (bi < 0) break;
    used[bi] = 1;
    const float *f = lg.e[bi].f;
    out("%4d %-4s dist=%5.2f  a=%.1f w=%.0f gz=%+.0f %s\n",
        bi + 1, CLSAB[lg.e[bi].cls], sc[bi], f[0], f[1], f[4],
        sc[bi] > 3.0f ? "<< suspeito" : "");
  }
  out("Excluir: x <idx>   (ex.: x %d)\n\n", 
      (int)(lg.n > 0 ? 1 : 1));
}

int logDeleteRange(uint16_t a, uint16_t b) {   // indices 1-based, inclusivos
  if (a < 1 || b > lg.n || a > b) return 0;
  uint16_t cnt = b - a + 1;
  for (uint16_t i = b; i < lg.n; i++) lg.e[i - cnt] = lg.e[i];
  lg.n -= cnt;
  saveLog();
  rebuildAcc();
  return cnt;
}

int logDeleteClass(int c) {
  uint16_t w = 0, removed = 0;
  for (uint16_t i = 0; i < lg.n; i++) {
    if (lg.e[i].cls == c) { removed++; continue; }
    lg.e[w++] = lg.e[i];
  }
  lg.n = w;
  saveLog();
  rebuildAcc();
  return removed;
}

// Consolida o log dentro do bloco legado (libera espaco, perde a edicao fina)
void consolidate() {
  for (uint16_t i = 0; i < lg.n; i++) {
    int c = lg.e[i].cls;
    for (int j = 0; j < NFEAT; j++) {
      float v = lg.e[i].f[j];
      leg.sum[c][j]   += v;
      leg.sumsq[c][j] += v * v;
    }
    leg.n[c]++;
  }
  uint16_t moved = lg.n;
  lg.n = 0;
  saveLegacy(); saveLog(); rebuildAcc();
  out("# %u golpes movidos para o bloco legado (log liberado)\n", moved);
}

// --------------------------- buffer circular --------------------------------
void pushRing(const Sample &s) {
  ring[ringIdx] = s; ringIdx = (ringIdx + 1) % PRE;
  if (ringIdx == 0) ringFull = true;
}
void ringToWindow() {
  uint16_t n = ringFull ? PRE : ringIdx;
  uint16_t start = ringFull ? ringIdx : 0;
  for (uint16_t i = 0; i < n; i++) win[i] = ring[(start + i) % PRE];
  winCount = n;
}

// ---------------------------- extracao de features --------------------------
void computeFeatures(float f[NFEAT]) {
  float aPk = 0, gPk = 0, gAx[3] = {0,0,0}, rot[3] = {0,0,0};
  uint16_t nAbove = 0, nPre = 0;
  double pre[3] = {0,0,0};

  for (uint16_t i = 0; i < winCount; i++) {
    float a[3], g[3]; toUnits(win[i], a, g);
    float am = sqrtf(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
    float gm = sqrtf(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]);
    if (am > aPk) aPk = am;
    if (gm > gPk) gPk = gm;
    for (int k = 0; k < 3; k++) {
      if (fabsf(g[k]) > fabsf(gAx[k])) gAx[k] = g[k];
      rot[k] += g[k] / FS_HZ;
    }
    if (gm > trigGyrDps * 0.4f) nAbove++;
    if (i < 50) { for (int k = 0; k < 3; k++) pre[k] += a[k]; nPre++; }
  }
  float pitch = 0, roll = 0;
  if (nPre > 0) {
    float ax = pre[0]/nPre, ay = pre[1]/nPre, az = pre[2]/nPre;
    pitch = atan2f(-ax, sqrtf(ay*ay + az*az)) * 57.29578f;
    roll  = atan2f(ay, az) * 57.29578f;
  }
  f[0]=aPk; f[1]=gPk; f[2]=gAx[0]; f[3]=gAx[1]; f[4]=gAx[2];
  f[5]=rot[0]; f[6]=rot[1]; f[7]=rot[2]; f[8]=pitch; f[9]=roll;
  f[10]=nAbove * 1000.0f / FS_HZ;
}

void showTrainStats() {
  outU("\n=== ESTATISTICAS (legado + log) ===\n");
  outU("%-12s", "feature");
  for (int c = 0; c < NCLS; c++) outU("%-22s", CLS[c]);
  outU("\n");
  for (int j = 0; j < NFEAT; j++) {
    outU("%-12s", FEATNAME[j]);
    for (int c = 0; c < NCLS; c++) {
      if (acc.n[c] == 0) { outU("%-22s", "-"); continue; }
      float m = acc.sum[c][j] / acc.n[c];
      float v = acc.sumsq[c][j] / acc.n[c] - m * m;
      char b[24];
      snprintf(b, sizeof(b), "%+8.1f +-%-7.1f", m, sqrtf(fmax(v, 0.0f)));
      outU("%-22s", b);
    }
    outU("\n");
  }
  outU("\ntotal: saque=%u forehand=%u backhand=%u\n\n", acc.n[0], acc.n[1], acc.n[2]);
}

void showSession() {
  out("\n--- SESSAO ---\n SAQUE: %lu | FH: %lu | BH: %lu | ?: %lu\n\n",
      sessCount[0], sessCount[1], sessCount[2], sessUnknown);
}

// ------------------------------- evento de golpe ----------------------------
void onSwing() {
  float f[NFEAT];
  computeFeatures(f);

  if (mode == MODE_TRAIN) {
    if (logAdd(curClass, f))
      out("[treino] %s  idx=%u  a=%.1fg w=%.0f  (x u desfaz)\n",
          CLS[curClass], lg.n, f[0], f[1]);
    return;
  }
  if (mode == MODE_FEAT) {
    outU("%lu,manual", ++swingNum);
    for (int j = 0; j < NFEAT; j++) outU(",%.2f", f[j]);
    outU("\n");
    return;
  }
  if (mode == MODE_CLASSIFY) {
    if (!mdlOK) { out("# sem modelo\n"); return; }
    float d[NCLS];
    int c = classifyWith(mdl, f, d);
    int s2 = -1;
    for (int k = 0; k < NCLS; k++) if (k != c && (s2 < 0 || d[k] < d[s2])) s2 = k;
    float conf = (d[s2] > 1e-3f) ? (d[s2] - d[c]) / d[s2] : 0.0f;
    swingNum++;
    if (d[c] > rejectDist) {
      sessUnknown++;
      out("#%-3lu  ???  nao identificado  (%s d=%.1f) a=%.1fg\n", swingNum, CLSAB[c], d[c], f[0]);
    } else {
      sessCount[c]++;
      out("#%-3lu  >> %-8s %3.0f%%  a=%.1fg w=%.0f  [%lu/%lu/%lu]\n",
          swingNum, CLS[c], conf*100, f[0], f[1], sessCount[0], sessCount[1], sessCount[2]);
      if (verbose)
        outU("        d: saq=%.2f fh=%.2f bh=%.2f | gz=%+.0f pitch=%+.0f\n",
             d[0], d[1], d[2], f[4], f[8]);
    }
  }
}

// ------------------------------- comandos -----------------------------------
int parseCls(char k) {
  k = tolower(k);
  if (k == 's') return 0;
  if (k == 'f') return 1;
  if (k == 'b') return 2;
  return -1;
}

void printHelp() {
  out("\n========== COMANDOS ==========\n");
  out(" k  classificar        s  resumo\n");
  out(" a  treinar            m  construir modelo\n");
  out(" l s|f|b  classe       q  parar\n");
  out("-- edicao do treino --\n");
  out(" g          listar golpes\n");
  out(" g s|f|b    listar por classe\n");
  out(" o          mais discrepantes\n");
  out(" x u        exclui o ultimo\n");
  out(" x 12       exclui o golpe 12\n");
  out(" x 12-15    exclui faixa\n");
  out(" x s|f|b    exclui a classe (log)\n");
  out(" c          consolida log -> legado\n");
  out(" z          apaga TUDO   z o  so o legado\n");
  out("-- outros --\n");
  out(" p parametros   v estatisticas   d detalhes\n");
  out(" t <g> <dps>    u <rejeicao>\n");
  out(" f features     r raw      h ajuda\n");
  out("==============================\n\n");
}

void calPrint() {
  outU("--- Calibracao ---\n");
  outU("  offset(g): %+.4f %+.4f %+.4f\n", cal.aOff[0], cal.aOff[1], cal.aOff[2]);
  outU("  escala   : %.5f %.5f %.5f\n",    cal.aScl[0], cal.aScl[1], cal.aScl[2]);
  outU("  gyro     : %+.3f %+.3f %+.3f\n", cal.gOff[0], cal.gOff[1], cal.gOff[2]);
  out ("  gatilhos %.1fg/%.0fdps | rejeicao %.1f\n", trigAccG, trigGyrDps, rejectDist);
  out ("  dados: legado %u/%u/%u + log %u | total %u/%u/%u\n",
       leg.n[0], leg.n[1], leg.n[2], lg.n, acc.n[0], acc.n[1], acc.n[2]);
  out ("  modelo: %s | classe: %s\n", mdlOK ? "OK" : "AUSENTE", CLS[curClass]);
}

void handleCommand(char *c) {
  while (*c == ' ') c++;
  char *arg = c + 1;
  while (*arg == ' ') arg++;

  switch (tolower(c[0])) {
    case 'h': printHelp(); break;
    case 'p': calPrint(); break;
    case 'v': showTrainStats(); break;
    case 's': showSession(); break;

    case 'g': {
      int fc = *arg ? parseCls(*arg) : -1;
      logList(fc);
      break;
    }

    case 'o': logOutliers(); break;

    case 'x': {
      if (!*arg) { out("# use: x u | x 12 | x 12-15 | x f\n"); break; }
      if (tolower(*arg) == 'u') {
        if (lg.n == 0) { out("# log vazio\n"); break; }
        int cls = lg.e[lg.n - 1].cls;
        logDeleteRange(lg.n, lg.n);
        out("# ultimo golpe (%s) excluido. Restam %u no log.\n", CLSAB[cls], lg.n);
      } else if (isdigit((unsigned char)*arg)) {
        int a = atoi(arg), b = a;
        char *dash = strchr(arg, '-');
        if (dash) b = atoi(dash + 1);
        int n = logDeleteRange(a, b);
        if (n) out("# %d golpe(s) excluido(s). Restam %u.\n", n, lg.n);
        else   out("# indice invalido (log tem %u golpes)\n", lg.n);
      } else {
        int cl = parseCls(*arg);
        if (cl < 0) { out("# argumento invalido\n"); break; }
        int n = logDeleteClass(cl);
        out("# %d golpe(s) de %s excluido(s) do log. Restam %u.\n", n, CLS[cl], lg.n);
      }
      if (mdlOK) { if (computeModel(mdl, acc, true)) { saveModel(); out("# modelo recalculado\n"); }
                   else { mdlOK = false; out("# dados insuficientes: modelo invalidado\n"); } }
      break;
    }

    case 'c': consolidate(); break;

    case 'l': {
      int cl = parseCls(*arg);
      if (cl < 0) { out("# use: l s | l f | l b\n"); break; }
      curClass = cl;
      out("# classe = %s\n", CLS[curClass]);
      break;
    }

    case 'a':
      mode = MODE_TRAIN; capturing = false;
      out("\n# TREINO -> %s (log: %u/%u)\n", CLS[curClass], lg.n, MAXLOG);
      break;

    case 'k':
      if (!mdlOK) { out("# sem modelo\n"); break; }
      mode = MODE_CLASSIFY; capturing = false;
      out("\n# CLASSIFICANDO\n\n");
      break;

    case 'f':
      mode = MODE_FEAT; capturing = false;
      outU("\ngolpe,rotulo");
      for (int j = 0; j < NFEAT; j++) outU(",%s", FEATNAME[j]);
      outU("\n");
      break;

    case 'r': mode = MODE_RAW; outU("\nt_ms,ax,ay,az,gx,gy,gz\n"); break;
    case 'q': mode = MODE_IDLE; capturing = false; out("# parado\n"); break;
    case 'm': buildModel(); break;

    case 'z':
      if (tolower(*arg) == 'o') {
        memset(&leg, 0, sizeof(leg)); leg.magic = TRN_MAGIC;
        saveLegacy(); rebuildAcc();
        out("# bloco legado apagado. Log preservado (%u golpes).\n", lg.n);
      } else {
        memset(&leg, 0, sizeof(leg)); leg.magic = TRN_MAGIC;
        memset(&lg, 0, sizeof(lg));   lg.magic = LOG_MAGIC;
        saveLegacy(); saveLog(); rebuildAcc();
        mdlOK = false;
        prefs.begin("tenis", false); prefs.remove("mdl"); prefs.end();
        out("# TUDO apagado (calibracao mantida)\n");
      }
      break;

    case 'd': verbose = !verbose; outU("# detalhes %s\n", verbose ? "ON" : "OFF"); break;

    case 't': { float a, g;
      if (sscanf(arg, "%f %f", &a, &g) == 2) { trigAccG = a; trigGyrDps = g; }
      out("# gatilhos %.1f/%.0f\n", trigAccG, trigGyrDps); break; }

    case 'u': { float v;
      if (sscanf(arg, "%f", &v) == 1) rejectDist = v;
      out("# rejeicao %.2f\n", rejectDist); break; }

    default: if (c[0]) out("# comando invalido (h)\n");
  }
}

void pollInputs() {
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == '\n' || ch == '\r') { if (lenU) { cmdU[lenU] = 0; handleCommand(cmdU); lenU = 0; } }
    else if (lenU < sizeof(cmdU) - 1) cmdU[lenU++] = ch;
  }
  if (!btReady) return;
  while (SerialBT.available()) {
    char ch = SerialBT.read();
    if (ch == '\n' || ch == '\r') { if (lenB) { cmdB[lenB] = 0; handleCommand(cmdB); lenB = 0; } }
    else if (lenB < sizeof(cmdB) - 1) cmdB[lenB++] = ch;
  }
}

void checkBt() {
  bool now = btReady && SerialBT.hasClient();
  if (now && !btWasConnected) {
    delay(200);
    SerialBT.printf("\n== RAQUETE-TENIS ==\nlog: %u golpes | modelo: %s\n'h' ajuda\n\n",
                    lg.n, mdlOK ? "OK" : "ausente");
    Serial.println("# celular conectado");
  } else if (!now && btWasConnected) Serial.println("# celular desconectado");
  btWasConnected = now;
}

// --------------------------------- setup / loop -----------------------------
void setup() {
  Serial.begin(115200); delay(600);
  Serial.println(F("\n\n=== Detector de golpes (log editavel) ==="));
  if (!mpuInit()) { while (1) delay(1000); }
  loadAll();
  btReady = SerialBT.begin(BT_NAME);
  Serial.println(btReady ? "Bluetooth ativo: RAQUETE-TENIS" : "FALHA no Bluetooth");
  calPrint();
  printHelp();
  if (mdlOK) { mode = MODE_CLASSIFY; Serial.println(F("# classificando\n")); }
  tPrev = micros();
}

void loop() {
  pollInputs();
  static uint32_t lastChk = 0;
  if (millis() - lastChk > 500) { lastChk = millis(); checkBt(); }

  uint32_t now = micros();
  if ((int32_t)(now - tPrev) < (int32_t)DT_US) return;
  tPrev += DT_US;
  if ((int32_t)(now - tPrev) > (int32_t)(DT_US * 4)) tPrev = now;

  Sample s;
  if (!readRaw(s)) return;

  if (mode == MODE_RAW) {
    float a[3], g[3]; toUnits(s, a, g);
    outU("%lu,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f\n", millis(), a[0], a[1], a[2], g[0], g[1], g[2]);
    return;
  }

  pushRing(s);
  if (mode == MODE_IDLE) return;

  if (capturing) {
    if (winCount < WIN) win[winCount++] = s;
    if (winCount >= WIN) { capturing = false; onSwing(); }
    return;
  }

  float a[3], g[3]; toUnits(s, a, g);
  float am = sqrtf(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
  float gm = sqrtf(g[0]*g[0] + g[1]*g[1] + g[2]*g[2]);
  if ((am >= trigAccG || gm >= trigGyrDps) && (millis() - lastTrigMs) > refracMs) {
    lastTrigMs = millis();
    ringToWindow();
    capturing = true;
  }
}
