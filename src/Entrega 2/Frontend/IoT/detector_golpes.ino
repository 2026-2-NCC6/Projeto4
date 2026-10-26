/*
 * ============================================================================
 *  DETECTOR DE GOLPES DE TENIS v2  -  ESP32 + MPU-6050 + Bluetooth (SPP)
 *  13 classes: saques (plano/slice/kick), smash, FH/BH (drive/slice/voleio),
 *              amortida, lob e RUIDO/OUTRO (movimentos que nao sao golpe)
 * ----------------------------------------------------------------------------
 *  O QUE MUDOU EM RELACAO A VERSAO ANTERIOR
 *
 *  1) MAIS DADOS DO MPU-6050 (30 features por golpe, antes eram 11)
 *     - Fusao acelerometro+giroscopio (filtro Mahony) -> orientacao 3D durante
 *       o golpe, gravidade removida da aceleracao e velocidade linear estimada.
 *     - Fases do golpe: backswing, avanco, impacto e follow-through (tempos).
 *     - Impacto da bola: pico de "choque" na 2a diferenca da aceleracao.
 *     - Direcao do eixo de rotacao, giro em torno da vertical (yaw relativo),
 *       trajetoria baixo->alto (topspin) ou alto->baixo (slice).
 *     - Temperatura do chip (so informativa) e deteccao de saturacao (+-16g /
 *       +-2000dps): o golpe e marcado quando o sensor satura.
 *
 *  2) DETECCAO ADAPTATIVA
 *     Gatilho mais sensivel + validacao pos-captura (duracao, rotacao minima),
 *     para pegar voleios/amortidas sem disparar com caminhada ou raquete caindo.
 *
 *  3) CLASSIFICADOR MAIS FORTE
 *     kNN por classe com pesos de Fisher (features mais discriminativas pesam
 *     mais), limite de rejeicao POR CLASSE, validacao leave-one-out com
 *     precisao e confusoes por classe ('m'). Classes com poucas amostras ficam
 *     inativas em vez de impedir o modelo.
 *
 *  4) ROTINA DE TREINO ('e'): ex.  e fhd 10 bhd 10 spl 5
 *     Conta acertos/erros por etapa e resume velocidade media/maxima.
 *
 *  5) ARMAZENAMENTO: LittleFS (/golpes.bin), ate MAXLOG golpes. O modelo e
 *     recalculado a partir do log (nao ha mais bloco legado). O log da versao
 *     anterior (11 features) NAO e compativel: e preciso retreinar.
 *     A calibracao (NVS "tenis"/"cal") continua sendo lida normalmente.
 *
 *  IDE: Partition Scheme = "Huge APP (3MB No OTA/1MB SPIFFS)"
 *       Erase Flash = "Only Sketch Uploaded"
 *  Ligacao: VCC->3V3  GND->GND  SDA->GPIO21  SCL->GPIO22
 *  Fixe o sensor RIGIDO na raquete (base do cabo ou garganta) e SEMPRE na
 *  mesma posicao/orientacao; o modelo aprende com a sua montagem.
 * ============================================================================
 */

#include <Wire.h>
#include <Preferences.h>
#include <LittleFS.h>
#include <math.h>
#include <stdarg.h>
#include <string.h>
#include <ctype.h>
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

static const float ACC_LSB = 2048.0f;     // +-16 g
static const float GYR_LSB = 16.4f;       // +-2000 dps
static const float D2R = 0.017453292f;
static const float R2D = 57.29578f;

static const uint16_t FS_HZ = 500;
static const uint32_t DT_US = 1000000UL / FS_HZ;
static const float    DT    = 1.0f / FS_HZ;
static const float    DT_MS = 1000.0f / FS_HZ;
static const uint16_t PRE  = 300;         // 600 ms antes do gatilho (backswing)
static const uint16_t POST = 250;         // 500 ms depois (impacto + follow-through)
static const uint16_t WIN  = PRE + POST;

#define MAXLOG        400                 // golpes no log (400 x 124 B = ~50 kB)
#define KNN           3                   // vizinhos por classe
#define MIN_PER_CLASS 5                   // amostras minimas p/ classe ativa

// ------------------------------- classes ------------------------------------
// Para criar uma classe nova basta ACRESCENTAR no fim das duas tabelas
// (o log existente continua valido).
static const char *const CLS[] = {
  "SAQUE PLANO", "SAQUE SLICE", "SAQUE KICK", "SMASH",
  "FH DRIVE", "FH SLICE", "FH VOLEIO",
  "BH DRIVE", "BH SLICE", "BH VOLEIO",
  "AMORTIDA", "LOB", "RUIDO/OUTRO"
};
static const char *const CLSAB[] = {
  "SPL", "SSL", "SKK", "SMA",
  "FHD", "FHS", "FHV",
  "BHD", "BHS", "BHV",
  "AMO", "LOB", "RUI"
};
static const int NCLS = sizeof(CLS) / sizeof(CLS[0]);

// ------------------------------- features -----------------------------------
enum Feat {
  F_GPK, F_APK, F_ALINPK, F_GRMS, F_SHOCK,        // intensidade
  F_WUX, F_WUY, F_WUZ,                            // eixo de rotacao no pico
  F_ROTX, F_ROTY, F_ROTZ, F_ROTTOT,               // rotacao acumulada (graus)
  F_BACKANG, F_BACKPK,                            // backswing
  F_TBACK, F_TFWD, F_TFOL, F_TIMP,                // tempos (ms)
  F_PVX, F_PVY, F_PVZ,                            // gravidade no corpo: inicio do avanco
  F_IVX, F_IVY, F_IVZ,                            // gravidade no corpo: impacto
  F_DYAW, F_AXVERT,                               // giro vertical / eixo vs vertical
  F_VVERT, F_VHOR, F_PATHELEV, F_AIMP,            // trajetoria linear
  NFEAT
};
static const char *const FEATNAME[NFEAT] = {
  "gPk", "aPk", "aLinPk", "gRms", "shock",
  "wuX", "wuY", "wuZ",
  "rotX", "rotY", "rotZ", "rotTot",
  "backAng", "backPk",
  "tBack", "tFwd", "tFol", "tImp",
  "pvX", "pvY", "pvZ",
  "ivX", "ivY", "ivZ",
  "dYaw", "axVert",
  "vVert", "vHor", "elev", "aImp"
};

// ------------------------------- estruturas ---------------------------------
struct Sample { int16_t ax, ay, az, gx, gy, gz; };

struct Calib { uint32_t magic; float aOff[3], aScl[3], gOff[3]; };
static const uint32_t CAL_MAGIC = 0x544E5331;

struct LogEntry { uint8_t cls; uint8_t flags; uint16_t rsv; float f[NFEAT]; };
struct FileHdr  { uint32_t magic; uint16_t nfeat; uint16_t recsz; };
static const uint32_t LOG_MAGIC = 0x544E4C32;
static const char *LOG_PATH = "/golpes.bin";
static const char *LOG_TMP  = "/golpes.tmp";
static const char *LOG_BAK  = "/golpes.bak";

