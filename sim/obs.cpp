// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file "LICENSE"
// for information on usage and redistribution of this file.

#include "obs.h"

#include <algorithm>
#include <cerrno>
#include <cinttypes>
#include <cstring>

#include "Vsim.h"
#include "sim_config.h"

namespace
{

// The version of the trace file and of the observation schema it follows
// (web/schema/obs-v1.json); docs/observation.md says when each changes.
constexpr int kTraceVersion = 1;
constexpr const char *kSchemaVersion = "1.1";
constexpr char kTrailerMagic[8] = {'O', 'B', 'S', 'E', 'N', 'D', '0', '1'};

struct Field {
    const char *name;
    unsigned bits;
    uint32_t (*get)(const Vsim &);
};

// Every field of a record, from what mycpu.common.TopGen wrote for this top.
#define OBS_FIELD(port, name, bits) \
    {name, bits, [](const Vsim &top) -> uint32_t { return top.io_##port; }},
const Field kFields[] = {SIM_OBS_FIELDS(OBS_FIELD)};
#undef OBS_FIELD

unsigned bytes_of(unsigned bits)
{
    return bits <= 8 ? 1 : bits <= 16 ? 2 : 4;
}

// `text` as a JSON string, quotes included.
std::string json_string(const std::string &text)
{
    std::string out = "\"";
    for (const unsigned char c : text) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            char escape[8];
            std::snprintf(escape, sizeof(escape), "\\u%04x", c);
            out += escape;
        } else {
            out += static_cast<char>(c);
        }
    }
    return out + "\"";
}

// The program's name: the ELF's file name without its directory and ".elf".
std::string program_of(const std::string &elf)
{
    const size_t slash = elf.rfind('/');
    std::string name = slash == std::string::npos ? elf : elf.substr(slash + 1);
    const size_t n = name.size();
    if (n > 4 && name.compare(n - 4, 4, ".elf") == 0)
        name.resize(n - 4);
    return name;
}

// SHA-256 (FIPS 180-4).
class Sha256
{
public:
    void update(const uint8_t *data, size_t size)
    {
        length_ += size;
        while (size > 0) {
            const size_t n = std::min(size, sizeof(block_) - used_);
            std::memcpy(block_ + used_, data, n);
            used_ += n;
            data += n;
            size -= n;
            if (used_ == sizeof(block_)) {
                compress();
                used_ = 0;
            }
        }
    }

    std::string hex()
    {
        const uint64_t bits = length_ * 8;
        const uint8_t one = 0x80, zero = 0;
        update(&one, 1);
        while (used_ != 56)
            update(&zero, 1);
        uint8_t size[8];
        for (int i = 0; i < 8; i++)
            size[i] = static_cast<uint8_t>(bits >> (56 - 8 * i));
        update(size, 8);
        char out[65];
        for (int i = 0; i < 8; i++)
            std::snprintf(out + 8 * i, 9, "%08" PRIx32, state_[i]);
        return std::string(out, 64);
    }

private:
    static uint32_t rotr(uint32_t x, int n)
    {
        return (x >> n) | (x << (32 - n));
    }

