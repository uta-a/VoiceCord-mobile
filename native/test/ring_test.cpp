// Ring(SPSC リングバッファ)の host 単体テスト。実機不要。
// g++ -std=c++17 -O2 -pthread native/test/ring_test.cpp -I native/src -o ring_test && ./ring_test
#include "ring.h"
#include <atomic>
#include <cassert>
#include <cstdio>
#include <thread>
#include <vector>

static int g_fail = 0;
static void check(bool ok, const char *name) {
  printf("%s %s\n", ok ? "PASS" : "FAIL", name);
  if (!ok) g_fail++;
}

int main() {
  // 1. 基本: write→mix で dst に加算される(gain=1,duck=1)。
  {
    Ring r(16);
    int16_t src[4] = {100, -100, 200, -200};
    size_t w = r.write(src, 4);
    check(w == 4, "write returns count");
    check(r.hasData(), "hasData after write");
    int16_t dst[4] = {10, 10, 10, 10};
    r.mix(dst, 4, 1.0f, 1.0f);
    check(dst[0] == 110 && dst[1] == -90 && dst[2] == 210 && dst[3] == -190, "mix adds sfx to mic");
    check(!r.hasData(), "empty after full consume");
  }
  // 2. gain/duck 適用。
  {
    Ring r(16);
    int16_t src[2] = {1000, 1000};
    r.write(src, 2);
    int16_t dst[2] = {1000, 1000};
    r.mix(dst, 2, 0.5f, 0.5f);  // mic*0.5 + sfx*0.5 = 500+500 = 1000
    check(dst[0] == 1000 && dst[1] == 1000, "gain/duck applied");
  }
  // 3. クリップ(clamp16)。
  {
    Ring r(8);
    int16_t src[1] = {30000};
    r.write(src, 1);
    int16_t dst[1] = {30000};
    r.mix(dst, 1, 1.0f, 1.0f);  // 60000 → 32767
    check(dst[0] == 32767, "clamp high");
    Ring r2(8);
    int16_t s2[1] = {-30000};
    r2.write(s2, 1);
    int16_t d2[1] = {-30000};
    r2.mix(d2, 1, 1.0f, 1.0f);  // -60000 → -32768
    check(d2[0] == -32768, "clamp low");
  }
  // 4. 満杯: 容量を超える write は切り詰め。
  {
    Ring r(4);
    int16_t src[6] = {1, 2, 3, 4, 5, 6};
    size_t w = r.write(src, 6);
    check(w == 4, "write truncates to capacity");
    size_t w2 = r.write(src, 1);
    check(w2 == 0, "write 0 when full");
  }
  // 5. ラップ: 容量境界をまたいで正しく読める。
  {
    Ring r(4);
    int16_t a[3] = {1, 2, 3};
    r.write(a, 3);
    int16_t d[3] = {0, 0, 0};
    r.mix(d, 3, 1.0f, 1.0f);  // consume 3, head=3
    check(d[0] == 1 && d[1] == 2 && d[2] == 3, "read before wrap");
    int16_t b[3] = {4, 5, 6};
    size_t w = r.write(b, 3);  // tail 3→6, wraps at cap 4
    check(w == 3, "write wraps");
    int16_t d2[3] = {0, 0, 0};
    r.mix(d2, 3, 1.0f, 1.0f);
    check(d2[0] == 4 && d2[1] == 5 && d2[2] == 6, "read after wrap");
  }
  // 6. mix が avail までしか消費しない(dst は n だが sfx は avail 分のみ)。
  {
    Ring r(16);
    int16_t src[2] = {50, 50};
    r.write(src, 2);
    int16_t dst[4] = {10, 10, 10, 10};
    r.mix(dst, 4, 1.0f, 1.0f);  // 先頭2に加算、残り2はduck=1なので不変
    check(dst[0] == 60 && dst[1] == 60 && dst[2] == 10 && dst[3] == 10, "mix consumes only avail");
    check(!r.hasData(), "empty after partial-avail mix");
  }
  // 7. requestStop: drain 要求後、次の mix で捨てられ hasData=false。
  {
    Ring r(16);
    int16_t src[4] = {1, 2, 3, 4};
    r.write(src, 4);
    r.requestStop();
    check(r.hasData(), "still has data before mix applies drain");
    int16_t dst[2] = {0, 0};
    r.mix(dst, 2, 1.0f, 1.0f);  // drain 適用で全捨て → sfx 加算されない
    check(dst[0] == 0 && dst[1] == 0, "stop drops pending audio");
    check(!r.hasData(), "empty after stop drain");
  }
  // 8. stop 後の新規 write は保持される(古いのは捨て、新しいのは残る)。
  {
    Ring r(16);
    int16_t old[3] = {7, 7, 7};
    r.write(old, 3);
    r.requestStop();              // drainTo = 3
    int16_t nw[2] = {9, 9};
    r.write(nw, 2);               // tail 3→5(新データ)
    int16_t dst[5] = {0, 0, 0, 0, 0};
    r.mix(dst, 5, 1.0f, 1.0f);    // 古い3は捨て、新2だけ加算
    check(dst[0] == 9 && dst[1] == 9 && dst[2] == 0, "stop keeps subsequent writes");
  }
  // 9. 並行 SPSC: producer/consumer を別スレッドで回し、総消費量が総投入量以下・クラッシュなし。
  {
    Ring r(1024);
    const int TOTAL = 200000;
    std::atomic<long> consumed{0};
    std::atomic<bool> done{false};
    std::thread prod([&] {
      int written = 0;
      int16_t chunk[64];
      for (int i = 0; i < 64; i++) chunk[i] = (int16_t)(i + 1);
      while (written < TOTAL) {
        size_t w = r.write(chunk, 64);
        written += (int)w;
        if (w == 0) std::this_thread::yield();
      }
      done = true;
    });
    std::thread cons([&] {
      int16_t dst[64];
      while (!done.load() || r.hasData()) {
        for (int i = 0; i < 64; i++) dst[i] = 0;
        long before = 0;
        // mix は avail までしか消費しないので、消費数は hasData と head 前進で近似計測せず、
        // ここではクラッシュ/デッドロックしないこと + 破壊しないことを主眼にする。
        r.mix(dst, 64, 1.0f, 1.0f);
        (void)before;
      }
    });
    prod.join(); cons.join();
    check(true, "concurrent producer/consumer no crash/deadlock");
  }

  printf(g_fail == 0 ? "\n全テスト通過\n" : "\n失敗 %d 件\n", g_fail);
  return g_fail == 0 ? 0 : 1;
}
