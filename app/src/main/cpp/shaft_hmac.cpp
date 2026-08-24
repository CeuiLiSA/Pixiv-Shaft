#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "shaft_secrets.generated.h"

namespace {

template <typename T>
void secure_zero(T* data, std::size_t count) noexcept {
    volatile std::uint8_t* bytes = reinterpret_cast<volatile std::uint8_t*>(data);
    const std::size_t byte_count = sizeof(T) * count;
    for (std::size_t i = 0; i < byte_count; ++i) {
        bytes[i] = 0;
    }
}

template <typename T, std::size_t N>
void secure_zero(std::array<T, N>& data) noexcept {
    secure_zero(data.data(), data.size());
}

constexpr std::array<std::uint32_t, 64> kSha256RoundConstants = {
        0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
        0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
        0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
        0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
        0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
        0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
        0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
        0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
        0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
        0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
        0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
        0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
        0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
        0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
        0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
        0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

constexpr std::uint32_t rotate_right(std::uint32_t value, std::uint32_t bits) noexcept {
    return (value >> bits) | (value << (32U - bits));
}

class Sha256 final {
public:
    Sha256() noexcept
            : state_{0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
                     0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U} {}

    ~Sha256() {
        secure_zero(buffer_);
        secure_zero(state_);
        buffer_size_ = 0;
        processed_bits_ = 0;
    }

    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;

    void update(const std::uint8_t* input, std::size_t length) noexcept {
        for (std::size_t i = 0; i < length; ++i) {
            buffer_[buffer_size_++] = input[i];
            if (buffer_size_ == buffer_.size()) {
                transform();
                processed_bits_ += 512U;
                buffer_size_ = 0;
            }
        }
    }

    std::array<std::uint8_t, 32> finish() noexcept {
        const std::uint64_t total_bits = processed_bits_ +
                static_cast<std::uint64_t>(buffer_size_) * 8U;

        buffer_[buffer_size_++] = 0x80U;
        if (buffer_size_ > 56U) {
            while (buffer_size_ < buffer_.size()) {
                buffer_[buffer_size_++] = 0U;
            }
            transform();
            buffer_.fill(0U);
            buffer_size_ = 0;
        }
        while (buffer_size_ < 56U) {
            buffer_[buffer_size_++] = 0U;
        }
        for (std::size_t i = 0; i < 8U; ++i) {
            buffer_[63U - i] = static_cast<std::uint8_t>(total_bits >> (i * 8U));
        }
        transform();

        std::array<std::uint8_t, 32> digest{};
        for (std::size_t i = 0; i < state_.size(); ++i) {
            digest[i * 4U] = static_cast<std::uint8_t>(state_[i] >> 24U);
            digest[i * 4U + 1U] = static_cast<std::uint8_t>(state_[i] >> 16U);
            digest[i * 4U + 2U] = static_cast<std::uint8_t>(state_[i] >> 8U);
            digest[i * 4U + 3U] = static_cast<std::uint8_t>(state_[i]);
        }
        secure_zero(buffer_);
        secure_zero(state_);
        buffer_size_ = 0;
        processed_bits_ = 0;
        return digest;
    }

private:
    void transform() noexcept {
        std::array<std::uint32_t, 64> schedule{};
        for (std::size_t i = 0; i < 16U; ++i) {
            const std::size_t offset = i * 4U;
            schedule[i] = (static_cast<std::uint32_t>(buffer_[offset]) << 24U) |
                    (static_cast<std::uint32_t>(buffer_[offset + 1U]) << 16U) |
                    (static_cast<std::uint32_t>(buffer_[offset + 2U]) << 8U) |
                    static_cast<std::uint32_t>(buffer_[offset + 3U]);
        }
        for (std::size_t i = 16U; i < schedule.size(); ++i) {
            const std::uint32_t s0 = rotate_right(schedule[i - 15U], 7U) ^
                    rotate_right(schedule[i - 15U], 18U) ^ (schedule[i - 15U] >> 3U);
            const std::uint32_t s1 = rotate_right(schedule[i - 2U], 17U) ^
                    rotate_right(schedule[i - 2U], 19U) ^ (schedule[i - 2U] >> 10U);
            schedule[i] = schedule[i - 16U] + s0 + schedule[i - 7U] + s1;
        }

        std::uint32_t a = state_[0];
        std::uint32_t b = state_[1];
        std::uint32_t c = state_[2];
        std::uint32_t d = state_[3];
        std::uint32_t e = state_[4];
        std::uint32_t f = state_[5];
        std::uint32_t g = state_[6];
        std::uint32_t h = state_[7];

        for (std::size_t i = 0; i < schedule.size(); ++i) {
            const std::uint32_t sum1 = rotate_right(e, 6U) ^ rotate_right(e, 11U) ^
                    rotate_right(e, 25U);
            const std::uint32_t choice = (e & f) ^ (~e & g);
            const std::uint32_t temp1 = h + sum1 + choice + kSha256RoundConstants[i] +
                    schedule[i];
            const std::uint32_t sum0 = rotate_right(a, 2U) ^ rotate_right(a, 13U) ^
                    rotate_right(a, 22U);
            const std::uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const std::uint32_t temp2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }

        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
        secure_zero(schedule);
    }

    std::array<std::uint8_t, 64> buffer_{};
    std::size_t buffer_size_ = 0;
    std::uint64_t processed_bits_ = 0;
    std::array<std::uint32_t, 8> state_{};
};

std::array<std::uint8_t, 32> hmac_sha256(
        const std::uint8_t* key,
        std::size_t key_size,
        const std::uint8_t* message,
        std::size_t message_size) noexcept {
    std::array<std::uint8_t, 64> key_block{};
    if (key_size > key_block.size()) {
        Sha256 key_hash;
        key_hash.update(key, key_size);
        auto digest = key_hash.finish();
        std::memcpy(key_block.data(), digest.data(), digest.size());
        secure_zero(digest);
    } else if (key_size > 0U) {
        std::memcpy(key_block.data(), key, key_size);
    }

    std::array<std::uint8_t, 64> inner_pad{};
    std::array<std::uint8_t, 64> outer_pad{};
    for (std::size_t i = 0; i < key_block.size(); ++i) {
        inner_pad[i] = static_cast<std::uint8_t>(key_block[i] ^ 0x36U);
        outer_pad[i] = static_cast<std::uint8_t>(key_block[i] ^ 0x5cU);
    }

    Sha256 inner_hash;
    inner_hash.update(inner_pad.data(), inner_pad.size());
    inner_hash.update(message, message_size);
    auto inner_digest = inner_hash.finish();

    Sha256 outer_hash;
    outer_hash.update(outer_pad.data(), outer_pad.size());
    outer_hash.update(inner_digest.data(), inner_digest.size());
    auto result = outer_hash.finish();

    secure_zero(key_block);
    secure_zero(inner_pad);
    secure_zero(outer_pad);
    secure_zero(inner_digest);
    return result;
}

std::vector<std::uint8_t> to_utf8(JNIEnv* env, jstring value) {
    const jsize length = env->GetStringLength(value);
    const jchar* utf16 = env->GetStringChars(value, nullptr);
    if (utf16 == nullptr) {
        return {};
    }

    std::vector<std::uint8_t> utf8;
    utf8.reserve(static_cast<std::size_t>(length) * 3U);
    for (jsize i = 0; i < length; ++i) {
        std::uint32_t code_point = utf16[i];
        if (code_point >= 0xd800U && code_point <= 0xdbffU) {
            if (i + 1 < length && utf16[i + 1] >= 0xdc00U && utf16[i + 1] <= 0xdfffU) {
                code_point = 0x10000U + ((code_point - 0xd800U) << 10U) +
                        (utf16[++i] - 0xdc00U);
            } else {
                code_point = static_cast<std::uint32_t>('?');
            }
        } else if (code_point >= 0xdc00U && code_point <= 0xdfffU) {
            code_point = static_cast<std::uint32_t>('?');
        }

        if (code_point <= 0x7fU) {
            utf8.push_back(static_cast<std::uint8_t>(code_point));
        } else if (code_point <= 0x7ffU) {
            utf8.push_back(static_cast<std::uint8_t>(0xc0U | (code_point >> 6U)));
            utf8.push_back(static_cast<std::uint8_t>(0x80U | (code_point & 0x3fU)));
        } else if (code_point <= 0xffffU) {
            utf8.push_back(static_cast<std::uint8_t>(0xe0U | (code_point >> 12U)));
            utf8.push_back(static_cast<std::uint8_t>(0x80U | ((code_point >> 6U) & 0x3fU)));
            utf8.push_back(static_cast<std::uint8_t>(0x80U | (code_point & 0x3fU)));
        } else {
            utf8.push_back(static_cast<std::uint8_t>(0xf0U | (code_point >> 18U)));
            utf8.push_back(static_cast<std::uint8_t>(0x80U | ((code_point >> 12U) & 0x3fU)));
            utf8.push_back(static_cast<std::uint8_t>(0x80U | ((code_point >> 6U) & 0x3fU)));
            utf8.push_back(static_cast<std::uint8_t>(0x80U | (code_point & 0x3fU)));
        }
    }
    env->ReleaseStringChars(value, utf16);
    return utf8;
}

std::string to_hex(const std::array<std::uint8_t, 32>& bytes) {
    constexpr char kHex[] = "0123456789abcdef";
    std::string output(bytes.size() * 2U, '0');
    for (std::size_t i = 0; i < bytes.size(); ++i) {
        output[i * 2U] = kHex[bytes[i] >> 4U];
        output[i * 2U + 1U] = kHex[bytes[i] & 0x0fU];
    }
    return output;
}

bool self_test() noexcept {
    // RFC 4231 test case 1: HMAC-SHA256(key=0x0b * 20, data="Hi There").
    std::array<std::uint8_t, 20> key{};
    key.fill(0x0bU);
    constexpr std::array<std::uint8_t, 8> data = {'H', 'i', ' ', 'T', 'h', 'e', 'r', 'e'};
    constexpr std::array<std::uint8_t, 32> expected = {
            0xb0U, 0x34U, 0x4cU, 0x61U, 0xd8U, 0xdbU, 0x38U, 0x53U,
            0x5cU, 0xa8U, 0xafU, 0xceU, 0xafU, 0x0bU, 0xf1U, 0x2bU,
            0x88U, 0x1dU, 0xc2U, 0x00U, 0xc9U, 0x83U, 0x3dU, 0xa7U,
            0x26U, 0xe9U, 0x37U, 0x6cU, 0x2eU, 0x32U, 0xcfU, 0xf7U,
    };
    auto actual = hmac_sha256(key.data(), key.size(), data.data(), data.size());
    std::uint8_t difference = 0U;
    for (std::size_t i = 0; i < actual.size(); ++i) {
        difference |= static_cast<std::uint8_t>(actual[i] ^ expected[i]);
    }
    secure_zero(key);
    secure_zero(actual);
    return difference == 0U;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ceui_pixiv_shaftapi_ShaftHmac_nativeIsConfigured(JNIEnv*, jobject) {
    return shaft::generated::kSecretSize > 0U ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ceui_pixiv_shaftapi_ShaftHmac_nativeSelfTest(JNIEnv*, jobject) {
    return self_test() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ceui_pixiv_shaftapi_ShaftHmac_nativeSignUtf8(JNIEnv* env, jobject, jstring payload) {
    if (payload == nullptr || shaft::generated::kSecretSize == 0U) {
        return env->NewStringUTF("");
    }

    try {
        auto message = to_utf8(env, payload);
        if (env->ExceptionCheck() == JNI_TRUE) {
            secure_zero(message.data(), message.size());
            return nullptr;
        }

        std::array<std::uint8_t, shaft::generated::kStorageSize> secret{};
        for (std::size_t i = 0; i < shaft::generated::kSecretSize; ++i) {
            secret[i] = static_cast<std::uint8_t>(shaft::generated::kEncoded[i] ^
                    shaft::generated::kMaskA[i] ^ shaft::generated::kMaskB[i]);
        }
        auto digest = hmac_sha256(secret.data(), shaft::generated::kSecretSize,
                                  message.data(), message.size());
        secure_zero(secret);
        secure_zero(message.data(), message.size());

        std::string hex = to_hex(digest);
        secure_zero(digest);
        jstring result = env->NewStringUTF(hex.c_str());
        secure_zero(hex.data(), hex.size());
        return result;
    } catch (...) {
        // Allocation/JNI failures must degrade to the same unsigned behavior as fork builds,
        // never terminate the Android process across a native exception boundary.
        return env->NewStringUTF("");
    }
}
