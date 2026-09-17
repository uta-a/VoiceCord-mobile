// SPSC リングバッファ(int16, 48kHz mono)。producer=デコードスレッド、consumer=オーディオスレッドの 1:1。
// stop は g_head を直接触らず drainTo 要求で行い、g_head の writer を consumer 単独に保つ。
// Android 依存が無いので host でも単体テストできる(native/test/ring_test.cpp)。
#pragma once
#include <atomic>
#include <cmath>
#include <cstdint>

class Ring {
 public:
  explicit Ring(size_t cap) : cap_(cap), buf_(new int16_t[cap]) {}
  ~Ring() { delete[] buf_; }
  Ring(const Ring &) = delete;
  Ring &operator=(const Ring &) = delete;

  // producer: 書けたサンプル数を返す。
  size_t write(const int16_t *pcm, size_t n) {
    uint64_t head = head_.load(std::memory_order_acquire);
    uint64_t tail = tail_.load(std::memory_order_relaxed);
    size_t space = cap_ - (size_t)(tail - head);
    if (n > space) n = space;
    for (size_t i = 0; i < n; i++) buf_[(tail + i) % cap_] = pcm[i];
    tail_.store(tail + n, std::memory_order_release);
    return n;
  }

  // consumer: リングから最大 n サンプル読み、dst に gain/duck で加算合成する。
  // head を書くのはこの関数のみ。stop 要求(drainTo)もここで適用する。
  void mix(int16_t *dst, size_t n, float gain, float duck) {
    uint64_t head = head_.load(std::memory_order_relaxed);
    uint64_t tail = tail_.load(std::memory_order_acquire);
    uint64_t drainTo = drain_to_.load(std::memory_order_relaxed);
    if (drainTo > head) {  // stop 要求: 消費済み扱いにして捨てる
      head = (drainTo < tail) ? drainTo : tail;
      head_.store(head, std::memory_order_release);
    }
    size_t avail = (size_t)(tail - head);
    size_t m = (n < avail) ? n : avail;
    for (size_t i = 0; i < m; i++) {
      int mic = (int)std::lround(dst[i] * duck);
      int sfx = (int)std::lround(buf_[(head + i) % cap_] * gain);
      dst[i] = clamp16(mic + sfx);
    }
    if (duck != 1.0f) {  // sfx が尽きた残りも duck 適用(duck<1 のとき自然)
      for (size_t i = m; i < n; i++) dst[i] = clamp16((int)std::lround(dst[i] * duck));
    }
    if (m > 0) head_.store(head + m, std::memory_order_release);
  }

  bool hasData() const {
    return tail_.load(std::memory_order_acquire) != head_.load(std::memory_order_acquire);
  }

  // stop: 現在の tail までを drain 要求する(head は触らない)。
  void requestStop() { drain_to_.store(tail_.load(std::memory_order_acquire), std::memory_order_relaxed); }

  static int16_t clamp16(int v) {
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return (int16_t)v;
  }

 private:
  const size_t cap_;
  int16_t *buf_;
  std::atomic<uint64_t> head_{0};
  std::atomic<uint64_t> tail_{0};
  std::atomic<uint64_t> drain_to_{0};
};
