// 图片滤镜内核：把 Kotlin 侧采样好的 3D LUT（+ 暗角 + 颗粒）作用到 ARGB_8888 位图上。
// 预览（屏幕尺寸的小图，每次拖滑条）和导出（全分辨率，保存 / 分享时一次）走的是同一个函数，
// 所以预览与成品逐像素一致。
//
// 快在这几件事：
// 1. 滤镜、强度、调节在 Kotlin 里先采样成**一只** 33³ LUT，像素侧无论滤镜多复杂都只是一次查表；
// 2. 四面体插值（4 个格点，不是三线性的 8 个），整数定点、无分支：LUT 存 Q6 的 int16，通道分数存 Q8，
//    每个通道值落在哪个格、分数多少预先打成 256 项表，像素循环里没有除法和浮点；
//    arm64 上一个格点一条 vld1、三个通道 4 条 vmlal；和左边像素同色（插画的平涂区）直接复用上一次的结果；
// 3. LUT 交错存成 {r, g, b, pad} 的 8 字节格点，33³ × 8 ≈ 280 KB，常驻 L2；
// 4. 暗角、颗粒与查表融在同一遍里：每个像素只读一次、写一次；
// 5. 行带（16 行）按原子计数器分给各线程抢着做，big.LITTLE 上小核慢也不会拖住整体；
//    线程数按像素量定（每线程 ≥ 512K 像素），屏幕尺寸的预览不为起线程付出比计算还多的时间。
//
// 位图是 premultiplied 的：不透明像素（绝大多数插画）直接算；带透明的像素先反乘、算完再乘回。

#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <system_error>
#include <thread>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {

constexpr int kLutFracBits = 8;    // 通道分数 Q8（0–256）
constexpr int kLutValueBits = 6;   // LUT 值 Q6（0–255 × 64）
constexpr int kBandRows = 16;
constexpr uint64_t kPixelsPerThread = 512 * 1024;
// 颗粒的格子边长按短边换算：短边 ≤ 900px 时一像素一颗，更大的图颗粒等比放大，
// 让屏幕尺寸的预览和几千像素的成品看起来是同一种「颗粒感」。
constexpr uint32_t kGrainDensity = 900;
// 满强度颗粒在中间调上的最大振幅（0–255 域）。
constexpr float kGrainAmplitude = 22.0f;

// 与 Kotlin 侧 NativeImageFilter 的返回码对齐。
constexpr jint kOk = 0;
constexpr jint kBadArgs = -1;
constexpr jint kBadBitmap = -2;
constexpr jint kLockFailed = -3;

struct Kernel {
    // 3D LUT
    bool lut_on = false;
    int n = 0;
    std::vector<int16_t> lut;  // n³ × 4（r, g, b, pad），Q6
    // 每个通道值 → 该通道在 LUT 里的元素偏移（已乘好步长与 4 个分量），像素循环里只剩三次加法。
    int32_t off_r[256]{}, off_g[256]{}, off_b[256]{};
    int32_t step_g = 0, step_b = 0;  // g / b 方向走一格的元素偏移
    uint16_t frac[256]{};

    // 暗角
    bool vignette_on = false;
    std::vector<uint16_t> vlut;  // Q8 乘子（0–256），按 d² 采样
    float cx = 0, cy = 0, inv_r2 = 0;

    // 颗粒
    bool grain_on = false;
    int32_t grain_amp_q8 = 0;  // 振幅 × 256
    uint32_t grain_cell = 1;
};

inline uint32_t hash2(uint32_t x, uint32_t y) {
    uint32_t h = x * 0x8DA6B343U ^ y * 0xD8163841U;
    h ^= h >> 15;
    h *= 0x2C1B3C6DU;
    h ^= h >> 12;
    h *= 0x297A2D39U;
    h ^= h >> 15;
    return h;
}

/** 颗粒噪声 −128…127。格子大于 1px 时在四角之间双线性插值，颗粒是软的而不是方块。 */
inline int32_t grain_noise(const Kernel& k, uint32_t x, uint32_t y) {
    if (k.grain_cell == 1) return static_cast<int32_t>(hash2(x, y) & 0xFFU) - 128;
    const uint32_t gx = x / k.grain_cell, gy = y / k.grain_cell;
    const int32_t fx = static_cast<int32_t>(((x % k.grain_cell) << 8) / k.grain_cell);
    const int32_t fy = static_cast<int32_t>(((y % k.grain_cell) << 8) / k.grain_cell);
    const int32_t a = static_cast<int32_t>(hash2(gx, gy) & 0xFFU);
    const int32_t b = static_cast<int32_t>(hash2(gx + 1, gy) & 0xFFU);
    const int32_t c = static_cast<int32_t>(hash2(gx, gy + 1) & 0xFFU);
    const int32_t d = static_cast<int32_t>(hash2(gx + 1, gy + 1) & 0xFFU);
    const int32_t top = (a << 8) + (b - a) * fx;
    const int32_t bottom = (c << 8) + (d - c) * fx;
    return (((top << 8) + (bottom - top) * fy) >> 16) - 128;
}