struct Model {
  bool     ok;
  bool     active[NCLS];
  uint16_t n[NCLS];
  float    w[NFEAT];            // peso de Fisher (diagnostico)
  float    ww[NFEAT];           // peso final: Fisher / sigma^2 (normalizado)
  float    rej[NCLS];           // limite de rejeicao por classe
  float    acc;                 // precisao leave-one-out
  uint16_t conf[NCLS][NCLS];    // matriz de confusao LOO [real][previsto]
};

struct Pred { int c1, c2; float d1, d2, conf, ratio; bool reject, ambig; };

struct SwingInfo {
  bool  valid, impact, sat;
  const char *why;
  float gPk, durMs, vHeadKmh;
};

struct Scratch {                // buffers de trabalho (alocados no heap)
  float A[WIN][3], G[WIN][3], V[WIN][3];
  float GM[WIN], GS[WIN], AM[WIN], AL[WIN], J[WIN];
};

struct Drill {
  bool on;
  uint8_t n, step;
  uint8_t cls[8];
  uint16_t goal[8], hit[8], miss[8];
  float vSum[8], vMax[8];
};

enum Mode { MODE_IDLE, MODE_RAW, MODE_FEAT, MODE_TRAIN, MODE_CLASSIFY };

// ------------------------------ estado global -------------------------------
BluetoothSerial SerialBT;
bool btReady = false, btWasConnected = false;

Preferences prefs;
Calib cal;
bool  fsOK = false;

LogEntry *lgE = nullptr;        // log em RAM (espelho de /golpes.bin)
uint16_t  lgN = 0;
Scratch  *S   = nullptr;

Model mdl;
bool  mdlDirty = true;
static int8_t   looPred[MAXLOG];
static float    looRatio[MAXLOG];
static uint16_t looN = 0;

Sample ring[PRE];
uint16_t ringIdx = 0;
bool ringFull = false;
Sample win[WIN];
uint16_t winCount = 0, trigIdx = 0;
bool capturing = false;

Mode mode = MODE_IDLE;
uint32_t tPrev = 0, lastTrigMs = 0;
float tempC = 0;

// parametros ajustaveis em tempo de execucao
float trigAccG = 4.5f, trigGyrDps = 350.0f;
uint32_t refracMs = 800;
float rejectK = 1.3f;           // multiplica o limite de rejeicao por classe (maior = mais permissivo)
float shockThr = 2.0f;          // g (2a diferenca) para considerar "houve impacto"
float armM = 0.60f;             // distancia sensor -> centro das cordas (m)

int curClass = 4;
unsigned long swingNum = 0;
unsigned long sessCount[NCLS];
float sessSum[NCLS], sessMax[NCLS];
unsigned long sessUnknown = 0, sessIgnored = 0;
bool verbose = true;
Drill drill;

char cmdU[96]; uint8_t lenU = 0;
char cmdB[96]; uint8_t lenB = 0;

// --------------------------- saida dupla (USB + BT) -------------------------
void out(const char *fmt, ...) {
  char buf[256]; va_list ap; va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
  Serial.print(buf);
  if (btReady && SerialBT.hasClient()) SerialBT.print(buf);
}
void outU(const char *fmt, ...) {
  char buf[256]; va_list ap; va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap); va_end(ap);
  Serial.print(buf);
}

// ----------------------------- utilitarios ----------------------------------
static inline float sqr(float x) { return x * x; }
static inline float vlen3(const float *v) { return sqrtf(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]); }

int parseCls(const char *s) {   // codigo de 3 letras (ex.: fhd), sem diferenciar maiuscula
  if (!s || !s[0]) return -1;
  for (int c = 0; c < NCLS; c++) {
    const char *a = CLSAB[c]; int k = 0;
    while (a[k] && s[k] && tolower((unsigned char)s[k]) == tolower((unsigned char)a[k])) k++;
    if (a[k] == 0 && !isalnum((unsigned char)s[k])) return c;
  }
  return -1;
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
  mpuWrite(REG_CONFIG, 0x01);                       // DLPF ~184 Hz
  mpuWrite(REG_SMPLRT_DIV, (1000 / FS_HZ) - 1);
  mpuWrite(REG_GYRO_CONFIG, 0x18);                  // +-2000 dps
  mpuWrite(REG_ACCEL_CONFIG, 0x18);                 // +-16 g
  delay(20);
  Serial.printf("MPU-6050 OK @ %u Hz\n", FS_HZ);
  return true;
}
// leitura sequencial explicita (a ordem de avaliacao de "a | b" em C++ nao e garantida)
static inline int16_t rd16() {
  uint8_t h = Wire.read(); uint8_t l = Wire.read();
  return (int16_t)((h << 8) | l);
}
bool readRaw(Sample &s) {
  Wire.beginTransmission(MPU_ADDR); Wire.write(REG_ACCEL_XOUT_H);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)MPU_ADDR, 14) != 14) return false;
  s.ax = rd16(); s.ay = rd16(); s.az = rd16();
  int16_t t = rd16();                               // temperatura do chip
  s.gx = rd16(); s.gy = rd16(); s.gz = rd16();
  float tc = t / 340.0f + 36.53f;
  tempC = (tempC == 0.0f) ? tc : tempC + 0.002f * (tc - tempC);
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

bool fsRewrite() {                       // regrava o log inteiro (apos excluir golpes)
  if (!fsOK) return false;
  File fl = LittleFS.open(LOG_TMP, "w");
  if (!fl) { out("!! FALHA ao abrir arquivo temporario\n"); return false; }
  FileHdr h; h.magic = LOG_MAGIC; h.nfeat = NFEAT; h.recsz = sizeof(LogEntry);
  bool ok = fl.write((const uint8_t *)&h, sizeof(h)) == sizeof(h);
  for (uint16_t i = 0; ok && i < lgN; i++)
    ok = fl.write((const uint8_t *)&lgE[i], sizeof(LogEntry)) == sizeof(LogEntry);
  fl.close();
  if (!ok) { out("!! FALHA ao gravar o log\n"); return false; }
  LittleFS.remove(LOG_PATH);
  LittleFS.rename(LOG_TMP, LOG_PATH);
  return true;
}

bool fsAppend(const LogEntry &e) {       // acrescenta um golpe (rapido, pouco desgaste)
  if (!fsOK) return false;
  File fl = LittleFS.open(LOG_PATH, "a");
  if (!fl) return false;
  if (fl.size() == 0) {
    FileHdr h; h.magic = LOG_MAGIC; h.nfeat = NFEAT; h.recsz = sizeof(LogEntry);
    fl.write((const uint8_t *)&h, sizeof(h));
  }
  size_t w = fl.write((const uint8_t *)&e, sizeof(e));
  fl.close();
  return w == sizeof(e);
}