    void compress()
    {
        static const uint32_t k[64] = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b,
            0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
            0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7,
            0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152,
            0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
            0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
            0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819,
            0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
            0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f,
            0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
            0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
        uint32_t w[64];
        for (int i = 0; i < 16; i++)
            w[i] = uint32_t{block_[4 * i]} << 24 |
                   uint32_t{block_[4 * i + 1]} << 16 |
                   uint32_t{block_[4 * i + 2]} << 8 | block_[4 * i + 3];
        for (int i = 16; i < 64; i++) {
            const uint32_t s0 =
                rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 =
                rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t v[8];
        std::memcpy(v, state_, sizeof(v));
        for (int i = 0; i < 64; i++) {
            const uint32_t s1 = rotr(v[4], 6) ^ rotr(v[4], 11) ^ rotr(v[4], 25);
            const uint32_t ch = (v[4] & v[5]) ^ (~v[4] & v[6]);
            const uint32_t t1 = v[7] + s1 + ch + k[i] + w[i];
            const uint32_t s0 = rotr(v[0], 2) ^ rotr(v[0], 13) ^ rotr(v[0], 22);
            const uint32_t maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            std::memmove(v + 1, v, 7 * sizeof(uint32_t));
            v[4] += t1;
            v[0] = t1 + s0 + maj;
        }
        for (int i = 0; i < 8; i++)
            state_[i] += v[i];
    }

    uint32_t state_[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                          0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    uint8_t block_[64] = {};
    size_t used_ = 0;
    uint64_t length_ = 0;
};

}  // namespace

bool sha256_file(const std::string &path, std::string *digest)
{
    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file)
        return false;
    Sha256 sha;
    uint8_t chunk[1 << 16];
    size_t n;
    while ((n = std::fread(chunk, 1, sizeof(chunk), file)) > 0)
        sha.update(chunk, n);
    const bool ok = std::ferror(file) == 0;
    std::fclose(file);
    if (ok)
        *digest = sha.hex();
    return ok;
}

ObsTrace::~ObsTrace()
{
    if (file_)
        std::fclose(file_);
}

bool ObsTrace::open(const std::string &path,
                    const ObsRun &run,
                    std::string *error)
{
    std::string elf_sha256;
    if (!sha256_file(run.elf, &elf_sha256)) {
        *error = "cannot read " + run.elf;
        return false;
    }
    file_ = std::fopen(path.c_str(), "wb");
    if (!file_) {
        *error = "cannot write " + path + ": " + std::strerror(errno);
        return false;
    }
    // a record of about 100 bytes per cycle: write them in large blocks
    std::setvbuf(file_, nullptr, _IOFBF, 1 << 20);
    path_ = path;
    first_ = run.first;
    count_ = run.count == 0 ? UINT64_MAX : run.count;

    std::string fields;
    unsigned offset = 0;
    for (const Field &f : kFields) {
        char entry[160];
        std::snprintf(entry, sizeof(entry),
                      "%s{\"name\":\"%s\",\"bits\":%u,\"offset\":%u,"
                      "\"bytes\":%u}",
                      fields.empty() ? "" : ",", f.name, f.bits, offset,
                      bytes_of(f.bits));
        fields += entry;
        offset += bytes_of(f.bits);
    }
    buffer_.assign(offset, 0);
    std::string groups;
    for (const char *g = SIM_OBS_GROUPS; *g;) {
        const char *comma = std::strchr(g, ',');
        const std::string name = comma ? std::string(g, comma) : std::string(g);
        groups += (groups.empty() ? "" : ",") + json_string(name);
        g = comma ? comma + 1 : g + name.size();
    }
    char numbers[256];
    std::snprintf(numbers, sizeof(numbers),
                  "\"uart_divider\":%u,\"ram_base\":%u,\"ram_bytes\":%u,"
                  "\"first\":%" PRIu64 ",\"limit\":%" PRIu64
                  ",\"record_bytes\":%u",
                  SIM_UART_DIVIDER, SIM_RAM_BASE, SIM_RAM_BYTES, run.first,
                  run.count, offset);
    const std::string header =
        "{\"format\":\"mycpu-obs-trace\",\"version\":" +
        std::to_string(kTraceVersion) +
        ",\"schema\":" + json_string(kSchemaVersion) +
        ",\"task\":" + json_string(run.task) +
        ",\"variant\":" + json_string(run.variant) +
        ",\"commit\":" + json_string(SIM_GIT_COMMIT) +
        ",\"top\":" + json_string(SIM_TOP_CLASS) +
        ",\"program\":" + json_string(program_of(run.elf)) +
        ",\"elf_sha256\":" + json_string(elf_sha256) + "," + numbers +
        ",\"groups\":[" + groups + "],\"fields\":[" + fields + "]}\n";
    std::fwrite(header.data(), 1, header.size(), file_);
    return true;
}

void ObsTrace::record(const Vsim &top)
{
    uint8_t *p = buffer_.data();
    for (const Field &f : kFields) {
        const uint32_t v = f.get(top);
        const unsigned n = bytes_of(f.bits);
        for (unsigned i = 0; i < n; i++)
            *p++ = static_cast<uint8_t>(v >> (8 * i));
    }
    std::fwrite(buffer_.data(), 1, buffer_.size(), file_);
    records_++;
}

void ObsTrace::end(uint64_t cycles, uint64_t instret, const std::string &how)
{
    end_ = "{\"cycles\":" + std::to_string(cycles) +
           ",\"instret\":" + std::to_string(instret) +
           ",\"how\":" + json_string(how) + "}";
}

void ObsTrace::frame(uint32_t index, uint64_t cycle)
{
    frames_ += (frames_.empty() ? "" : ",") +
               json_string(std::to_string(index)) + ":" + std::to_string(cycle);
}

bool ObsTrace::close(const std::vector<ObsOutput> &outputs, std::string *error)
{
    if (!file_)
        return true;

    // Without the hash of every output there is no trailer: a reader then
    // refuses the trace as cut off, rather than trust a partial one.
    std::string hashes;
    for (const ObsOutput &output : outputs) {
        std::string digest;
        if (!sha256_file(output.path, &digest)) {
            *error = "cannot read " + output.path + " to its end";
            std::fclose(file_);
            file_ = nullptr;
            return false;
        }
        hashes += (hashes.empty() ? "" : ",") + json_string(output.role) + ":" +
                  json_string(digest);
    }
    bool ok = true;
    const std::string trailer = "{\"records\":" + std::to_string(records_) +
                                ",\"end\":" + end_ + ",\"outputs\":{" + hashes +
                                "},\"frames\":{" + frames_ + "}}";
    uint8_t length[8];
    for (int i = 0; i < 8; i++)
        length[i] = static_cast<uint8_t>(uint64_t{trailer.size()} >> (8 * i));
    std::fwrite(trailer.data(), 1, trailer.size(), file_);
    std::fwrite(length, 1, sizeof(length), file_);
    std::fwrite(kTrailerMagic, 1, sizeof(kTrailerMagic), file_);
    const bool failed = std::ferror(file_) != 0;
    if (std::fclose(file_) != 0 || failed) {
        *error = "cannot write " + path_ + " to its end";
        ok = false;
    }
    file_ = nullptr;
    return ok;
}