/**
 * 四面体插值：按三个通道分数的大小关系落进 6 个四面体之一，只取 4 个格点。
 *
 * 写成无分支：把分数排成 f1 ≥ f2 ≥ f3，最大分数所在的轴决定第二个顶点、最小分数所在的轴决定第三个顶点
 * （= 对角顶点退回最小轴一步）。传统写法是 6 路 if，噪点 / JPEG 色块会让它每个像素都猜错一次，
 * 一次误预测就是十几个周期；这里只剩 max / min / csel。
 */
inline void lut_lookup(const Kernel& k, int32_t r, int32_t g, int32_t b, int32_t out[3]) {
    const int32_t fr = k.frac[r], fg = k.frac[g], fb = k.frac[b];
    const int16_t* c000 = k.lut.data() + k.off_r[r] + k.off_g[g] + k.off_b[b];
    const int32_t sr = 4, sg = k.step_g, sb = k.step_b;
    const int16_t* c111 = c000 + sr + sg + sb;
    const int32_t f1 = std::max(fr, std::max(fg, fb));
    const int32_t f3 = std::min(fr, std::min(fg, fb));
    const int32_t f2 = fr + fg + fb - f1 - f3;
    // 并列时按 r → g → b 取最大轴、按 b → g → r 取最小轴，两者永远不会是同一根轴。
    const int32_t s_hi = fr == f1 ? sr : (fg == f1 ? sg : sb);
    const int32_t s_lo = fb == f3 ? sb : (fg == f3 ? sg : sr);
    const int16_t* p1 = c000 + s_hi;
    const int16_t* p2 = c111 - s_lo;
    const int32_t w0 = 256 - f1, w1 = f1 - f2, w2 = f2 - f3, w3 = f3;
    constexpr int shift = kLutFracBits + kLutValueBits;
#if defined(__aarch64__)
    // 格点是 8 字节的 {r, g, b, pad}：一条 vld1 取整个顶点，4 条乘加做完三个通道。
    int32x4_t acc = vmull_n_s16(vld1_s16(c000), static_cast<int16_t>(w0));
    acc = vmlal_n_s16(acc, vld1_s16(p1), static_cast<int16_t>(w1));
    acc = vmlal_n_s16(acc, vld1_s16(p2), static_cast<int16_t>(w2));
    acc = vmlal_n_s16(acc, vld1_s16(c111), static_cast<int16_t>(w3));
    acc = vrshrq_n_s32(acc, shift);
    out[0] = vgetq_lane_s32(acc, 0);
    out[1] = vgetq_lane_s32(acc, 1);
    out[2] = vgetq_lane_s32(acc, 2);
#else
    constexpr int32_t round = 1 << (shift - 1);
    for (int i = 0; i < 3; ++i) {
        out[i] = (c000[i] * w0 + p1[i] * w1 + p2[i] * w2 + c111[i] * w3 + round) >> shift;
    }
#endif
}

inline uint8_t clamp_u8(int32_t v) { return static_cast<uint8_t>(std::clamp(v, 0, 255)); }