void fsReadLog() {
  lgN = 0;
  if (!fsOK) return;
  if (!LittleFS.exists(LOG_PATH) && LittleFS.exists(LOG_TMP)) LittleFS.rename(LOG_TMP, LOG_PATH);
  if (!LittleFS.exists(LOG_PATH)) return;
  File fl = LittleFS.open(LOG_PATH, "r");
  if (!fl) return;
  FileHdr h;
  if (fl.read((uint8_t *)&h, sizeof(h)) != sizeof(h) || h.magic != LOG_MAGIC ||
      h.nfeat != NFEAT || h.recsz != sizeof(LogEntry)) {
    fl.close();
    Serial.println("ATENCAO: log incompativel ignorado (guardado como /golpes.bak)");
    LittleFS.remove(LOG_BAK);
    LittleFS.rename(LOG_PATH, LOG_BAK);
    return;
  }
  size_t body = fl.size() - sizeof(h);
  size_t cnt = body / sizeof(LogEntry);
  bool ragged = (body % sizeof(LogEntry)) != 0;
  for (size_t i = 0; i < cnt && lgN < MAXLOG; i++) {
    if (fl.read((uint8_t *)&lgE[lgN], sizeof(LogEntry)) != sizeof(LogEntry)) break;
    if (lgE[lgN].cls < NCLS) lgN++;
  }
  fl.close();
  if (ragged || cnt > MAXLOG) fsRewrite();   // limpa registro truncado por queda de energia
}

void loadAll() {
  prefs.begin("tenis", true);
  if (prefs.getBytesLength("cal") == sizeof(Calib)) {
    prefs.getBytes("cal", &cal, sizeof(Calib));
    if (cal.magic != CAL_MAGIC) calDefaults(); else Serial.println("Calibracao carregada.");
  } else { calDefaults(); Serial.println("ATENCAO: sem calibracao!"); }
  prefs.end();
  fsReadLog();
  Serial.printf("Log: %u golpes | LittleFS: %s\n", lgN, fsOK ? "OK" : "FALHOU (so RAM)");
}

// ----------------------------- modelo (kNN + Fisher) ------------------------
static inline float dist2(const float *a, const float *b) {
  float d = 0;
  for (int j = 0; j < NFEAT; j++) { float t = a[j] - b[j]; d += mdl.ww[j] * t * t; }
  return d;
}

// distancia do golpe f a cada classe = media das KNN menores distancias.
// skip = indice do log a ignorar (leave-one-out) ou -1.
void classDist(const float *f, int skip, float *dk) {
  float best[NCLS][KNN]; uint8_t cnt[NCLS];
  for (int c = 0; c < NCLS; c++) { cnt[c] = 0; for (int m = 0; m < KNN; m++) best[c][m] = 1e30f; }
  for (uint16_t j = 0; j < lgN; j++) {
    if ((int)j == skip) continue;
    int c = lgE[j].cls;
    if (!mdl.active[c]) continue;
    float d = dist2(f, lgE[j].f);
    if (d < best[c][KNN - 1]) {
      int m = KNN - 1;
      while (m > 0 && best[c][m - 1] > d) { best[c][m] = best[c][m - 1]; m--; }
      best[c][m] = d;
      if (cnt[c] < KNN) cnt[c]++;
    }
  }
  for (int c = 0; c < NCLS; c++) {
    if (!mdl.active[c] || cnt[c] == 0) { dk[c] = 1e30f; continue; }
    float s = 0; for (int m = 0; m < cnt[c]; m++) s += sqrtf(best[c][m]);
    dk[c] = s / cnt[c];
  }
}

Pred decide(const float *dk) {
  Pred p; p.c1 = -1; p.c2 = -1; p.d1 = 1e30f; p.d2 = 1e30f;
  for (int c = 0; c < NCLS; c++) {
    if (!mdl.active[c]) continue;
    if (dk[c] < p.d1)      { p.c2 = p.c1; p.d2 = p.d1; p.c1 = c; p.d1 = dk[c]; }
    else if (dk[c] < p.d2) { p.c2 = c; p.d2 = dk[c]; }
  }
  p.conf  = (p.c2 >= 0 && p.d2 < 1e29f && p.d2 > 1e-6f) ? (p.d2 - p.d1) / p.d2 : 1.0f;
  p.ratio = (p.c1 >= 0 && mdl.rej[p.c1] > 1e-6f) ? p.d1 / mdl.rej[p.c1] : 99.0f;
  p.reject = p.ratio > rejectK;
  p.ambig  = p.conf < 0.10f;
  return p;
}

bool computeModel(bool quiet) {
  memset(&mdl, 0, sizeof(mdl));
  for (uint16_t i = 0; i < lgN; i++) mdl.n[lgE[i].cls]++;
  int nAct = 0; uint32_t N = 0;
  for (int c = 0; c < NCLS; c++)
    if (mdl.n[c] >= MIN_PER_CLASS) { mdl.active[c] = true; nAct++; N += mdl.n[c]; }
  if (nAct < 2) {
    if (!quiet) out("ERRO: preciso de >= 2 classes com %d+ golpes (ativas: %d).\n", MIN_PER_CLASS, nAct);
    return false;
  }

  // estatisticas globais e por classe
  static double cm[NCLS][NFEAT];
  double mu[NFEAT], m2[NFEAT], W[NFEAT], B[NFEAT], sg[NFEAT];
  memset(cm, 0, sizeof(cm));
  for (int j = 0; j < NFEAT; j++) { mu[j] = m2[j] = W[j] = B[j] = 0; }
  for (uint16_t i = 0; i < lgN; i++) {
    int c = lgE[i].cls; if (!mdl.active[c]) continue;
    for (int j = 0; j < NFEAT; j++) { double x = lgE[i].f[j]; mu[j] += x; m2[j] += x * x; cm[c][j] += x; }
  }
  for (int j = 0; j < NFEAT; j++) {
    mu[j] /= N;
    sg[j] = sqrt(fmax(m2[j] / N - mu[j] * mu[j], 0.0));
    for (int c = 0; c < NCLS; c++) if (mdl.active[c]) cm[c][j] /= mdl.n[c];
  }
  for (uint16_t i = 0; i < lgN; i++) {
    int c = lgE[i].cls; if (!mdl.active[c]) continue;
    for (int j = 0; j < NFEAT; j++) { double d = lgE[i].f[j] - cm[c][j]; W[j] += d * d; }
  }
  for (int c = 0; c < NCLS; c++) {
    if (!mdl.active[c]) continue;
    for (int j = 0; j < NFEAT; j++) { double d = cm[c][j] - mu[j]; B[j] += mdl.n[c] * d * d; }
  }

  // peso de Fisher = variancia entre classes / variancia dentro das classes
  double Fj[NFEAT], Fsum = 0; int Fn = 0;
  for (int j = 0; j < NFEAT; j++) {
    if (sg[j] < 1e-6) { Fj[j] = -1; continue; }
    Fj[j] = B[j] / (W[j] + 1e-12); Fsum += Fj[j]; Fn++;
  }
  double Fmean = (Fn > 0 && Fsum > 0) ? Fsum / Fn : 1.0;
  float wsum = 0;
  for (int j = 0; j < NFEAT; j++) {
    if (Fj[j] < 0) { mdl.w[j] = 0; continue; }
    double w = Fj[j] / Fmean;
    if (w < 0.2) w = 0.2;
    if (w > 3.0) w = 3.0;
    mdl.w[j] = (float)w; wsum += (float)w;
  }
  for (int j = 0; j < NFEAT; j++)
    mdl.ww[j] = (mdl.w[j] > 0 && wsum > 0) ? mdl.w[j] / (float)(sg[j] * sg[j]) / wsum : 0.0f;

  // leave-one-out: precisao, confusoes e limite de rejeicao por classe
  double sm[NCLS], sq2[NCLS]; uint16_t cn[NCLS]; uint32_t correct = 0;
  for (int c = 0; c < NCLS; c++) { sm[c] = sq2[c] = 0; cn[c] = 0; }
  float dk[NCLS];
  for (uint16_t i = 0; i < lgN; i++) {
    int c = lgE[i].cls;
    if (!mdl.active[c]) { looPred[i] = -1; looRatio[i] = 0; continue; }
    classDist(lgE[i].f, i, dk);
    int b = -1; float bd = 1e30f;
    for (int k = 0; k < NCLS; k++) if (mdl.active[k] && dk[k] < bd) { bd = dk[k]; b = k; }
    looPred[i] = (int8_t)b;
    looRatio[i] = dk[c];
    if (b >= 0) { mdl.conf[c][b]++; if (b == c) correct++; }
    sm[c] += dk[c]; sq2[c] += (double)dk[c] * dk[c]; cn[c]++;
  }
  for (int c = 0; c < NCLS; c++) {
    if (!mdl.active[c] || cn[c] == 0) continue;
    double mean = sm[c] / cn[c];
    double sd = sqrt(fmax(sq2[c] / cn[c] - mean * mean, 0.0));
    mdl.rej[c] = (float)(mean + 3.0 * fmax(sd, 0.2 * mean));
  }
  for (uint16_t i = 0; i < lgN; i++)
    if (looPred[i] >= 0) looRatio[i] = looRatio[i] / mdl.rej[lgE[i].cls];
  looN = lgN;
  mdl.acc = (N > 0) ? (float)correct / N : 0.0f;
  mdl.ok = true;
  mdlDirty = false;
  return true;
}

void modelReport() {
  uint32_t tot = 0; int nAct = 0;
  for (int c = 0; c < NCLS; c++) { tot += mdl.n[c]; if (mdl.active[c]) nAct++; }
  out("\n=== MODELO ===\n");
  out("golpes: %lu | classes ativas: %d/%d | precisao LOO: %.1f%%\n",
      (unsigned long)tot, nAct, NCLS, mdl.acc * 100.0f);
  for (int c = 0; c < NCLS; c++) {
    if (mdl.n[c] == 0) continue;
    if (!mdl.active[c]) { out(" %s n=%-3u INATIVA (min %d)\n", CLSAB[c], mdl.n[c], MIN_PER_CLASS); continue; }
    uint16_t t = 0; for (int p = 0; p < NCLS; p++) t += mdl.conf[c][p];
    char cf[48] = ""; int shown = 0; size_t used = 0;
    uint8_t skip[NCLS]; memset(skip, 0, sizeof(skip)); skip[c] = 1;
    for (int k = 0; k < 2; k++) {
      int bp = -1;
      for (int p = 0; p < NCLS; p++) if (!skip[p] && mdl.conf[c][p] > 0 && (bp < 0 || mdl.conf[c][p] > mdl.conf[c][bp])) bp = p;
      if (bp < 0) break;
      skip[bp] = 1; shown++;
      used += snprintf(cf + used, sizeof(cf) - used, " %s(%u)", CLSAB[bp], mdl.conf[c][bp]);
    }
    out(" %s n=%-3u acerto=%3.0f%%  lim=%.2f%s%s\n", CLSAB[c], mdl.n[c],
        t ? 100.0f * mdl.conf[c][c] / t : 0.0f, mdl.rej[c], shown ? "  confunde:" : "", cf);
  }
  out("features mais discriminativas:");
  uint8_t used[NFEAT]; memset(used, 0, sizeof(used));
  for (int k = 0; k < 6; k++) {
    int bj = -1;
    for (int j = 0; j < NFEAT; j++) if (!used[j] && (bj < 0 || mdl.w[j] > mdl.w[bj])) bj = j;
    if (bj < 0) break;
    used[bj] = 1; out(" %s(%.1f)", FEATNAME[bj], mdl.w[bj]);
  }
  out("\n\n");
}

// ------------------------- log: adicionar / listar / excluir ----------------
bool logAdd(int c, const float *f) {
  if (lgN >= MAXLOG) { out("!! log cheio (%u). Use 'x' para abrir espaco.\n", MAXLOG); return false; }
  LogEntry &e = lgE[lgN];
  memset(&e, 0, sizeof(e));
  e.cls = (uint8_t)c;
  memcpy(e.f, f, sizeof(float) * NFEAT);
  if (!fsAppend(e)) out("!! falha ao gravar no flash (golpe so na RAM)\n");
  lgN++;
  mdlDirty = true;
  return true;
}

void logList(int filter) {
  if (lgN == 0) { out("# log vazio\n"); return; }
  out("\n idx cod   gPk  aPk shock tImp elev dist/lim prev\n");
  for (uint16_t i = 0; i < lgN; i++) {
    if (filter >= 0 && lgE[i].cls != filter) continue;
    const float *f = lgE[i].f;
    char ds[12] = "      -", pv[8] = "";
    if (mdl.ok && i < looN && looPred[i] >= 0) {
      snprintf(ds, sizeof(ds), "%7.2f", looRatio[i]);
      if (looPred[i] != lgE[i].cls) snprintf(pv, sizeof(pv), "<%s", CLSAB[looPred[i]]);
    }
    out("%4u %s %5.0f %4.1f %5.1f %4.0f %+4.0f %s %s\n", i + 1, CLSAB[lgE[i].cls],
        f[F_GPK], f[F_APK], f[F_SHOCK], f[F_TIMP], f[F_PATHELEV], ds, pv);
  }
  out("total no log: %u/%u\n\n", lgN, MAXLOG);
}

void logOutliers() {
  if (lgN == 0) { out("# log vazio\n"); return; }
  if (!mdl.ok || mdlDirty) { if (!computeModel(false)) return; }
  out("\n--- golpes mais discrepantes (candidatos a exclusao) ---\n");
  static uint8_t used[MAXLOG]; memset(used, 0, sizeof(used));
  for (int k = 0; k < 10; k++) {
    int bi = -1;
    for (uint16_t i = 0; i < looN; i++)
      if (!used[i] && looPred[i] >= 0 && (bi < 0 || looRatio[i] > looRatio[bi])) bi = i;
    if (bi < 0) break;
    used[bi] = 1;
    const char *why = "";
    char tmp[24] = "";
    if (looPred[bi] != lgE[bi].cls) { snprintf(tmp, sizeof(tmp), "<< parece %s", CLSAB[looPred[bi]]); why = tmp; }
    else if (looRatio[bi] > 1.0f) why = "<< fora do padrao";
    out("%4d %s dist/lim=%5.2f  w=%.0f a=%.1f %s\n", bi + 1, CLSAB[lgE[bi].cls], looRatio[bi],
        lgE[bi].f[F_GPK], lgE[bi].f[F_APK], why);
  }
  out("Excluir: x <idx>\n\n");
}

int logDeleteRange(uint16_t a, uint16_t b) {   // indices 1-based, inclusivos
  if (a < 1 || b > lgN || a > b) return 0;
  uint16_t cnt = b - a + 1;
  for (uint16_t i = b; i < lgN; i++) lgE[i - cnt] = lgE[i];
  lgN -= cnt;
  fsRewrite();
  mdlDirty = true;
  return cnt;
}