void process_row(const Kernel& k, const uint8_t* src, uint8_t* dst, uint32_t width, uint32_t y,
                 const float* dx2) {
    // 暗角：这一行的 dy² 只算一次，逐像素只剩加法 + 查表，不开方。
    float dy2 = 0;
    int32_t vlast = 0;
    float vscale = 0;
    if (k.vignette_on) {
        const float dy = static_cast<float>(y) + 0.5f - k.cy;
        dy2 = dy * dy * k.inv_r2;
        vlast = static_cast<int32_t>(k.vlut.size() - 1);
        vscale = static_cast<float>(vlast);
    }
    uint32_t last_key = 0xFFFFFFFFU;  // 不可能的颜色：第一个像素必然查表
    int32_t last_out[3] = {0, 0, 0};
    for (uint32_t x = 0; x < width; ++x) {
        const uint8_t* s = src + x * 4;
        uint8_t* d = dst + x * 4;
        const int32_t a = s[3];
        if (a == 0) {
            // 全透明：预乘后的颜色恒为 0，算什么都一样。
            if (d != s) std::memcpy(d, s, 4);
            continue;
        }
        int32_t r = s[0], g = s[1], b = s[2];
        if (a < 255) {
            r = std::min(255, (r * 255 + a / 2) / a);
            g = std::min(255, (g * 255 + a / 2) / a);
            b = std::min(255, (b * 255 + a / 2) / a);
        }
        if (k.lut_on) {
            // 插画里大片平涂：和上一个像素同色就直接复用查表结果。
            const uint32_t key = static_cast<uint32_t>(r) | static_cast<uint32_t>(g) << 8 |
                                 static_cast<uint32_t>(b) << 16;
            if (key != last_key) {
                lut_lookup(k, r, g, b, last_out);
                last_key = key;
            }
            r = last_out[0]; g = last_out[1]; b = last_out[2];
        }
        if (k.vignette_on) {
            const auto i = static_cast<int32_t>((dx2[x] + dy2) * vscale + 0.5f);
            const int32_t v = k.vlut[static_cast<size_t>(std::min(i, vlast))];
            r = (r * v + 128) >> 8;
            g = (g * v + 128) >> 8;
            b = (b * v + 128) >> 8;
        }
        if (k.grain_on) {
            // 颗粒集中在中间调：黑位和高光保持干净，像真胶片那样。
            const int32_t l = (r * 77 + g * 150 + b * 29) >> 8;
            const int32_t w = (l * (255 - l)) >> 6;  // 0–254，中灰处最大
            const int32_t delta = (grain_noise(k, x, y) * k.grain_amp_q8 / 128 * w) >> 16;
            r += delta; g += delta; b += delta;
        }
        r = clamp_u8(r); g = clamp_u8(g); b = clamp_u8(b);
        if (a < 255) {
            r = (r * a + 127) / 255;
            g = (g * a + 127) / 255;
            b = (b * a + 127) / 255;
        }
        d[0] = static_cast<uint8_t>(r);
        d[1] = static_cast<uint8_t>(g);
        d[2] = static_cast<uint8_t>(b);
        d[3] = static_cast<uint8_t>(a);
    }
}

void run(const Kernel& k, const uint8_t* src, uint32_t src_stride, uint8_t* dst, uint32_t dst_stride,
         uint32_t width, uint32_t height) {
    std::vector<float> dx2;
    if (k.vignette_on) {
        dx2.resize(width);
        for (uint32_t x = 0; x < width; ++x) {
            const float dx = static_cast<float>(x) + 0.5f - k.cx;
            dx2[x] = dx * dx * k.inv_r2;
        }
    }
    const uint32_t bands = (height + kBandRows - 1) / kBandRows;
    std::atomic<uint32_t> next{0};
    auto worker = [&] {
        for (uint32_t band = next.fetch_add(1); band < bands; band = next.fetch_add(1)) {
            const uint32_t end = std::min(height, (band + 1) * kBandRows);
            for (uint32_t y = band * kBandRows; y < end; ++y) {
                process_row(k, src + static_cast<size_t>(y) * src_stride, dst + static_cast<size_t>(y) * dst_stride,
                            width, y, dx2.data());
            }
        }
    };
    // 起线程、唤醒睡着的核心都要毫秒级：活儿少的时候（预览草稿、缩略图）多开线程只会更慢。
    // 按像素数分：每个线程至少分到 kPixelsPerThread，24MP 的导出才会铺满所有核心。
    const uint32_t cores = std::max(1U, std::thread::hardware_concurrency());
    const uint64_t pixels = static_cast<uint64_t>(width) * height;
    const auto by_work = static_cast<uint32_t>(std::max<uint64_t>(1, pixels / kPixelsPerThread));
    const uint32_t threads = std::min({cores, by_work, bands});
    std::vector<std::thread> pool;
    pool.reserve(threads - 1);
    for (uint32_t i = 1; i < threads; ++i) {
        // 进程线程数见顶 / 内存吃紧时 std::thread 会抛 system_error；这里没人接，冒出 JNI 就是 terminate 闪退。
        // 行带是抢着做的，少起几个线程只是慢一点：起不来就停手，剩下的交给已经在跑的线程。
        try {
            pool.emplace_back(worker);
        } catch (const std::system_error&) {
            break;
        }
    }
    worker();  // 调用线程自己也干活
    for (auto& t : pool) t.join();
}