int logDeleteClass(int c) {
  uint16_t w = 0, removed = 0;
  for (uint16_t i = 0; i < lgN; i++) {
    if (lgE[i].cls == c) { removed++; continue; }
    lgE[w++] = lgE[i];
  }
  lgN = w;
  fsRewrite();
  mdlDirty = true;
  return removed;
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
  trigIdx = n;
}

// ---------------------------- extracao de features --------------------------
// Pipeline:  unidades -> fases (pico/avanco/follow-through) -> impacto ->
//            filtro Mahony (orientacao) -> gravidade removida -> trajetoria.
bool computeFeatures(float *f, SwingInfo &si) {
  Scratch &s = *S;
  const int n = winCount;
  si.valid = false; si.why = ""; si.impact = false; si.sat = false;
  si.gPk = 0; si.durMs = 0; si.vHeadKmh = 0;
  memset(f, 0, sizeof(float) * NFEAT);
  if (n < 100) { si.why = "janela curta"; return false; }

  // 1) unidades, magnitudes, picos e saturacao
  float gPk = 0, aPk = 0; int iPk = 0;
  for (int i = 0; i < n; i++) {
    const Sample &r = win[i];
    if (abs(r.gx) > 32000 || abs(r.gy) > 32000 || abs(r.gz) > 32000 ||
        abs(r.ax) > 32000 || abs(r.ay) > 32000 || abs(r.az) > 32000) si.sat = true;
    toUnits(r, s.A[i], s.G[i]);
    s.AM[i] = vlen3(s.A[i]); s.GM[i] = vlen3(s.G[i]);
    if (s.AM[i] > aPk) aPk = s.AM[i];
    if (s.GM[i] > gPk) { gPk = s.GM[i]; iPk = i; }
  }
  si.gPk = gPk;
  for (int i = 0; i < n; i++) {
    int a = i > 0 ? i - 1 : i, b = i < n - 1 ? i + 1 : i;
    s.GS[i] = (s.GM[a] + s.GM[i] + s.GM[b]) / 3.0f;
  }

  // 2) fases: avanco = trecho continuo acima de 20% do pico em torno do pico
  float thr = 0.20f * gPk; if (thr < 60.0f) thr = 60.0f;
  int iS = iPk; while (iS > 0 && s.GS[iS - 1] > thr) iS--;
  int iE = iPk; while (iE < n - 1 && s.GS[iE + 1] > thr) iE++;
  si.durMs = (iE - iS) * DT_MS;

  // validacao: descarta o que nao parece golpe
  if (gPk < 0.6f * trigGyrDps) { si.why = "sem rotacao de golpe"; return false; }
  if (si.durMs < 40.0f)        { si.why = "pulso curto (batida/ruido)"; return false; }
  if (si.durMs > 900.0f)       { si.why = "movimento longo (nao e golpe)"; return false; }

  float rot[3] = {0, 0, 0}, rotTot = 0, gsq = 0;
  for (int i = iS; i <= iE; i++) {
    for (int k = 0; k < 3; k++) rot[k] += s.G[i][k] * DT;
    rotTot += s.GM[i] * DT; gsq += s.GM[i] * s.GM[i];
  }
  float gRms = sqrtf(gsq / (iE - iS + 1));
  if (rotTot < 30.0f) { si.why = "rotacao total pequena"; return false; }

  // 3) impacto: pico da 2a diferenca da aceleracao perto do pico de giro
  for (int i = 0; i < n; i++) {
    if (i < 2) { s.J[i] = 0; continue; }
    float dx = s.A[i][0] - 2 * s.A[i-1][0] + s.A[i-2][0];
    float dy = s.A[i][1] - 2 * s.A[i-1][1] + s.A[i-2][1];
    float dz = s.A[i][2] - 2 * s.A[i-1][2] + s.A[i-2][2];
    s.J[i] = sqrtf(dx*dx + dy*dy + dz*dz);
  }
  int lo = iPk - 25; if (lo < iS) lo = iS; if (lo < 2) lo = 2;
  int hi = iPk + 40; if (hi > iE) hi = iE; if (hi > n - 1) hi = n - 1;
  int iI = iPk; float shock = 0;
  for (int i = lo; i <= hi; i++) if (s.J[i] > shock) { shock = s.J[i]; iI = i; }
  if (shock < shockThr) iI = iPk;     // sem choque claro (swing no ar): contato ~ pico de giro
  if (iI < iS) iI = iS;

  // eixo de rotacao no pico (media de 7 amostras, normalizada)
  float u[3] = {0, 0, 0};
  for (int i = iPk - 3; i <= iPk + 3; i++) {
    int k = i < 0 ? 0 : (i >= n ? n - 1 : i);
    for (int c = 0; c < 3; c++) u[c] += s.G[k][c];
  }
  float un = vlen3(u);
  if (un > 1e-3f) { for (int c = 0; c < 3; c++) u[c] /= un; } else { u[0] = u[1] = u[2] = 0; }

  // 4) filtro Mahony sobre a janela: orientacao, gravidade removida, trajetoria
  int n0 = trigIdx < 40 ? trigIdx : 40; if (n0 < 5) n0 = n < 5 ? n : 5;
  float m[3] = {0, 0, 0};
  for (int i = 0; i < n0; i++) for (int c = 0; c < 3; c++) m[c] += s.A[i][c];
  float mn = vlen3(m);
  float q0 = 1, q1 = 0, q2 = 0, q3 = 0;
  if (mn > 0.5f * n0) {
    float roll  = atan2f(m[1], m[2]);
    float pitch = atan2f(-m[0], sqrtf(m[1]*m[1] + m[2]*m[2]));
    float cr = cosf(roll * 0.5f), sr = sinf(roll * 0.5f);
    float cp = cosf(pitch * 0.5f), sp = sinf(pitch * 0.5f);
    q0 = cr * cp; q1 = sr * cp; q2 = cr * sp; q3 = -sr * sp;
  }
  const float twoKp = 3.0f;
  float vx = 0, vy = 0, vz = 0, dYaw = 0, aLinPk = 0;
  for (int i = 0; i < n; i++) {
    float gx = s.G[i][0] * D2R, gy = s.G[i][1] * D2R, gz = s.G[i][2] * D2R;
    float ax = s.A[i][0], ay = s.A[i][1], az = s.A[i][2], an = s.AM[i];
    if (fabsf(an - 1.0f) < 0.15f) {         // so corrige com o acelerometro quando ~1 g
      float inv = 1.0f / an, axn = ax * inv, ayn = ay * inv, azn = az * inv;
      float hvx = q1*q3 - q0*q2, hvy = q0*q1 + q2*q3, hvz = q0*q0 - 0.5f + q3*q3;
      gx += twoKp * (ayn * hvz - azn * hvy);
      gy += twoKp * (azn * hvx - axn * hvz);
      gz += twoKp * (axn * hvy - ayn * hvx);
    }
    gx *= 0.5f * DT; gy *= 0.5f * DT; gz *= 0.5f * DT;
    float qa = q0, qb = q1, qc = q2;
    q0 += (-qb * gx - qc * gy - q3 * gz);
    q1 += ( qa * gx + qc * gz - q3 * gy);
    q2 += ( qa * gy - qb * gz + q3 * gx);
    q3 += ( qa * gz + qb * gy - qc * gx);
    float nr = 1.0f / sqrtf(q0*q0 + q1*q1 + q2*q2 + q3*q3);
    q0 *= nr; q1 *= nr; q2 *= nr; q3 *= nr;

    // 3a linha da matriz corpo->mundo = "para cima" no referencial do sensor
    float r3x = 2 * (q1*q3 - q0*q2), r3y = 2 * (q0*q1 + q2*q3), r3z = q0*q0 - q1*q1 - q2*q2 + q3*q3;
    s.V[i][0] = r3x; s.V[i][1] = r3y; s.V[i][2] = r3z;
    float lx = ax - r3x, ly = ay - r3y, lz = az - r3z;       // aceleracao linear (g)
    s.AL[i] = sqrtf(lx*lx + ly*ly + lz*lz);
    if (s.AL[i] > aLinPk) aLinPk = s.AL[i];
    if (i >= iS && i <= iI) {                                // avanco ate o impacto
      float r1x = 1 - 2 * (q2*q2 + q3*q3), r1y = 2 * (q1*q2 - q0*q3), r1z = 2 * (q1*q3 + q0*q2);
      float r2x = 2 * (q1*q2 + q0*q3), r2y = 1 - 2 * (q1*q1 + q3*q3), r2z = 2 * (q2*q3 - q0*q1);
      vx += (r1x * lx + r1y * ly + r1z * lz) * DT;
      vy += (r2x * lx + r2y * ly + r2z * lz) * DT;
      vz += (r3x * lx + r3y * ly + r3z * lz) * DT;
      dYaw += (r3x * s.G[i][0] + r3y * s.G[i][1] + r3z * s.G[i][2]) * DT;
    }
  }
  float vVert = vz * 9.81f, vHor = sqrtf(vx*vx + vy*vy) * 9.81f;
  float elev = atan2f(vVert, vHor) * R2D;

  float aImp = 0; int ac = 0;
  for (int i = iI - 2; i <= iI + 2; i++) if (i >= 0 && i < n) { aImp += s.AL[i]; ac++; }
  aImp = ac ? aImp / ac : 0;

  // 5) backswing: trecho antes do avanco em que a rotacao e contraria ao eixo principal
  float thrB = 0.04f * gPk; if (thrB < 40.0f) thrB = 40.0f;
  int iB0 = -1; float backAng = 0, backPk = 0;
  for (int i = 0; i < iS; i++) {
    float gu = s.G[i][0]*u[0] + s.G[i][1]*u[1] + s.G[i][2]*u[2];
    if (iB0 < 0 && gu < -thrB) iB0 = i;
    if (iB0 >= 0) { backAng += gu * DT; if (-gu > backPk) backPk = -gu; }
  }
  float tBack = iB0 >= 0 ? (iS - iB0) * DT_MS : 0.0f;
  float axVert = s.V[iPk][0]*u[0] + s.V[iPk][1]*u[1] + s.V[iPk][2]*u[2];

  f[F_GPK] = gPk; f[F_APK] = aPk; f[F_ALINPK] = aLinPk; f[F_GRMS] = gRms; f[F_SHOCK] = shock;
  f[F_WUX] = u[0]; f[F_WUY] = u[1]; f[F_WUZ] = u[2];
  f[F_ROTX] = rot[0]; f[F_ROTY] = rot[1]; f[F_ROTZ] = rot[2]; f[F_ROTTOT] = rotTot;
  f[F_BACKANG] = fabsf(backAng); f[F_BACKPK] = backPk;
  f[F_TBACK] = tBack; f[F_TFWD] = (iPk - iS) * DT_MS; f[F_TFOL] = (iE - iPk) * DT_MS;
  f[F_TIMP] = (iI - iPk) * DT_MS;
  f[F_PVX] = s.V[iS][0]; f[F_PVY] = s.V[iS][1]; f[F_PVZ] = s.V[iS][2];
  f[F_IVX] = s.V[iI][0]; f[F_IVY] = s.V[iI][1]; f[F_IVZ] = s.V[iI][2];
  f[F_DYAW] = dYaw; f[F_AXVERT] = axVert;
  f[F_VVERT] = vVert; f[F_VHOR] = vHor; f[F_PATHELEV] = elev; f[F_AIMP] = aImp;

  si.impact = shock >= shockThr;
  si.vHeadKmh = gPk * D2R * armM * 3.6f;       // estimativa: omega x braco
  si.valid = true;
  return true;
}

// ------------------------------ relatorios ----------------------------------
void showTrainStats() {
  if (lgN == 0) { out("# log vazio\n"); return; }
  static const int sel[7] = { F_GPK, F_APK, F_TFWD, F_TBACK, F_PATHELEV, F_DYAW, F_VVERT };
  outU("\n=== ESTATISTICAS (media; gPk com desvio) ===\n");
  outU("cod   n      gPk           aPk  tFwd tBack  elev  dYaw vVert\n");
  for (int c = 0; c < NCLS; c++) {
    double s1[7] = {0}, s2[7] = {0}; int cnt = 0;
    for (uint16_t i = 0; i < lgN; i++) {
      if (lgE[i].cls != c) continue;
      cnt++;
      for (int k = 0; k < 7; k++) { double x = lgE[i].f[sel[k]]; s1[k] += x; s2[k] += x * x; }
    }
    if (!cnt) continue;
    double mg = s1[0] / cnt, sd = sqrt(fmax(s2[0] / cnt - mg * mg, 0.0));
    outU("%-4s %3d %6.0f+-%-5.0f %6.1f %5.0f %5.0f %+5.0f %+5.0f %+5.1f\n", CLSAB[c], cnt, mg, sd,
         s1[1] / cnt, s1[2] / cnt, s1[3] / cnt, s1[4] / cnt, s1[5] / cnt, s1[6] / cnt);
  }
  outU("\n");
}

void showSession() {
  out("\n--- SESSAO ---\n");
  unsigned long tot = 0;
  for (int c = 0; c < NCLS; c++) {
    if (!sessCount[c]) continue;
    tot += sessCount[c];
    out(" %-12s %3lu   vel media ~%.0f / max ~%.0f km/h\n", CLS[c], sessCount[c],
        sessSum[c] / sessCount[c], sessMax[c]);
  }
  out(" total %lu | nao identificados %lu | ignorados %lu\n\n", tot, sessUnknown, sessIgnored);
}

void listClasses() {
  uint16_t cnt[NCLS]; memset(cnt, 0, sizeof(cnt));
  for (uint16_t i = 0; i < lgN; i++) cnt[lgE[i].cls]++;
  out("\n cod  classe        n\n");
  for (int c = 0; c < NCLS; c++)
    out(" %s  %-12s %3u%s%s\n", CLSAB[c], CLS[c], cnt[c], cnt[c] < MIN_PER_CLASS ? " (poucos)" : "",
        c == curClass ? " <- treino" : "");
  out("\n");
}

// ------------------------------ rotina de treino ----------------------------
void drillSummary() {
  out("\n=== RESUMO DA ROTINA ===\n");
  for (int i = 0; i < drill.n; i++) {
    unsigned t = drill.hit[i] + drill.miss[i];
    out(" %s meta %u: acertos %u erros %u (%.0f%%)", CLSAB[drill.cls[i]], drill.goal[i], drill.hit[i],
        drill.miss[i], t ? 100.0f * drill.hit[i] / t : 0.0f);
    if (drill.hit[i]) out("  ~%.0f km/h (max %.0f)", drill.vSum[i] / drill.hit[i], drill.vMax[i]);
    out("\n");
  }
  out("\n");
}