bool load_lut(JNIEnv* env, jfloatArray array, jint size, Kernel& k) {
    if (size < 2 || size > 65) return false;
    const auto n = static_cast<size_t>(size);
    const size_t count = n * n * n;
    if (static_cast<size_t>(env->GetArrayLength(array)) != count * 3) return false;
    std::vector<float> values(count * 3);
    env->GetFloatArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    k.lut.assign(count * 4, 0);
    constexpr float scale = 255.0f * (1 << kLutValueBits);
    for (size_t i = 0; i < count; ++i) {
        for (size_t c = 0; c < 3; ++c) {
            k.lut[i * 4 + c] = static_cast<int16_t>(std::lround(std::clamp(values[i * 3 + c], 0.0f, 1.0f) * scale));
        }
    }
    k.n = size;
    k.step_g = 4 * size;
    k.step_b = 4 * size * size;
    for (int v = 0; v < 256; ++v) {
        const float pos = static_cast<float>(v) * static_cast<float>(size - 1) / 255.0f;
        auto i = static_cast<int>(pos);
        int32_t f = static_cast<int32_t>(std::lround((pos - static_cast<float>(i)) * 256.0f));
        if (i >= size - 1) { i = size - 2; f = 256; }
        k.off_r[v] = 4 * i;
        k.off_g[v] = k.step_g * i;
        k.off_b[v] = k.step_b * i;
        k.frac[v] = static_cast<uint16_t>(f);
    }
    k.lut_on = true;
    return true;
}

bool lock(JNIEnv* env, jobject bitmap, AndroidBitmapInfo& info, void** pixels) {
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
        return false;
    }
    return AndroidBitmap_lockPixels(env, bitmap, pixels) == ANDROID_BITMAP_RESULT_SUCCESS && *pixels != nullptr;
}

}  // namespace

/**
 * src → dst（可以是同一张，原地改写）。两张必须同尺寸、都是 RGBA_8888，dst 必须可变。
 * lut 为 null 表示颜色不变；vignette_lut 为 null 表示无暗角；grain ≤ 0 表示无颗粒。
 */
extern "C" JNIEXPORT jint JNICALL
Java_ceui_pixiv_ui_filter_NativeImageFilter_nativeRender(
        JNIEnv* env, jobject, jobject src_bitmap, jobject dst_bitmap,
        jfloatArray lut, jint lut_size, jfloatArray vignette_lut, jfloat grain) {
    if (src_bitmap == nullptr || dst_bitmap == nullptr) return kBadArgs;

    Kernel k{};
    if (lut != nullptr && !load_lut(env, lut, lut_size, k)) return kBadArgs;
    if (vignette_lut != nullptr) {
        const jsize n = env->GetArrayLength(vignette_lut);
        if (n < 2) return kBadArgs;
        std::vector<float> v(static_cast<size_t>(n));
        env->GetFloatArrayRegion(vignette_lut, 0, n, v.data());
        k.vlut.resize(v.size());
        for (size_t i = 0; i < v.size(); ++i) {
            k.vlut[i] = static_cast<uint16_t>(std::lround(std::clamp(v[i], 0.0f, 1.0f) * 256.0f));
        }
        k.vignette_on = true;
    }
    if (grain > 0) {
        k.grain_on = true;
        k.grain_amp_q8 = static_cast<int32_t>(std::lround(std::min(grain, 1.0f) * kGrainAmplitude * 256.0f));
    }

    const bool same = env->IsSameObject(src_bitmap, dst_bitmap) == JNI_TRUE;
    AndroidBitmapInfo src_info{}, dst_info{};
    void* src_pixels = nullptr;
    void* dst_pixels = nullptr;
    if (!lock(env, src_bitmap, src_info, &src_pixels)) return kLockFailed;
    if (same) {
        dst_info = src_info;
        dst_pixels = src_pixels;
    } else if (!lock(env, dst_bitmap, dst_info, &dst_pixels)) {
        AndroidBitmap_unlockPixels(env, src_bitmap);
        return kLockFailed;
    }
    if (dst_info.width != src_info.width || dst_info.height != src_info.height) {
        if (!same) AndroidBitmap_unlockPixels(env, dst_bitmap);
        AndroidBitmap_unlockPixels(env, src_bitmap);
        return kBadBitmap;
    }

    const uint32_t w = src_info.width, h = src_info.height;
    if (k.vignette_on) {
        // 与 Kotlin 侧同一几何：圆心在图中心，半径是半对角线（四角 d = 1）。
        k.cx = static_cast<float>(w) * 0.5f;
        k.cy = static_cast<float>(h) * 0.5f;
        k.inv_r2 = 1.0f / (k.cx * k.cx + k.cy * k.cy);
    }
    k.grain_cell = std::max(1U, std::min(w, h) / kGrainDensity);

    run(k, static_cast<const uint8_t*>(src_pixels), src_info.stride, static_cast<uint8_t*>(dst_pixels),
        dst_info.stride, w, h);

    if (!same) AndroidBitmap_unlockPixels(env, dst_bitmap);
    AndroidBitmap_unlockPixels(env, src_bitmap);
    return kOk;
}