void drillFeed(int c, bool rej, float kmh) {
  if (!drill.on) return;
  int s = drill.step;
  if (!rej && c == drill.cls[s]) {
    drill.hit[s]++; drill.vSum[s] += kmh; if (kmh > drill.vMax[s]) drill.vMax[s] = kmh;
    out("   [rotina %d/%d] %s %u/%u\n", s + 1, drill.n, CLSAB[drill.cls[s]], drill.hit[s], drill.goal[s]);
    if (drill.hit[s] >= drill.goal[s]) {
      if (s + 1 < drill.n) { drill.step++; out("   >> proxima etapa: %s x%u\n", CLS[drill.cls[s + 1]], drill.goal[s + 1]); }
      else { drillSummary(); drill.on = false; }
    }
  } else {
    drill.miss[s]++;
    if (rej) out("   [rotina] nao identificado (esperado %s)\n", CLSAB[drill.cls[s]]);
    else     out("   [rotina] esperado %s, veio %s\n", CLSAB[drill.cls[s]], CLSAB[c]);
  }
}

void drillStart(char *args) {
  Drill d; memset(&d, 0, sizeof(d));
  char *tok = strtok(args, " ,;");
  while (tok && d.n < 8) {
    int c = parseCls(tok);
    if (c < 0) { out("# classe invalida: %s (use 'n')\n", tok); return; }
    char *cnt = strtok(NULL, " ,;");
    int g = cnt ? atoi(cnt) : 0;
    if (g <= 0) { out("# faltou a quantidade apos %s\n", CLSAB[c]); return; }
    d.cls[d.n] = (uint8_t)c; d.goal[d.n] = (uint16_t)g; d.n++;
    tok = strtok(NULL, " ,;");
  }
  if (!d.n) { out("# use: e fhd 10 bhd 10 spl 5\n"); return; }
  if (!mdl.ok || mdlDirty) { if (!computeModel(false)) return; }
  for (int i = 0; i < d.n; i++)
    if (!mdl.active[d.cls[i]]) { out("# %s sem amostras suficientes no modelo\n", CLSAB[d.cls[i]]); return; }
  d.on = true; drill = d;
  mode = MODE_CLASSIFY; capturing = false;
  out("\n# ROTINA INICIADA: etapa 1 -> %s x%u  ('e' encerra)\n\n", CLS[drill.cls[0]], drill.goal[0]);
}

// ------------------------------- evento de golpe ----------------------------
void onSwing() {
  float f[NFEAT]; SwingInfo si;
  bool ok = computeFeatures(f, si);
  if (!ok) {
    sessIgnored++;
    if (mode == MODE_TRAIN || verbose)
      out("# ignorado: %s (w=%.0f dur=%.0fms)\n", si.why, si.gPk, si.durMs);
    return;
  }
  if (si.sat) out("   !! sensor saturou (+-16g/+-2000dps): valores subestimados\n");

  if (mode == MODE_TRAIN) {
    if (mdl.ok) {                              // confere o rotulo com o modelo atual
      float dk[NCLS]; classDist(f, -1, dk);
      Pred p = decide(dk);
      if (p.c1 >= 0 && p.c1 != curClass && !p.reject)
        out("   ?? parece %s (d=%.2f) e nao %s\n", CLSAB[p.c1], p.d1, CLSAB[curClass]);
    }
    if (logAdd(curClass, f)) {
      int cn = 0; for (uint16_t i = 0; i < lgN; i++) if (lgE[i].cls == curClass) cn++;
      out("[treino] %s #%d (log %u/%u) w=%.0f a=%.1fg ~%.0fkm/h %s\n", CLSAB[curClass], cn, lgN, MAXLOG,
          f[F_GPK], f[F_APK], si.vHeadKmh, si.impact ? "" : "sem-impacto");
    }
    return;
  }
  if (mode == MODE_FEAT) {
    outU("%lu,%s", ++swingNum, CLSAB[curClass]);
    for (int j = 0; j < NFEAT; j++) outU(",%.3f", f[j]);
    outU("\n");
    return;
  }
  if (mode == MODE_CLASSIFY) {
    if (!mdl.ok || mdlDirty) { if (!computeModel(false)) { out("# sem modelo\n"); return; } }
    float dk[NCLS]; classDist(f, -1, dk);
    Pred p = decide(dk);
    swingNum++;
    if (p.c1 < 0) { out("# sem classes ativas\n"); return; }
    if (p.reject) {
      sessUnknown++;
      out("#%-3lu  ???  nao identificado (perto de %s, d=%.2f lim=%.2f) w=%.0f\n", swingNum,
          CLSAB[p.c1], p.d1, mdl.rej[p.c1] * rejectK, f[F_GPK]);
      drillFeed(p.c1, true, si.vHeadKmh);
      return;
    }
    int c = p.c1;
    sessCount[c]++; sessSum[c] += si.vHeadKmh; if (si.vHeadKmh > sessMax[c]) sessMax[c] = si.vHeadKmh;
    out("#%-3lu  >> %-12s %3.0f%%  ~%.0fkm/h a=%.1fg %s\n", swingNum, CLS[c], p.conf * 100.0f,
        si.vHeadKmh, f[F_APK], si.impact ? "" : "(sem impacto?)");
    if (p.ambig && p.c2 >= 0)
      out("        ambiguo: %s %.2f x %s %.2f\n", CLSAB[c], p.d1, CLSAB[p.c2], p.d2);
    if (verbose)
      out("        elev=%+.0f dYaw=%+.0f back=%.0fms fwd=%.0fms imp=%+.0fms\n",
          f[F_PATHELEV], f[F_DYAW], f[F_TBACK], f[F_TFWD], f[F_TIMP]);
    drillFeed(c, false, si.vHeadKmh);
  }
}

// ------------------------------- comandos -----------------------------------
void printHelp() {
  out("\n========== COMANDOS ==========\n");
  out(" k classificar   a treinar\n");
  out(" m modelo/relatorio  q parar\n");
  out(" l <cod>  classe de treino\n");
  out(" n  lista de classes\n");
  out(" e <cod> <n> ... rotina\n");
  out("   ex: e fhd 10 bhd 10 spl 5\n");
  out("-- edicao do treino --\n");
  out(" g [cod]    listar golpes\n");
  out(" o          mais suspeitos\n");
  out(" x u | x 12 | x 12-15 | x <cod>\n");
  out(" z sim      apaga TODO o log\n");
  out("-- ajustes --\n");
  out(" t <g> <dps>  gatilhos\n");
  out(" u <k> rejeicao  i <g> impacto\n");
  out(" w <m> braco da raquete\n");
  out(" p parametros  v estatisticas\n");
  out(" s sessao  d detalhes\n");
  out(" f features  r raw  h ajuda\n");
  out("==============================\n\n");
}

void calPrint() {
  outU("--- Calibracao ---\n");
  outU("  offset(g): %+.4f %+.4f %+.4f\n", cal.aOff[0], cal.aOff[1], cal.aOff[2]);
  outU("  escala   : %.5f %.5f %.5f\n",    cal.aScl[0], cal.aScl[1], cal.aScl[2]);
  outU("  gyro     : %+.3f %+.3f %+.3f\n", cal.gOff[0], cal.gOff[1], cal.gOff[2]);
  out ("  gatilhos %.1fg/%.0fdps | refrat %lums | rej x%.2f\n", trigAccG, trigGyrDps,
       (unsigned long)refracMs, rejectK);
  out ("  impacto>=%.1fg | braco %.2fm | chip %.1fC\n", shockThr, armM, tempC);
  out ("  log %u/%u | modelo: %s | classe: %s\n", lgN, MAXLOG, mdl.ok ? "OK" : "AUSENTE", CLS[curClass]);
}

void handleCommand(char *c) {
  while (*c == ' ') c++;
  char *arg = c + 1;
  while (*arg == ' ') arg++;

  switch (tolower((unsigned char)c[0])) {
    case 'h': printHelp(); break;
    case 'p': calPrint(); break;
    case 'v': showTrainStats(); break;
    case 's': showSession(); break;
    case 'n': listClasses(); break;

    case 'g': {
      int fc = *arg ? parseCls(arg) : -1;
      if (*arg && fc < 0) { out("# classe invalida (use 'n')\n"); break; }
      logList(fc);
      break;
    }

    case 'o': logOutliers(); break;

    case 'x': {
      if (!*arg) { out("# use: x u | x 12 | x 12-15 | x fhd\n"); break; }
      if (tolower((unsigned char)*arg) == 'u') {
        if (lgN == 0) { out("# log vazio\n"); break; }
        int cls = lgE[lgN - 1].cls;
        logDeleteRange(lgN, lgN);
        out("# ultimo golpe (%s) excluido. Restam %u.\n", CLSAB[cls], lgN);
      } else if (isdigit((unsigned char)*arg)) {
        int a = atoi(arg), b = a;
        char *dash = strchr(arg, '-');
        if (dash) b = atoi(dash + 1);
        int n = (a >= 1 && b >= a) ? logDeleteRange((uint16_t)a, (uint16_t)b) : 0;
        if (n) out("# %d golpe(s) excluido(s). Restam %u.\n", n, lgN);
        else   out("# indice invalido (log tem %u golpes)\n", lgN);
      } else {
        int cl = parseCls(arg);
        if (cl < 0) { out("# argumento invalido\n"); break; }
        int n = logDeleteClass(cl);
        out("# %d golpe(s) de %s excluido(s). Restam %u.\n", n, CLS[cl], lgN);
      }
      if (mdl.ok) {
        if (computeModel(true)) out("# modelo recalculado\n");
        else out("# dados insuficientes: modelo invalidado\n");
      }
      break;
    }

    case 'l': {
      int cl = isdigit((unsigned char)*arg) ? atoi(arg) : parseCls(arg);
      if (cl < 0 || cl >= NCLS) { out("# use: l <cod>  (veja 'n')\n"); break; }
      curClass = cl;
      out("# classe = %s (%s)\n", CLS[curClass], CLSAB[curClass]);
      break;
    }

    case 'a':
      mode = MODE_TRAIN; capturing = false; drill.on = false;
      out("\n# TREINO -> %s (log: %u/%u)\n", CLS[curClass], lgN, MAXLOG);
      break;

    case 'k':
      if (!mdl.ok || mdlDirty) { if (!computeModel(false)) break; }
      mode = MODE_CLASSIFY; capturing = false;
      out("\n# CLASSIFICANDO\n\n");
      break;

    case 'm':
      if (computeModel(false)) modelReport();
      break;

    case 'e':
      if (!*arg) {
        if (drill.on) { drillSummary(); drill.on = false; }
        else out("# use: e fhd 10 bhd 10 spl 5\n");
      } else drillStart(arg);
      break;

    case 'f':
      mode = MODE_FEAT; capturing = false;
      outU("\ngolpe,rotulo");
      for (int j = 0; j < NFEAT; j++) outU(",%s", FEATNAME[j]);
      outU("\n");
      break;

    case 'r': mode = MODE_RAW; outU("\nt_ms,ax,ay,az,gx,gy,gz\n"); break;
    case 'q': mode = MODE_IDLE; capturing = false; out("# parado\n"); break;

    case 'z':
      if (tolower((unsigned char)arg[0]) == 's') {
        lgN = 0; mdl.ok = false; mdlDirty = true; looN = 0;
        fsRewrite();
        out("# TODO o log foi apagado (calibracao mantida)\n");
      } else out("# isto apaga todos os golpes. Confirme com: z sim\n");
      break;

    case 'd': verbose = !verbose; outU("# detalhes %s\n", verbose ? "ON" : "OFF"); break;

    case 't': { float a, g;
      if (sscanf(arg, "%f %f", &a, &g) == 2) { trigAccG = a; trigGyrDps = g; }
      out("# gatilhos %.1fg / %.0fdps\n", trigAccG, trigGyrDps); break; }

    case 'u': { float v;
      if (sscanf(arg, "%f", &v) == 1 && v > 0) rejectK = v;
      out("# rejeicao x%.2f (maior = mais permissivo)\n", rejectK); break; }

    case 'i': { float v;
      if (sscanf(arg, "%f", &v) == 1 && v > 0) shockThr = v;
      out("# limiar de impacto %.1fg\n", shockThr); break; }

    case 'w': { float v;
      if (sscanf(arg, "%f", &v) == 1 && v > 0.1f && v < 1.5f) armM = v;
      out("# braco sensor->cordas %.2fm\n", armM); break; }

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
    SerialBT.printf("\n== RAQUETE-TENIS v2 ==\nlog: %u golpes | modelo: %s\n'h' ajuda\n\n",
                    lgN, mdl.ok ? "OK" : "ausente");
    Serial.println("# celular conectado");
  } else if (!now && btWasConnected) Serial.println("# celular desconectado");
  btWasConnected = now;
}

// --------------------------------- setup / loop -----------------------------
void setup() {
  Serial.begin(115200); delay(600);
  Serial.println(F("\n\n=== Detector de golpes v2 ==="));
  // buffers grandes alocados ANTES de ligar o Bluetooth
  lgE = (LogEntry *)malloc(sizeof(LogEntry) * MAXLOG);
  S   = (Scratch *)malloc(sizeof(Scratch));
  if (!lgE || !S) { Serial.println("ERRO: memoria insuficiente"); while (1) delay(1000); }
  memset(&drill, 0, sizeof(drill));
  memset(&mdl, 0, sizeof(mdl));
  if (!mpuInit()) { while (1) delay(1000); }
  fsOK = LittleFS.begin(true);
  loadAll();
  btReady = SerialBT.begin(BT_NAME);
  Serial.println(btReady ? "Bluetooth ativo: RAQUETE-TENIS" : "FALHA no Bluetooth");
  calPrint();
  printHelp();
  if (lgN > 0 && computeModel(true)) { mode = MODE_CLASSIFY; Serial.println(F("# classificando\n")); }
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
  float am = vlen3(a), gm = vlen3(g);
  if ((am >= trigAccG || gm >= trigGyrDps) && (millis() - lastTrigMs) > refracMs) {
    lastTrigMs = millis();
    ringToWindow();
    capturing = true;
  }
}
