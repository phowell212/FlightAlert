#define NOMINMAX
#include <windows.h>
#include <bcrypt.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <cwctype>
#include <deque>
#include <filesystem>
#include <fstream>
#include <functional>
#include <future>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <optional>
#include <queue>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

#ifndef FA_SOURCE_SHA256
#define FA_SOURCE_SHA256 "unknown"
#endif

namespace fs = std::filesystem;

namespace {

constexpr std::uint64_t MAX_SIGNED_ID = (std::uint64_t{1} << 63) - 1;
constexpr std::size_t MAX_BLOB_HEADER_BYTES = 65'536;
constexpr std::size_t MAX_BLOB_BYTES = 64 * 1024 * 1024;
constexpr std::size_t MAX_UNCOMPRESSED_BLOB_BYTES = 32 * 1024 * 1024;
constexpr std::size_t SORT_CHUNK_BYTES = 32 * 1024 * 1024;
constexpr std::size_t MAX_OPL_LINE_BYTES = 64 * 1024 * 1024;
constexpr std::size_t MERGE_FAN_IN = 8;
constexpr unsigned MAX_WORKERS = 10;
constexpr std::uint64_t DEFAULT_MAX_WORK_BYTES = 1'099'511'627'776ULL;
constexpr std::uint64_t DEFAULT_MAX_OUTPUT_BYTES = 68'719'476'736ULL;
constexpr char TOOL_NAME[] = "flightalert-native-osm-boundary-extractor";
constexpr char TOOL_VERSION[] = "1";
constexpr char POLICY_VERSION[] = "flightalert.osm-boundary-closure.v1";

class ExtractError final : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

[[noreturn]] void fail(const std::string& message) {
    throw ExtractError(message);
}

std::int64_t checked_add(std::int64_t left, std::int64_t right, std::string_view label) {
    if ((right > 0 && left > std::numeric_limits<std::int64_t>::max() - right) ||
        (right < 0 && left < std::numeric_limits<std::int64_t>::min() - right)) {
        fail(std::string(label) + " overflows signed-64");
    }
    return left + right;
}

std::int64_t checked_multiply(
    std::int64_t left,
    std::int64_t right,
    std::string_view label
) {
    if (left == 0 || right == 0) return 0;
    if ((left == -1 && right == std::numeric_limits<std::int64_t>::min()) ||
        (right == -1 && left == std::numeric_limits<std::int64_t>::min())) {
        fail(std::string(label) + " overflows signed-64");
    }
    if (left > 0) {
        if ((right > 0 && left > std::numeric_limits<std::int64_t>::max() / right) ||
            (right < 0 && right < std::numeric_limits<std::int64_t>::min() / left)) {
            fail(std::string(label) + " overflows signed-64");
        }
    } else {
        if ((right > 0 && left < std::numeric_limits<std::int64_t>::min() / right) ||
            (right < 0 && left < std::numeric_limits<std::int64_t>::max() / right)) {
            fail(std::string(label) + " overflows signed-64");
        }
    }
    return left * right;
}

std::string path_utf8(const fs::path& path) {
    const auto value = path.generic_u8string();
    return std::string(reinterpret_cast<const char*>(value.data()), value.size());
}

std::vector<std::uint32_t> utf8_scalars(std::string_view value, std::string_view label) {
    std::vector<std::uint32_t> result;
    result.reserve(value.size());
    for (std::size_t index = 0; index < value.size();) {
        const auto first = static_cast<unsigned char>(value[index]);
        std::uint32_t scalar = 0;
        std::size_t count = 0;
        if (first <= 0x7f) {
            scalar = first;
            count = 1;
        } else if (first >= 0xc2 && first <= 0xdf) {
            scalar = first & 0x1f;
            count = 2;
        } else if (first >= 0xe0 && first <= 0xef) {
            scalar = first & 0x0f;
            count = 3;
        } else if (first >= 0xf0 && first <= 0xf4) {
            scalar = first & 0x07;
            count = 4;
        } else {
            fail(std::string(label) + " is not strict UTF-8");
        }
        if (index + count > value.size()) fail(std::string(label) + " is not strict UTF-8");
        for (std::size_t offset = 1; offset < count; ++offset) {
            const auto next = static_cast<unsigned char>(value[index + offset]);
            if ((next & 0xc0) != 0x80) fail(std::string(label) + " is not strict UTF-8");
            scalar = (scalar << 6) | (next & 0x3f);
        }
        if ((count == 3 && ((first == 0xe0 && scalar < 0x800) ||
                            (first == 0xed && scalar >= 0xd800))) ||
            (count == 4 && ((first == 0xf0 && scalar < 0x10000) ||
                            (first == 0xf4 && scalar > 0x10ffff))) ||
            scalar > 0x10ffff || (scalar >= 0xd800 && scalar <= 0xdfff)) {
            fail(std::string(label) + " is not strict UTF-8");
        }
        result.push_back(scalar);
        index += count;
    }
    return result;
}

void append_utf8(std::string& output, std::uint32_t scalar) {
    if (scalar <= 0x7f) {
        output.push_back(static_cast<char>(scalar));
    } else if (scalar <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (scalar >> 6)));
        output.push_back(static_cast<char>(0x80 | (scalar & 0x3f)));
    } else if (scalar <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (scalar >> 12)));
        output.push_back(static_cast<char>(0x80 | ((scalar >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (scalar & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (scalar >> 18)));
        output.push_back(static_cast<char>(0x80 | ((scalar >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((scalar >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (scalar & 0x3f)));
    }
}

std::string opl_escape(std::string_view value, std::string_view label) {
    std::string output;
    for (const auto scalar : utf8_scalars(value, label)) {
        const bool escape = scalar == '%' || scalar == ',' || scalar == '=' ||
            scalar == ' ' || scalar == '@' || scalar < 0x20 || scalar == 0x7f;
        if (!escape) {
            append_utf8(output, scalar);
            continue;
        }
        std::ostringstream encoded;
        encoded << '%' << std::hex << std::nouppercase << scalar << '%';
        output += encoded.str();
    }
    return output;
}

std::string json_quote(std::string_view value) {
    utf8_scalars(value, "JSON text");
    std::string output = "\"";
    static constexpr char HEX[] = "0123456789abcdef";
    for (const auto character : value) {
        const auto byte = static_cast<unsigned char>(character);
        if (byte == '"' || byte == '\\') {
            output.push_back('\\');
            output.push_back(static_cast<char>(byte));
        } else if (byte < 0x20) {
            output += "\\u00";
            output.push_back(HEX[byte >> 4]);
            output.push_back(HEX[byte & 0x0f]);
        } else {
            output.push_back(static_cast<char>(byte));
        }
    }
    output.push_back('"');
    return output;
}

class Sha256 final {
public:
    Sha256() {
        check(BCryptOpenAlgorithmProvider(&algorithm_, BCRYPT_SHA256_ALGORITHM, nullptr, 0),
              "open SHA-256 provider");
        DWORD bytes = 0;
        DWORD received = 0;
        check(BCryptGetProperty(algorithm_, BCRYPT_OBJECT_LENGTH,
              reinterpret_cast<PUCHAR>(&bytes), sizeof(bytes), &received, 0),
              "query SHA-256 object length");
        object_.resize(bytes);
        check(BCryptCreateHash(algorithm_, &hash_, object_.data(), bytes, nullptr, 0, 0),
              "create SHA-256 hash");
    }

    Sha256(const Sha256&) = delete;
    Sha256& operator=(const Sha256&) = delete;

    ~Sha256() {
        if (hash_) BCryptDestroyHash(hash_);
        if (algorithm_) BCryptCloseAlgorithmProvider(algorithm_, 0);
    }

    void update(const void* data, std::size_t size) {
        const auto* current = static_cast<const unsigned char*>(data);
        while (size) {
            const auto part = static_cast<ULONG>(std::min<std::size_t>(size, 1U << 30));
            check(BCryptHashData(hash_, const_cast<PUCHAR>(current), part, 0),
                  "update SHA-256");
            bytes_ += part;
            current += part;
            size -= part;
        }
    }

    std::pair<std::uint64_t, std::string> finish() {
        if (finished_) fail("SHA-256 hash was finalized twice");
        std::array<unsigned char, 32> digest{};
        check(BCryptFinishHash(hash_, digest.data(), static_cast<ULONG>(digest.size()), 0),
              "finish SHA-256");
        finished_ = true;
        std::ostringstream text;
        text << std::hex << std::setfill('0');
        for (const auto value : digest) text << std::setw(2) << static_cast<unsigned>(value);
        return {bytes_, text.str()};
    }

private:
    static void check(NTSTATUS status, const char* action) {
        if (status < 0) fail(std::string("Windows CNG failed to ") + action);
    }

    BCRYPT_ALG_HANDLE algorithm_ = nullptr;
    BCRYPT_HASH_HANDLE hash_ = nullptr;
    std::vector<unsigned char> object_;
    std::uint64_t bytes_ = 0;
    bool finished_ = false;
};

std::pair<std::uint64_t, std::string> hash_bytes(std::string_view bytes) {
    Sha256 hash;
    hash.update(bytes.data(), bytes.size());
    return hash.finish();
}

std::pair<std::uint64_t, std::string> hash_file(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) fail("cannot open file for SHA-256: " + path_utf8(path));
    Sha256 hash;
    std::vector<char> buffer(1 << 20);
    while (input) {
        input.read(buffer.data(), buffer.size());
        const auto count = input.gcount();
        if (count > 0) hash.update(buffer.data(), static_cast<std::size_t>(count));
    }
    if (!input.eof()) fail("failed while hashing file: " + path_utf8(path));
    return hash.finish();
}

class ProtoReader final {
public:
    explicit ProtoReader(std::string_view bytes) : bytes_(bytes) {}

    bool empty() const noexcept { return position_ == bytes_.size(); }

    std::pair<std::uint32_t, unsigned> key() {
        const auto encoded = varint();
        const auto field = static_cast<std::uint32_t>(encoded >> 3);
        const auto wire = static_cast<unsigned>(encoded & 7);
        if (field == 0 || field > 0x1fffffff || (wire != 0 && wire != 1 && wire != 2 && wire != 5)) {
            fail("malformed protobuf field key");
        }
        return {field, wire};
    }

    std::uint64_t varint() {
        std::uint64_t result = 0;
        for (unsigned index = 0; index < 10; ++index) {
            if (position_ == bytes_.size()) fail("malformed protobuf truncated varint");
            const auto byte = static_cast<unsigned char>(bytes_[position_++]);
            if (index == 9 && byte > 1) fail("malformed protobuf overflowing varint");
            result |= static_cast<std::uint64_t>(byte & 0x7f) << (index * 7);
            if ((byte & 0x80) == 0) return result;
        }
        fail("malformed protobuf unterminated varint");
    }

    std::string_view bytes() {
        const auto length = varint();
        if (length > bytes_.size() - position_) fail("malformed protobuf truncated bytes field");
        const auto result = bytes_.substr(position_, static_cast<std::size_t>(length));
        position_ += static_cast<std::size_t>(length);
        return result;
    }

    void skip(unsigned wire) {
        if (wire == 0) {
            (void)varint();
        } else if (wire == 1) {
            advance(8);
        } else if (wire == 2) {
            (void)bytes();
        } else if (wire == 5) {
            advance(4);
        } else {
            fail("malformed protobuf unsupported wire type");
        }
    }

private:
    void advance(std::size_t count) {
        if (count > bytes_.size() - position_) fail("malformed protobuf truncated fixed field");
        position_ += count;
    }

    std::string_view bytes_;
    std::size_t position_ = 0;
};

std::int64_t zigzag(std::uint64_t value) {
    const auto bits = (value >> 1) ^ (std::uint64_t{0} - (value & 1));
    return std::bit_cast<std::int64_t>(bits);
}

std::int64_t signed_varint(std::uint64_t value) {
    return std::bit_cast<std::int64_t>(value);
}

void require_wire(unsigned actual, unsigned expected, std::string_view label) {
    if (actual != expected) fail("malformed protobuf wire type for " + std::string(label));
}

void append_packed(
    ProtoReader& reader,
    unsigned wire,
    std::vector<std::uint64_t>& output,
    std::string_view label
) {
    if (wire == 0) {
        output.push_back(reader.varint());
        return;
    }
    require_wire(wire, 2, label);
    ProtoReader packed(reader.bytes());
    while (!packed.empty()) output.push_back(packed.varint());
}

struct Metadata {
    std::int32_t version = 0;
    std::string timestamp;
    bool visible = true;
};

using Tags = std::vector<std::pair<std::string, std::string>>;

struct Node {
    std::uint64_t id = 0;
    Metadata metadata;
    Tags tags;
    std::int64_t longitude_e7 = 0;
    std::int64_t latitude_e7 = 0;
};

struct Way {
    std::uint64_t id = 0;
    Metadata metadata;
    Tags tags;
    std::vector<std::uint64_t> refs;
};

struct Member {
    char type = 0;
    std::uint64_t ref = 0;
    std::string role;
};

struct Relation {
    std::uint64_t id = 0;
    Metadata metadata;
    Tags tags;
    std::vector<Member> members;
};

struct BlockContext {
    std::vector<std::string> strings;
    std::int64_t granularity = 100;
    std::int64_t date_granularity = 1000;
    std::int64_t latitude_offset = 0;
    std::int64_t longitude_offset = 0;
    bool dense_feature = false;
};

std::string format_timestamp(std::int64_t units, const BlockContext& context) {
    const auto milliseconds = checked_multiply(units, context.date_granularity, "timestamp");
    if (milliseconds % 1000 != 0) fail("PBF timestamp is not an exact whole second");
    const auto seconds = milliseconds / 1000;
    if (seconds < 0 || seconds > 253'402'300'799LL) fail("PBF timestamp is outside canonical UTC range");
    const __time64_t source = static_cast<__time64_t>(seconds);
    std::tm utc{};
    if (_gmtime64_s(&utc, &source) != 0 || utc.tm_year + 1900 > 9999) {
        fail("PBF timestamp cannot be represented as canonical UTC");
    }
    std::ostringstream output;
    output << std::put_time(&utc, "%Y-%m-%dT%H:%M:%SZ");
    return output.str();
}

Metadata parse_info(std::string_view bytes, const BlockContext& context) {
    ProtoReader reader(bytes);
    bool have_version = false;
    bool have_timestamp = false;
    Metadata result;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 0, "Info.version");
            if (have_version) fail("malformed protobuf duplicate Info.version");
            const auto value = reader.varint();
            if (value == 0 || value > static_cast<std::uint64_t>(std::numeric_limits<std::int32_t>::max())) {
                fail("PBF object version is not a positive int32");
            }
            result.version = static_cast<std::int32_t>(value);
            have_version = true;
        } else if (field == 2) {
            require_wire(wire, 0, "Info.timestamp");
            if (have_timestamp) fail("malformed protobuf duplicate Info.timestamp");
            const auto raw = signed_varint(reader.varint());
            result.timestamp = format_timestamp(raw, context);
            have_timestamp = true;
        } else if (field == 3 || field == 4) {
            require_wire(wire, 0, "Info integer metadata");
            (void)signed_varint(reader.varint());
        } else if (field == 5) {
            require_wire(wire, 0, "Info.user_sid");
            const auto sid = reader.varint();
            if (sid >= context.strings.size()) fail("PBF Info user_sid is outside the string table");
        } else if (field == 6) {
            require_wire(wire, 0, "Info.visible");
            const auto value = reader.varint();
            if (value > 1) fail("PBF Info.visible is not boolean");
            result.visible = value != 0;
        } else {
            reader.skip(wire);
        }
    }
    if (!have_version || !have_timestamp) fail("PBF object is missing version or timestamp metadata");
    return result;
}

Tags resolve_tags(
    const std::vector<std::uint64_t>& keys,
    const std::vector<std::uint64_t>& values,
    const BlockContext& context
) {
    if (keys.size() != values.size()) fail("PBF tag key/value arrays have different lengths");
    Tags result;
    result.reserve(keys.size());
    std::set<std::string> seen;
    for (std::size_t index = 0; index < keys.size(); ++index) {
        if (keys[index] >= context.strings.size() || values[index] >= context.strings.size()) {
            fail("PBF tag string-table index is out of range");
        }
        const auto& key = context.strings[static_cast<std::size_t>(keys[index])];
        const auto& value = context.strings[static_cast<std::size_t>(values[index])];
        if (key.empty()) fail("PBF tag key is empty");
        if (!seen.insert(key).second) fail("PBF object has duplicate tag key");
        result.emplace_back(key, value);
    }
    return result;
}

std::uint64_t positive_id(std::int64_t value, std::string_view label) {
    if (value <= 0 || static_cast<std::uint64_t>(value) > MAX_SIGNED_ID) {
        fail(std::string(label) + " is not a positive signed-63 ID");
    }
    return static_cast<std::uint64_t>(value);
}

std::int64_t coordinate_e7(
    std::int64_t raw,
    std::int64_t offset,
    const BlockContext& context,
    bool latitude
) {
    const auto scaled = checked_multiply(raw, context.granularity, "coordinate");
    const auto nanodegrees = checked_add(offset, scaled, "coordinate");
    if (nanodegrees % 100 != 0) fail("PBF coordinate cannot be represented exactly at E7");
    const auto result = nanodegrees / 100;
    const auto maximum = latitude ? 900'000'000LL : 1'800'000'000LL;
    if (result < -maximum || result > maximum) fail("PBF coordinate is outside its domain");
    return result == 0 ? 0 : result;
}

Node parse_node(std::string_view bytes, const BlockContext& context) {
    ProtoReader reader(bytes);
    bool have_id = false;
    bool have_info = false;
    bool have_latitude = false;
    bool have_longitude = false;
    std::int64_t id = 0;
    std::int64_t latitude = 0;
    std::int64_t longitude = 0;
    Metadata metadata;
    std::vector<std::uint64_t> keys;
    std::vector<std::uint64_t> values;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 0, "Node.id");
            if (have_id) fail("malformed protobuf duplicate Node.id");
            id = zigzag(reader.varint());
            have_id = true;
        } else if (field == 2) {
            append_packed(reader, wire, keys, "Node.keys");
        } else if (field == 3) {
            append_packed(reader, wire, values, "Node.vals");
        } else if (field == 4) {
            require_wire(wire, 2, "Node.info");
            if (have_info) fail("malformed protobuf duplicate Node.info");
            metadata = parse_info(reader.bytes(), context);
            have_info = true;
        } else if (field == 8) {
            require_wire(wire, 0, "Node.lat");
            if (have_latitude) fail("malformed protobuf duplicate Node.lat");
            latitude = zigzag(reader.varint());
            have_latitude = true;
        } else if (field == 9) {
            require_wire(wire, 0, "Node.lon");
            if (have_longitude) fail("malformed protobuf duplicate Node.lon");
            longitude = zigzag(reader.varint());
            have_longitude = true;
        } else {
            reader.skip(wire);
        }
    }
    if (!have_id || !have_info || !have_latitude || !have_longitude) {
        fail("malformed protobuf Node is missing a required field");
    }
    Node result;
    result.id = positive_id(id, "node ID");
    result.metadata = std::move(metadata);
    result.tags = resolve_tags(keys, values, context);
    result.longitude_e7 = coordinate_e7(longitude, context.longitude_offset, context, false);
    result.latitude_e7 = coordinate_e7(latitude, context.latitude_offset, context, true);
    return result;
}

Way parse_way(std::string_view bytes, const BlockContext& context) {
    ProtoReader reader(bytes);
    bool have_id = false;
    bool have_info = false;
    std::int64_t id = 0;
    Metadata metadata;
    std::vector<std::uint64_t> keys;
    std::vector<std::uint64_t> values;
    std::vector<std::uint64_t> encoded_refs;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 0, "Way.id");
            if (have_id) fail("malformed protobuf duplicate Way.id");
            id = signed_varint(reader.varint());
            have_id = true;
        } else if (field == 2) {
            append_packed(reader, wire, keys, "Way.keys");
        } else if (field == 3) {
            append_packed(reader, wire, values, "Way.vals");
        } else if (field == 4) {
            require_wire(wire, 2, "Way.info");
            if (have_info) fail("malformed protobuf duplicate Way.info");
            metadata = parse_info(reader.bytes(), context);
            have_info = true;
        } else if (field == 8) {
            append_packed(reader, wire, encoded_refs, "Way.refs");
        } else {
            reader.skip(wire);
        }
    }
    if (!have_id || !have_info) fail("malformed protobuf Way is missing ID or metadata");
    Way result;
    result.id = positive_id(id, "way ID");
    result.metadata = std::move(metadata);
    result.tags = resolve_tags(keys, values, context);
    std::int64_t prior = 0;
    result.refs.reserve(encoded_refs.size());
    for (const auto encoded : encoded_refs) {
        prior = checked_add(prior, zigzag(encoded), "way reference delta");
        result.refs.push_back(positive_id(prior, "way node reference"));
    }
    return result;
}

Relation parse_relation(std::string_view bytes, const BlockContext& context) {
    ProtoReader reader(bytes);
    bool have_id = false;
    bool have_info = false;
    std::int64_t id = 0;
    Metadata metadata;
    std::vector<std::uint64_t> keys;
    std::vector<std::uint64_t> values;
    std::vector<std::uint64_t> roles;
    std::vector<std::uint64_t> member_ids;
    std::vector<std::uint64_t> types;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 0, "Relation.id");
            if (have_id) fail("malformed protobuf duplicate Relation.id");
            id = signed_varint(reader.varint());
            have_id = true;
        } else if (field == 2) {
            append_packed(reader, wire, keys, "Relation.keys");
        } else if (field == 3) {
            append_packed(reader, wire, values, "Relation.vals");
        } else if (field == 4) {
            require_wire(wire, 2, "Relation.info");
            if (have_info) fail("malformed protobuf duplicate Relation.info");
            metadata = parse_info(reader.bytes(), context);
            have_info = true;
        } else if (field == 8) {
            append_packed(reader, wire, roles, "Relation.roles_sid");
        } else if (field == 9) {
            append_packed(reader, wire, member_ids, "Relation.memids");
        } else if (field == 10) {
            append_packed(reader, wire, types, "Relation.types");
        } else {
            reader.skip(wire);
        }
    }
    if (!have_id || !have_info) fail("malformed protobuf Relation is missing ID or metadata");
    if (roles.size() != member_ids.size() || roles.size() != types.size()) {
        fail("PBF relation member arrays have different lengths");
    }
    Relation result;
    result.id = positive_id(id, "relation ID");
    result.metadata = std::move(metadata);
    result.tags = resolve_tags(keys, values, context);
    std::int64_t prior = 0;
    result.members.reserve(roles.size());
    for (std::size_t index = 0; index < roles.size(); ++index) {
        if (roles[index] >= context.strings.size()) fail("PBF relation role SID is out of range");
        prior = checked_add(prior, zigzag(member_ids[index]), "relation member delta");
        if (types[index] > 2) fail("PBF relation has invalid member type");
        static constexpr char TYPE[] = {'n', 'w', 'r'};
        result.members.push_back(Member{
            TYPE[static_cast<std::size_t>(types[index])],
            positive_id(prior, "relation member ID"),
            context.strings[static_cast<std::size_t>(roles[index])],
        });
    }
    return result;
}

bool has_tag(const Tags& tags, std::string_view key, std::string_view value) {
    return std::any_of(tags.begin(), tags.end(), [&](const auto& item) {
        return item.first == key && item.second == value;
    });
}

bool canonical_admin_level(const Tags& tags) {
    for (const auto& [key, value] : tags) {
        if (key != "admin_level") continue;
        return value == "2" || value == "3" || value == "4" || value == "5" ||
            value == "6" || value == "7" || value == "8" || value == "9" ||
            value == "10" || value == "11";
    }
    return false;
}

bool direct_way(const Way& way) {
    return has_tag(way.tags, "natural", "coastline") ||
        (has_tag(way.tags, "boundary", "administrative") && canonical_admin_level(way.tags));
}

bool admitted_relation(const Relation& relation) {
    return has_tag(relation.tags, "boundary", "administrative") &&
        canonical_admin_level(relation.tags) &&
        (has_tag(relation.tags, "type", "boundary") ||
         has_tag(relation.tags, "type", "multipolygon"));
}

}  // namespace

namespace {

enum class PassMode { SelectRoots, CollectWays, CollectNodes };

struct ObjectCounts {
    std::uint64_t nodes = 0;
    std::uint64_t ways = 0;
    std::uint64_t relations = 0;

    ObjectCounts& operator+=(const ObjectCounts& other) {
        nodes += other.nodes;
        ways += other.ways;
        relations += other.relations;
        return *this;
    }
};

struct ParsedBlob {
    ObjectCounts counts;
    std::vector<std::uint64_t> selected_way_ids;
    std::vector<Way> selected_ways;
    std::vector<Node> selected_nodes;
    std::vector<Relation> admitted_relations;
};

class WorkQuota final {
public:
    explicit WorkQuota(std::uint64_t maximum) : maximum_(maximum) {}

    void claim(std::size_t bytes) {
        const auto amount = static_cast<std::uint64_t>(bytes);
        if (amount > maximum_ - live_) fail("work byte quota exceeded");
        live_ += amount;
        peak_ = std::max(peak_, live_);
    }

    void release(std::uint64_t bytes) {
        if (bytes > live_) fail("internal work byte accounting underflow");
        live_ -= bytes;
    }

    std::uint64_t peak() const noexcept { return peak_; }

private:
    std::uint64_t maximum_;
    std::uint64_t live_ = 0;
    std::uint64_t peak_ = 0;
};

WorkQuota* active_work_quota = nullptr;

class WorkQuotaScope final {
public:
    explicit WorkQuotaScope(WorkQuota& quota) {
        if (active_work_quota) fail("nested work quota scope is forbidden");
        active_work_quota = &quota;
    }
    ~WorkQuotaScope() { active_work_quota = nullptr; }
    WorkQuotaScope(const WorkQuotaScope&) = delete;
    WorkQuotaScope& operator=(const WorkQuotaScope&) = delete;
};

void quota_write(std::ostream& output, const void* data, std::size_t bytes) {
    if (!active_work_quota) fail("work-file write occurred outside its quota scope");
    active_work_quota->claim(bytes);
    output.write(static_cast<const char*>(data), static_cast<std::streamsize>(bytes));
    if (!output) fail("failed to write quota-accounted work file");
}

void remove_work_file(const fs::path& path) {
    if (!fs::exists(path)) return;
    const auto bytes = fs::file_size(path);
    if (!fs::remove(path)) fail("failed to remove superseded work file");
    if (!active_work_quota) fail("work-file removal occurred outside its quota scope");
    active_work_quota->release(bytes);
}

class IdLookup;

class IdReader final {
public:
    explicit IdReader(const IdLookup& lookup);
    bool contains(std::uint64_t id);

private:
    const IdLookup& lookup_;
};

class IdLookup final {
public:
    explicit IdLookup(fs::path path);

private:
    friend class IdReader;
    bool contains(std::uint64_t id) const;

    std::vector<std::uint64_t> bits_;
};

struct ScanResult {
    ObjectCounts counts;
    std::uint64_t input_bytes = 0;
    std::string input_sha256;
};

struct DiskRecord {
    std::uint64_t id;
    std::string line;
};

void write_u64(std::ostream& output, std::uint64_t value);
void write_record(std::ostream& output, const DiskRecord& record);
std::uint64_t sort_ids(
    const fs::path& raw_path,
    const fs::path& sorted_path,
    const fs::path& prefix,
    bool reject_duplicates,
    std::string_view duplicate_label
);
void require_same_ids(
    const fs::path& expected_path,
    const fs::path& actual_path,
    std::string_view missing_label
);
std::uint64_t sort_records_to_opl(
    const fs::path& raw_path,
    const fs::path& opl_path,
    const fs::path& prefix,
    std::string_view duplicate_label
);
void concatenate_files(const std::vector<fs::path>& inputs, const fs::path& output_path);
std::string node_opl(const Node& node);
std::string way_opl(const Way& way);
std::string relation_opl(const Relation& relation);
ScanResult scan_pbf(
    const fs::path& input_path,
    unsigned workers,
    PassMode mode,
    const std::shared_ptr<const IdLookup>& lookup,
    const std::function<void(ParsedBlob&&)>& consume
);

struct Arguments {
    fs::path input;
    fs::path output;
    fs::path work;
    unsigned workers = MAX_WORKERS;
    std::uint64_t max_work_bytes = DEFAULT_MAX_WORK_BYTES;
    std::uint64_t max_output_bytes = DEFAULT_MAX_OUTPUT_BYTES;
};

std::uint64_t parse_positive_u64(const std::wstring& value, std::string_view label) {
    if (value.empty() || value.front() == L'0' ||
        !std::all_of(value.begin(), value.end(), [](wchar_t character) {
            return character >= L'0' && character <= L'9';
        })) {
        fail(std::string(label) + " must be a canonical positive u64");
    }
    std::uint64_t result = 0;
    for (const auto character : value) {
        const auto digit = static_cast<std::uint64_t>(character - L'0');
        if (result > (std::numeric_limits<std::uint64_t>::max() - digit) / 10) {
            fail(std::string(label) + " must be a canonical positive u64");
        }
        result = result * 10 + digit;
    }
    return result;
}

std::wstring lowercase(std::wstring value) {
    std::transform(value.begin(), value.end(), value.begin(), [](wchar_t character) {
        return static_cast<wchar_t>(std::towlower(character));
    });
    return value;
}

fs::path normalize_existing(const fs::path& path, std::string_view label) {
    std::error_code error;
    const auto result = fs::canonical(path, error);
    if (error) fail(std::string(label) + " does not resolve: " + path_utf8(path));
    return result.lexically_normal();
}

fs::path normalize_target(const fs::path& path, std::string_view label) {
    if (path.empty() || path.filename().empty()) fail(std::string(label) + " path is empty");
    std::error_code error;
    const auto absolute = fs::absolute(path, error);
    if (error) fail("cannot resolve " + std::string(label) + " path");
    const auto parent = fs::weakly_canonical(absolute.parent_path(), error);
    if (error) fail("cannot resolve " + std::string(label) + " parent path");
    return (parent / absolute.filename()).lexically_normal();
}

bool same_component(const fs::path& left, const fs::path& right) {
    return lowercase(left.native()) == lowercase(right.native());
}

bool path_within(const fs::path& child, const fs::path& parent) {
    auto child_it = child.begin();
    for (auto parent_it = parent.begin(); parent_it != parent.end(); ++parent_it, ++child_it) {
        if (child_it == child.end() || !same_component(*child_it, *parent_it)) return false;
    }
    return true;
}

fs::path receipt_path_for(const fs::path& output) {
    return fs::path(output.wstring() + L".receipt.json");
}

fs::path partial_path_for(const fs::path& path) {
    return fs::path(path.wstring() + L".partial");
}

Arguments parse_arguments(int argc, wchar_t** argv) {
    Arguments result;
    bool have_input = false;
    bool have_output = false;
    bool have_work = false;
    bool have_workers = false;
    bool have_max_work = false;
    bool have_max_output = false;
    for (int index = 1; index < argc; ++index) {
        const std::wstring flag = argv[index];
        if (index + 1 >= argc) fail("every CLI option requires an explicit value");
        const std::wstring value = argv[++index];
        if (flag == L"--input" && !have_input) {
            result.input = value;
            have_input = true;
        } else if (flag == L"--output" && !have_output) {
            result.output = value;
            have_output = true;
        } else if (flag == L"--work-dir" && !have_work) {
            result.work = value;
            have_work = true;
        } else if (flag == L"--workers" && !have_workers) {
            std::size_t consumed = 0;
            unsigned long parsed = 0;
            try {
                parsed = std::stoul(value, &consumed, 10);
            } catch (...) {
                fail("workers must be a canonical integer from 1 through 10");
            }
            if (consumed != value.size() || parsed < 1 || parsed > MAX_WORKERS) {
                fail("workers must be a canonical integer from 1 through 10");
            }
            result.workers = static_cast<unsigned>(parsed);
            have_workers = true;
        } else if (flag == L"--max-work-bytes" && !have_max_work) {
            result.max_work_bytes = parse_positive_u64(value, "max-work-bytes");
            have_max_work = true;
        } else if (flag == L"--max-output-bytes" && !have_max_output) {
            result.max_output_bytes = parse_positive_u64(value, "max-output-bytes");
            have_max_output = true;
        } else {
            fail("unknown or duplicate CLI option");
        }
    }
    if (!have_input || !have_output || !have_work) {
        fail("usage: native_pbf_extractor --input FILE --output FILE --work-dir DIR "
             "[--workers 1..10] [--max-work-bytes N] [--max-output-bytes N]");
    }
    return result;
}

fs::path executable_path() {
    std::vector<wchar_t> buffer(1024);
    while (true) {
        const auto length = GetModuleFileNameW(nullptr, buffer.data(), static_cast<DWORD>(buffer.size()));
        if (length == 0) fail("cannot resolve extractor executable path");
        if (length < buffer.size() - 1) return fs::path(std::wstring(buffer.data(), length));
        buffer.resize(buffer.size() * 2);
    }
}

void require_source_hash() {
    const std::string hash = FA_SOURCE_SHA256;
    if (hash.size() != 64 || !std::all_of(hash.begin(), hash.end(), [](unsigned char value) {
            return std::isdigit(value) || (value >= 'a' && value <= 'f');
        })) {
        fail("extractor was not built with a canonical source SHA-256 identity");
    }
}

void require_same_source(const ScanResult& first, const ScanResult& later) {
    if (first.input_bytes != later.input_bytes || first.input_sha256 != later.input_sha256 ||
        first.counts.nodes != later.counts.nodes || first.counts.ways != later.counts.ways ||
        first.counts.relations != later.counts.relations) {
        fail("input PBF changed or parsed differently between deterministic passes");
    }
}

void write_text_file(const fs::path& path, std::string_view bytes) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) fail("cannot create receipt temporary file");
    output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    if (!output) fail("failed to write receipt temporary file");
}

std::string build_receipt(
    const fs::path& input_path,
    const ScanResult& input_identity,
    const std::pair<std::uint64_t, std::string>& output_identity,
    const ObjectCounts& output_counts,
    const fs::path& executable,
    const std::pair<std::uint64_t, std::string>& executable_identity,
    unsigned workers,
    std::uint64_t max_work_bytes,
    std::uint64_t max_output_bytes,
    std::uint64_t peak_work_bytes
) {
    std::string semantic =
        std::string("{\"policy\":") + json_quote(POLICY_VERSION) +
        ",\"tool\":{\"name\":" + json_quote(TOOL_NAME) +
        ",\"version\":" + json_quote(TOOL_VERSION) +
        ",\"sourceSha256\":" + json_quote(FA_SOURCE_SHA256) + "}" +
        ",\"input\":{\"path\":" + json_quote(path_utf8(input_path)) +
        ",\"bytes\":" + std::to_string(input_identity.input_bytes) +
        ",\"sha256\":" + json_quote(input_identity.input_sha256) + "}" +
        ",\"output\":{\"bytes\":" + std::to_string(output_identity.first) +
        ",\"sha256\":" + json_quote(output_identity.second) + "}" +
        ",\"counts\":{\"inputObjects\":{\"nodes\":" +
            std::to_string(input_identity.counts.nodes) +
        ",\"ways\":" + std::to_string(input_identity.counts.ways) +
        ",\"relations\":" + std::to_string(input_identity.counts.relations) + "}" +
        ",\"outputObjects\":{\"nodes\":" + std::to_string(output_counts.nodes) +
        ",\"ways\":" + std::to_string(output_counts.ways) +
        ",\"relations\":" + std::to_string(output_counts.relations) + "}}}";
    const auto semantic_identity = hash_bytes(semantic).second;
    return std::string("{\"schema\":\"flightalert.osm-boundary-extraction-receipt.v1\"") +
        ",\"state\":\"complete\",\"semantic\":" + semantic +
        ",\"semanticIdentitySha256\":" + json_quote(semantic_identity) +
        ",\"execution\":{\"executable\":{\"path\":" + json_quote(path_utf8(executable)) +
        ",\"bytes\":" + std::to_string(executable_identity.first) +
        ",\"sha256\":" + json_quote(executable_identity.second) + "}" +
        ",\"workerCount\":" + std::to_string(workers) +
        ",\"resourceBounds\":{\"maxWorkers\":10,\"maxBlobHeaderBytes\":65536," +
        "\"maxBlobBytes\":67108864,\"maxUncompressedBlobBytes\":33554432," +
        "\"sortChunkBytes\":33554432,\"bloomBytes\":33554432,\"queueDepth\":10," +
        "\"maxWorkBytes\":" + std::to_string(max_work_bytes) +
        ",\"maxOutputBytes\":" + std::to_string(max_output_bytes) +
        ",\"peakWorkBytes\":" + std::to_string(peak_work_bytes) + "}}" +
        ",\"failureClosedPolicy\":{\"completionMarker\":\"receipt-last\"," +
        "\"existingTargets\":\"never-overwritten\"," +
        "\"malformedOrIncompleteInput\":\"reject\"," +
        "\"temporaryOutput\":\"removed-on-failure\"}}\n";
}

void run_extraction(Arguments arguments) {
    require_source_hash();
    const auto input_path = normalize_existing(arguments.input, "input PBF");
    if (!fs::is_regular_file(input_path)) fail("input PBF is not a regular file");
    const auto output_path = normalize_target(arguments.output, "output OPL");
    const auto work_path = normalize_target(arguments.work, "work directory");
    const auto receipt_path = receipt_path_for(output_path);
    const auto output_partial = partial_path_for(output_path);
    const auto receipt_partial = partial_path_for(receipt_path);

    if (path_within(output_path, work_path) || path_within(receipt_path, work_path) ||
        path_within(input_path, work_path) || same_component(input_path, output_path)) {
        fail("unsafe input/output/work path overlap");
    }
    if (!fs::is_directory(output_path.parent_path()) || !fs::is_directory(work_path.parent_path())) {
        fail("output and work parents must already be directories");
    }
    for (const auto& path : {output_path, receipt_path, output_partial, receipt_partial, work_path}) {
        if (fs::exists(path)) fail("refusing to overwrite path that already exists: " + path_utf8(path));
    }
    if (!fs::create_directory(work_path)) fail("cannot create explicit work directory");
    WorkQuota work_quota(arguments.max_work_bytes);
    WorkQuotaScope quota_scope(work_quota);

    try {
        const auto way_ids_raw = work_path / "selected-way-ids.raw";
        const auto way_ids_sorted = work_path / "selected-way-ids.sorted";
        const auto relation_records_raw = work_path / "relations.raw";
        const auto relations_opl = work_path / "relations.opl";
        std::ofstream way_ids(way_ids_raw, std::ios::binary | std::ios::trunc);
        std::ofstream relation_records(relation_records_raw, std::ios::binary | std::ios::trunc);
        if (!way_ids || !relation_records) {
            fail("cannot create pass-one disk stores");
        }
        const auto first_scan = scan_pbf(
            input_path,
            arguments.workers,
            PassMode::SelectRoots,
            nullptr,
            [&](ParsedBlob&& blob) {
                for (const auto id : blob.selected_way_ids) write_u64(way_ids, id);
                for (const auto& relation : blob.admitted_relations) {
                    write_record(relation_records, DiskRecord{relation.id, relation_opl(relation)});
                }
            }
        );
        way_ids.close();
        relation_records.close();
        const auto selected_way_count = sort_ids(
            way_ids_raw,
            way_ids_sorted,
            work_path / "way-id-sort",
            false,
            "selected way"
        );
        const auto relation_count = sort_records_to_opl(
            relation_records_raw,
            relations_opl,
            work_path / "relation-sort",
            "admitted relation"
        );

        const auto way_lookup = std::make_shared<const IdLookup>(way_ids_sorted);
        const auto node_ids_raw = work_path / "selected-node-ids.raw";
        const auto node_ids_sorted = work_path / "selected-node-ids.sorted";
        const auto found_way_ids_raw = work_path / "found-way-ids.raw";
        const auto found_way_ids_sorted = work_path / "found-way-ids.sorted";
        const auto way_records_raw = work_path / "ways.raw";
        const auto ways_opl = work_path / "ways.opl";
        std::ofstream node_ids(node_ids_raw, std::ios::binary | std::ios::trunc);
        std::ofstream found_way_ids(found_way_ids_raw, std::ios::binary | std::ios::trunc);
        std::ofstream way_records(way_records_raw, std::ios::binary | std::ios::trunc);
        if (!node_ids || !found_way_ids || !way_records) fail("cannot create pass-two disk stores");
        const auto second_scan = scan_pbf(
            input_path,
            arguments.workers,
            PassMode::CollectWays,
            way_lookup,
            [&](ParsedBlob&& blob) {
                for (const auto& way : blob.selected_ways) {
                    if (way.refs.size() < 2) fail("selected way has unusable geometry with fewer than two refs");
                    write_u64(found_way_ids, way.id);
                    for (const auto ref : way.refs) write_u64(node_ids, ref);
                    write_record(way_records, DiskRecord{way.id, way_opl(way)});
                }
            }
        );
        node_ids.close();
        found_way_ids.close();
        way_records.close();
        require_same_source(first_scan, second_scan);
        const auto found_way_count = sort_ids(
            found_way_ids_raw,
            found_way_ids_sorted,
            work_path / "found-way-sort",
            true,
            "selected way"
        );
        require_same_ids(way_ids_sorted, found_way_ids_sorted, "way");
        if (found_way_count != selected_way_count) fail("selected way inventory changed between passes");
        const auto selected_node_count = sort_ids(
            node_ids_raw,
            node_ids_sorted,
            work_path / "node-id-sort",
            false,
            "selected node"
        );
        const auto way_count = sort_records_to_opl(
            way_records_raw,
            ways_opl,
            work_path / "way-sort",
            "selected way"
        );
        if (way_count != selected_way_count) fail("selected way OPL inventory differs from closure IDs");

        const auto node_lookup = std::make_shared<const IdLookup>(node_ids_sorted);
        const auto found_node_ids_raw = work_path / "found-node-ids.raw";
        const auto found_node_ids_sorted = work_path / "found-node-ids.sorted";
        const auto node_records_raw = work_path / "nodes.raw";
        const auto nodes_opl = work_path / "nodes.opl";
        std::ofstream found_node_ids(found_node_ids_raw, std::ios::binary | std::ios::trunc);
        std::ofstream node_records(node_records_raw, std::ios::binary | std::ios::trunc);
        if (!found_node_ids || !node_records) fail("cannot create pass-three disk stores");
        const auto third_scan = scan_pbf(
            input_path,
            arguments.workers,
            PassMode::CollectNodes,
            node_lookup,
            [&](ParsedBlob&& blob) {
                for (const auto& node : blob.selected_nodes) {
                    write_u64(found_node_ids, node.id);
                    write_record(node_records, DiskRecord{node.id, node_opl(node)});
                }
            }
        );
        found_node_ids.close();
        node_records.close();
        require_same_source(first_scan, third_scan);
        const auto found_node_count = sort_ids(
            found_node_ids_raw,
            found_node_ids_sorted,
            work_path / "found-node-sort",
            true,
            "selected node"
        );
        require_same_ids(node_ids_sorted, found_node_ids_sorted, "node");
        if (found_node_count != selected_node_count) fail("selected node inventory changed between passes");
        const auto node_count = sort_records_to_opl(
            node_records_raw,
            nodes_opl,
            work_path / "node-sort",
            "selected node"
        );
        if (node_count != selected_node_count) fail("selected node OPL inventory differs from closure IDs");

        const auto final_opl = work_path / "closure.opl";
        concatenate_files({nodes_opl, ways_opl, relations_opl}, final_opl);
        const auto output_identity = hash_file(final_opl);
        if (output_identity.first > arguments.max_output_bytes) {
            fail("output byte quota exceeded");
        }
        const auto executable = normalize_existing(executable_path(), "extractor executable");
        const auto executable_identity = hash_file(executable);
        const ObjectCounts output_counts{node_count, way_count, relation_count};
        const auto receipt = build_receipt(
            input_path,
            first_scan,
            output_identity,
            output_counts,
            executable,
            executable_identity,
            arguments.workers,
            arguments.max_work_bytes,
            arguments.max_output_bytes,
            work_quota.peak()
        );
        fs::copy_file(final_opl, output_partial, fs::copy_options::none);
        write_text_file(receipt_partial, receipt);
        if (fs::remove_all(work_path) == 0 || fs::exists(work_path)) {
            fail("failed to remove explicit work directory");
        }
        fs::rename(output_partial, output_path);
        try {
            fs::rename(receipt_partial, receipt_path);
        } catch (...) {
            fs::remove(output_path);
            throw;
        }
        std::cout << "sha256=" << output_identity.second
                  << " bytes=" << output_identity.first
                  << " nodes=" << node_count
                  << " ways=" << way_count
                  << " relations=" << relation_count
                  << " workers=" << arguments.workers << '\n';
    } catch (...) {
        std::error_code ignored;
        fs::remove(output_partial, ignored);
        fs::remove(receipt_partial, ignored);
        fs::remove_all(work_path, ignored);
        throw;
    }
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    try {
        run_extraction(parse_arguments(argc, argv));
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "error: " << error.what() << '\n';
        return 2;
    }
}

namespace {

void write_u64(std::ostream& output, std::uint64_t value);
std::optional<std::uint64_t> read_u64(std::istream& input);
fs::path numbered_path(const fs::path& prefix, std::string_view phase, std::size_t number);

std::string coordinate_text(std::int64_t value) {
    const bool negative = value < 0;
    const auto magnitude = static_cast<std::uint64_t>(negative ? -value : value);
    std::ostringstream output;
    if (negative) output << '-';
    output << magnitude / 10'000'000 << '.' << std::setw(7) << std::setfill('0')
           << magnitude % 10'000'000;
    return output.str();
}

void append_tags(std::string& output, const Tags& tags) {
    if (tags.empty()) return;
    output += " T";
    for (std::size_t index = 0; index < tags.size(); ++index) {
        if (index) output.push_back(',');
        output += opl_escape(tags[index].first, "OPL tag key");
        output.push_back('=');
        output += opl_escape(tags[index].second, "OPL tag value");
    }
}

std::string node_opl(const Node& node) {
    std::string output = "n" + std::to_string(node.id) + " v" +
        std::to_string(node.metadata.version) + " dV t" + node.metadata.timestamp;
    append_tags(output, node.tags);
    output += " x" + coordinate_text(node.longitude_e7) +
        " y" + coordinate_text(node.latitude_e7) + "\n";
    if (output.size() > MAX_OPL_LINE_BYTES + 1) fail("selected node OPL line exceeds consumer bound");
    return output;
}

std::string way_opl(const Way& way) {
    std::string output = "w" + std::to_string(way.id) + " v" +
        std::to_string(way.metadata.version) + " dV t" + way.metadata.timestamp;
    append_tags(output, way.tags);
    output += " N";
    for (std::size_t index = 0; index < way.refs.size(); ++index) {
        if (index) output.push_back(',');
        output.push_back('n');
        output += std::to_string(way.refs[index]);
    }
    output.push_back('\n');
    if (output.size() > MAX_OPL_LINE_BYTES + 1) fail("selected way OPL line exceeds consumer bound");
    return output;
}

std::string relation_opl(const Relation& relation) {
    std::string output = "r" + std::to_string(relation.id) + " v" +
        std::to_string(relation.metadata.version) + " dV t" + relation.metadata.timestamp;
    append_tags(output, relation.tags);
    output += " M";
    for (std::size_t index = 0; index < relation.members.size(); ++index) {
        if (index) output.push_back(',');
        output.push_back(relation.members[index].type);
        output += std::to_string(relation.members[index].ref);
        output.push_back('@');
        output += opl_escape(relation.members[index].role, "OPL relation role");
    }
    output.push_back('\n');
    if (output.size() > MAX_OPL_LINE_BYTES + 1) fail("admitted relation OPL line exceeds consumer bound");
    return output;
}

void write_record(std::ostream& output, const DiskRecord& record) {
    write_u64(output, record.id);
    write_u64(output, record.line.size());
    quota_write(output, record.line.data(), record.line.size());
}

std::optional<DiskRecord> read_record(std::istream& input) {
    const auto id = read_u64(input);
    if (!id) return std::nullopt;
    const auto length = read_u64(input);
    if (!length || *length > MAX_OPL_LINE_BYTES + 1) {
        fail("disk-backed object record has invalid length");
    }
    std::string line(static_cast<std::size_t>(*length), '\0');
    input.read(line.data(), static_cast<std::streamsize>(line.size()));
    if (input.gcount() != static_cast<std::streamsize>(line.size())) {
        fail("disk-backed object record is truncated");
    }
    if (line.empty() || line.back() != '\n' ||
        (line.size() >= 2 && line[line.size() - 2] == '\r')) {
        fail("disk-backed OPL record is not canonical LF text");
    }
    return DiskRecord{*id, std::move(line)};
}

void merge_record_group(
    const std::vector<fs::path>& inputs,
    const fs::path& output_path,
    std::string_view duplicate_label
) {
    struct Item {
        DiskRecord record;
        std::size_t source;
    };
    const auto later = [](const Item& left, const Item& right) {
        return left.record.id > right.record.id ||
            (left.record.id == right.record.id && left.source > right.source);
    };
    std::vector<std::ifstream> streams;
    streams.reserve(inputs.size());
    std::priority_queue<Item, std::vector<Item>, decltype(later)> heap(later);
    for (std::size_t index = 0; index < inputs.size(); ++index) {
        streams.emplace_back(inputs[index], std::ios::binary);
        if (!streams.back()) fail("cannot open object sort run");
        if (auto record = read_record(streams.back())) {
            heap.push(Item{std::move(*record), index});
        }
    }
    std::ofstream output(output_path, std::ios::binary | std::ios::trunc);
    if (!output) fail("cannot create merged object sort run");
    std::optional<std::uint64_t> previous;
    while (!heap.empty()) {
        auto item = std::move(const_cast<Item&>(heap.top()));
        heap.pop();
        if (previous && *previous == item.record.id) {
            fail("duplicate current " + std::string(duplicate_label));
        }
        previous = item.record.id;
        write_record(output, item.record);
        if (auto next = read_record(streams[item.source])) {
            heap.push(Item{std::move(*next), item.source});
        }
    }
}

std::uint64_t sort_records_to_opl(
    const fs::path& raw_path,
    const fs::path& opl_path,
    const fs::path& prefix,
    std::string_view duplicate_label
) {
    std::ifstream raw(raw_path, std::ios::binary);
    if (!raw) fail("cannot open raw object extraction store");
    std::vector<fs::path> runs;
    std::size_t run_number = 0;
    while (true) {
        std::vector<DiskRecord> records;
        std::size_t memory = 0;
        while (memory < SORT_CHUNK_BYTES) {
            auto record = read_record(raw);
            if (!record) break;
            memory += record->line.size() + sizeof(DiskRecord);
            records.push_back(std::move(*record));
        }
        if (records.empty()) break;
        std::sort(records.begin(), records.end(), [](const auto& left, const auto& right) {
            return left.id < right.id;
        });
        if (std::adjacent_find(records.begin(), records.end(), [](const auto& left, const auto& right) {
                return left.id == right.id;
            }) != records.end()) {
            fail("duplicate current " + std::string(duplicate_label));
        }
        const auto run_path = numbered_path(prefix, "run", run_number++);
        std::ofstream run(run_path, std::ios::binary | std::ios::trunc);
        if (!run) fail("cannot create object sort run");
        for (const auto& record : records) write_record(run, record);
        runs.push_back(run_path);
    }
    raw.close();
    if (runs.empty()) {
        std::ofstream output(opl_path, std::ios::binary | std::ios::trunc);
        if (!output) fail("cannot create empty OPL part");
        return 0;
    }
    std::size_t pass = 0;
    while (runs.size() > 1) {
        std::vector<fs::path> next;
        for (std::size_t start = 0; start < runs.size(); start += MERGE_FAN_IN) {
            const auto end = std::min(runs.size(), start + MERGE_FAN_IN);
            std::vector<fs::path> group(runs.begin() + static_cast<std::ptrdiff_t>(start),
                                        runs.begin() + static_cast<std::ptrdiff_t>(end));
            const auto merged = numbered_path(prefix, "merge" + std::to_string(pass), next.size());
            merge_record_group(group, merged, duplicate_label);
            for (const auto& path : group) remove_work_file(path);
            next.push_back(merged);
        }
        runs = std::move(next);
        ++pass;
    }

    std::ifstream sorted(runs.front(), std::ios::binary);
    std::ofstream output(opl_path, std::ios::binary | std::ios::trunc);
    if (!sorted || !output) fail("cannot materialize sorted OPL part");
    std::uint64_t count = 0;
    while (auto record = read_record(sorted)) {
        quota_write(output, record->line.data(), record->line.size());
        ++count;
    }
    sorted.close();
    output.close();
    remove_work_file(runs.front());
    return count;
}

void concatenate_files(const std::vector<fs::path>& inputs, const fs::path& output_path) {
    std::ofstream output(output_path, std::ios::binary | std::ios::trunc);
    if (!output) fail("cannot create final strict OPL closure");
    std::vector<char> buffer(1 << 20);
    for (const auto& path : inputs) {
        std::ifstream input(path, std::ios::binary);
        if (!input) fail("cannot open sorted OPL part");
        while (input) {
            input.read(buffer.data(), buffer.size());
            const auto count = input.gcount();
            if (count > 0) quota_write(output, buffer.data(), static_cast<std::size_t>(count));
        }
        if (!input.eof() || !output) fail("failed while concatenating strict OPL closure");
    }
}

}  // namespace

namespace {

struct FramedBlob {
    std::string type;
    std::string encoded_blob;
};

struct HeaderFeatures {
    bool dense_nodes = false;
};

FramedBlob read_framed_blob(std::ifstream& input, Sha256& source_hash, bool& eof) {
    std::array<unsigned char, 4> length_bytes{};
    input.read(reinterpret_cast<char*>(length_bytes.data()), length_bytes.size());
    const auto received = input.gcount();
    if (received == 0 && input.eof()) {
        eof = true;
        return {};
    }
    if (received != static_cast<std::streamsize>(length_bytes.size())) {
        fail("malformed PBF truncated BlobHeader length");
    }
    source_hash.update(length_bytes.data(), length_bytes.size());
    const auto header_length =
        (static_cast<std::uint32_t>(length_bytes[0]) << 24) |
        (static_cast<std::uint32_t>(length_bytes[1]) << 16) |
        (static_cast<std::uint32_t>(length_bytes[2]) << 8) |
        static_cast<std::uint32_t>(length_bytes[3]);
    if (header_length == 0 || header_length > MAX_BLOB_HEADER_BYTES) {
        fail("malformed PBF BlobHeader length is outside its bound");
    }
    std::string header_bytes(header_length, '\0');
    input.read(header_bytes.data(), static_cast<std::streamsize>(header_bytes.size()));
    if (input.gcount() != static_cast<std::streamsize>(header_bytes.size())) {
        fail("malformed PBF truncated BlobHeader");
    }
    source_hash.update(header_bytes.data(), header_bytes.size());

    ProtoReader reader(header_bytes);
    bool have_type = false;
    bool have_size = false;
    std::string type;
    std::uint64_t data_size = 0;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 2, "BlobHeader.type");
            if (have_type) fail("malformed protobuf duplicate BlobHeader.type");
            const auto raw = reader.bytes();
            utf8_scalars(raw, "PBF BlobHeader type");
            type.assign(raw);
            have_type = true;
        } else if (field == 2) {
            require_wire(wire, 2, "BlobHeader.indexdata");
            (void)reader.bytes();
        } else if (field == 3) {
            require_wire(wire, 0, "BlobHeader.datasize");
            if (have_size) fail("malformed protobuf duplicate BlobHeader.datasize");
            data_size = reader.varint();
            have_size = true;
        } else {
            reader.skip(wire);
        }
    }
    if (!have_type || !have_size || data_size == 0 || data_size > MAX_BLOB_BYTES) {
        fail("malformed PBF BlobHeader is missing or exceeds bounded datasize");
    }
    std::string blob(static_cast<std::size_t>(data_size), '\0');
    input.read(blob.data(), static_cast<std::streamsize>(blob.size()));
    if (input.gcount() != static_cast<std::streamsize>(blob.size())) {
        fail("malformed PBF truncated Blob payload");
    }
    source_hash.update(blob.data(), blob.size());
    eof = false;
    return FramedBlob{std::move(type), std::move(blob)};
}

std::string decode_blob(std::string_view bytes) {
    ProtoReader reader(bytes);
    bool have_raw = false;
    bool have_zlib = false;
    bool have_raw_size = false;
    std::string_view raw;
    std::string_view compressed;
    std::uint64_t raw_size = 0;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 2, "Blob.raw");
            if (have_raw) fail("malformed protobuf duplicate Blob.raw");
            raw = reader.bytes();
            have_raw = true;
        } else if (field == 2) {
            require_wire(wire, 0, "Blob.raw_size");
            if (have_raw_size) fail("malformed protobuf duplicate Blob.raw_size");
            raw_size = reader.varint();
            have_raw_size = true;
        } else if (field == 3) {
            require_wire(wire, 2, "Blob.zlib_data");
            if (have_zlib) fail("malformed protobuf duplicate Blob.zlib_data");
            compressed = reader.bytes();
            have_zlib = true;
        } else if (field >= 4 && field <= 7) {
            fail("unsupported PBF compression; only raw and zlib blobs are accepted");
        } else {
            fail("unsupported PBF Blob field or compression");
        }
    }
    if (have_raw == have_zlib) fail("PBF Blob must carry exactly one raw or zlib payload");
    if (have_raw) {
        if (raw.size() > MAX_UNCOMPRESSED_BLOB_BYTES) fail("raw PBF Blob exceeds its uncompressed bound");
        if (have_raw_size && raw_size != raw.size()) fail("raw PBF Blob raw_size disagrees");
        return std::string(raw);
    }
    if (!have_raw_size || raw_size > MAX_UNCOMPRESSED_BLOB_BYTES) {
        fail("zlib PBF Blob is missing a bounded raw_size");
    }
    std::string output(static_cast<std::size_t>(raw_size), '\0');
    uLongf output_size = static_cast<uLongf>(output.size());
    const auto status = uncompress(
        reinterpret_cast<Bytef*>(output.data()),
        &output_size,
        reinterpret_cast<const Bytef*>(compressed.data()),
        static_cast<uLong>(compressed.size())
    );
    if (status != Z_OK || output_size != raw_size) fail("malformed zlib PBF Blob payload");
    return output;
}

HeaderFeatures parse_header_block(std::string_view bytes) {
    ProtoReader reader(bytes);
    std::set<std::string> required;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 4 || field == 5 || field == 16 || field == 17 || field == 34) {
            require_wire(wire, 2, "HeaderBlock string");
            const auto raw = reader.bytes();
            utf8_scalars(raw, "PBF HeaderBlock string");
            if (field == 4 && !required.insert(std::string(raw)).second) {
                fail("PBF HeaderBlock repeats a required feature");
            }
        } else if (field == 1) {
            require_wire(wire, 2, "HeaderBlock.bbox");
            (void)reader.bytes();
        } else if (field == 32 || field == 33) {
            require_wire(wire, 0, "HeaderBlock replication metadata");
            (void)reader.varint();
        } else {
            reader.skip(wire);
        }
    }
    if (!required.contains("OsmSchema-V0.6")) {
        fail("PBF HeaderBlock does not require OsmSchema-V0.6");
    }
    for (const auto& feature : required) {
        if (feature != "OsmSchema-V0.6" && feature != "DenseNodes" &&
            feature != "HistoricalInformation") {
            fail("unsupported required PBF feature: " + feature);
        }
    }
    return HeaderFeatures{required.contains("DenseNodes")};
}

}  // namespace

namespace {

void accept_node(Node&& node, PassMode mode, IdReader* lookup, ParsedBlob& output) {
    ++output.counts.nodes;
    if (mode == PassMode::CollectNodes && node.metadata.visible && lookup->contains(node.id)) {
        output.selected_nodes.push_back(std::move(node));
    }
}

void accept_way(Way&& way, PassMode mode, IdReader* lookup, ParsedBlob& output) {
    ++output.counts.ways;
    if (!way.metadata.visible) return;
    if (mode == PassMode::SelectRoots) {
        if (direct_way(way)) output.selected_way_ids.push_back(way.id);
    } else if (mode == PassMode::CollectWays && lookup->contains(way.id)) {
        output.selected_ways.push_back(std::move(way));
    }
}

void accept_relation(Relation&& relation, PassMode mode, ParsedBlob& output) {
    ++output.counts.relations;
    if (mode != PassMode::SelectRoots || !relation.metadata.visible ||
        !admitted_relation(relation)) return;
    std::vector<std::uint64_t> geometry_way_ids;
    for (const auto& member : relation.members) {
        if (member.role != "outer" && member.role != "inner") continue;
        if (member.type != 'w') return;
        geometry_way_ids.push_back(member.ref);
    }
    if (geometry_way_ids.empty()) return;
    output.selected_way_ids.insert(
        output.selected_way_ids.end(),
        geometry_way_ids.begin(),
        geometry_way_ids.end()
    );
    output.admitted_relations.push_back(std::move(relation));
}

struct DenseInfoArrays {
    std::vector<std::uint64_t> versions;
    std::vector<std::uint64_t> timestamps;
    std::vector<std::uint64_t> changesets;
    std::vector<std::uint64_t> uids;
    std::vector<std::uint64_t> user_sids;
    std::vector<std::uint64_t> visible;
};

DenseInfoArrays parse_dense_info(std::string_view bytes) {
    DenseInfoArrays output;
    ProtoReader reader(bytes);
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) append_packed(reader, wire, output.versions, "DenseInfo.version");
        else if (field == 2) append_packed(reader, wire, output.timestamps, "DenseInfo.timestamp");
        else if (field == 3) append_packed(reader, wire, output.changesets, "DenseInfo.changeset");
        else if (field == 4) append_packed(reader, wire, output.uids, "DenseInfo.uid");
        else if (field == 5) append_packed(reader, wire, output.user_sids, "DenseInfo.user_sid");
        else if (field == 6) append_packed(reader, wire, output.visible, "DenseInfo.visible");
        else reader.skip(wire);
    }
    return output;
}

void require_dense_length(
    const std::vector<std::uint64_t>& values,
    std::size_t expected,
    std::string_view label,
    bool optional = false
) {
    if ((optional && values.empty()) || values.size() == expected) return;
    fail("PBF DenseInfo " + std::string(label) + " length differs from DenseNodes");
}

void parse_dense_nodes(
    std::string_view bytes,
    const BlockContext& context,
    PassMode mode,
    IdReader* lookup,
    ParsedBlob& output
) {
    if (!context.dense_feature) fail("DenseNodes data lacks the DenseNodes required feature");
    ProtoReader reader(bytes);
    std::vector<std::uint64_t> ids;
    std::vector<std::uint64_t> latitudes;
    std::vector<std::uint64_t> longitudes;
    std::vector<std::uint64_t> keys_values;
    std::optional<DenseInfoArrays> info;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) append_packed(reader, wire, ids, "DenseNodes.id");
        else if (field == 5) {
            require_wire(wire, 2, "DenseNodes.denseinfo");
            if (info) fail("malformed protobuf duplicate DenseNodes.denseinfo");
            info = parse_dense_info(reader.bytes());
        } else if (field == 8) append_packed(reader, wire, latitudes, "DenseNodes.lat");
        else if (field == 9) append_packed(reader, wire, longitudes, "DenseNodes.lon");
        else if (field == 10) append_packed(reader, wire, keys_values, "DenseNodes.keys_vals");
        else reader.skip(wire);
    }
    if (ids.size() != latitudes.size() || ids.size() != longitudes.size()) {
        fail("PBF DenseNodes ID/latitude/longitude arrays have different lengths");
    }
    if (!info) fail("PBF DenseNodes is missing DenseInfo source metadata");
    const auto count = ids.size();
    require_dense_length(info->versions, count, "version");
    require_dense_length(info->timestamps, count, "timestamp");
    require_dense_length(info->changesets, count, "changeset", true);
    require_dense_length(info->uids, count, "uid", true);
    require_dense_length(info->user_sids, count, "user_sid", true);
    require_dense_length(info->visible, count, "visible", true);

    std::int64_t id = 0;
    std::int64_t latitude = 0;
    std::int64_t longitude = 0;
    std::int64_t timestamp = 0;
    std::int64_t changeset = 0;
    std::int64_t uid = 0;
    std::int64_t user_sid = 0;
    std::size_t tag_cursor = 0;
    for (std::size_t index = 0; index < count; ++index) {
        id = checked_add(id, zigzag(ids[index]), "dense node ID delta");
        latitude = checked_add(latitude, zigzag(latitudes[index]), "dense latitude delta");
        longitude = checked_add(longitude, zigzag(longitudes[index]), "dense longitude delta");
        timestamp = checked_add(timestamp, zigzag(info->timestamps[index]), "dense timestamp delta");
        if (!info->changesets.empty()) {
            changeset = checked_add(changeset, zigzag(info->changesets[index]), "dense changeset delta");
            (void)changeset;
        }
        if (!info->uids.empty()) {
            uid = checked_add(uid, zigzag(info->uids[index]), "dense uid delta");
            (void)uid;
        }
        if (!info->user_sids.empty()) {
            user_sid = checked_add(user_sid, zigzag(info->user_sids[index]), "dense user SID delta");
            if (user_sid < 0 || static_cast<std::uint64_t>(user_sid) >= context.strings.size()) {
                fail("dense user SID is outside the string table");
            }
        }
        if (info->versions[index] == 0 ||
            info->versions[index] > static_cast<std::uint64_t>(std::numeric_limits<std::int32_t>::max())) {
            fail("dense node version is not a positive int32");
        }
        const bool visible = info->visible.empty() || info->visible[index] == 1;
        if (!info->visible.empty() && info->visible[index] > 1) {
            fail("DenseInfo.visible is not boolean");
        }
        const auto tag_start = tag_cursor;
        bool terminated = false;
        while (tag_cursor < keys_values.size()) {
            const auto key = keys_values[tag_cursor++];
            if (key == 0) {
                terminated = true;
                break;
            }
            if (tag_cursor == keys_values.size()) fail("DenseNodes keys_vals ends after a key");
            ++tag_cursor;
        }
        if (!terminated) fail("DenseNodes keys_vals lacks one terminator per node");
        const auto node_id = positive_id(id, "dense node ID");
        ++output.counts.nodes;
        if (mode != PassMode::CollectNodes || !visible || !lookup->contains(node_id)) continue;

        std::vector<std::uint64_t> keys;
        std::vector<std::uint64_t> values;
        for (auto cursor = tag_start; cursor + 1 < tag_cursor; cursor += 2) {
            keys.push_back(keys_values[cursor]);
            values.push_back(keys_values[cursor + 1]);
        }
        Node node;
        node.id = node_id;
        node.metadata = Metadata{
            static_cast<std::int32_t>(info->versions[index]),
            format_timestamp(timestamp, context),
            visible,
        };
        node.tags = resolve_tags(keys, values, context);
        node.longitude_e7 = coordinate_e7(longitude, context.longitude_offset, context, false);
        node.latitude_e7 = coordinate_e7(latitude, context.latitude_offset, context, true);
        output.selected_nodes.push_back(std::move(node));
    }
    if (tag_cursor != keys_values.size()) fail("DenseNodes keys_vals has trailing data");
}

std::uint64_t count_dense_nodes(std::string_view bytes, bool dense_feature) {
    if (!dense_feature) fail("DenseNodes data lacks the DenseNodes required feature");
    ProtoReader reader(bytes);
    std::uint64_t count = 0;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field != 1) {
            reader.skip(wire);
            continue;
        }
        if (wire == 0) {
            (void)reader.varint();
            ++count;
            continue;
        }
        require_wire(wire, 2, "DenseNodes.id");
        ProtoReader packed(reader.bytes());
        while (!packed.empty()) {
            (void)packed.varint();
            ++count;
        }
    }
    return count;
}

std::vector<std::string> parse_string_table(std::string_view bytes) {
    ProtoReader reader(bytes);
    std::vector<std::string> output;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 2, "StringTable.s");
            const auto raw = reader.bytes();
            utf8_scalars(raw, "PBF string table entry");
            output.emplace_back(raw);
        } else {
            reader.skip(wire);
        }
    }
    if (output.empty() || !output.front().empty()) {
        fail("PBF string table must begin with the empty string");
    }
    return output;
}

void parse_primitive_group(
    std::string_view bytes,
    const BlockContext& context,
    PassMode mode,
    IdReader* lookup,
    ParsedBlob& output
) {
    ProtoReader reader(bytes);
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        require_wire(wire, 2, "PrimitiveGroup object");
        const auto message = reader.bytes();
        if (field == 1) {
            if (mode == PassMode::CollectNodes) {
                accept_node(parse_node(message, context), mode, lookup, output);
            } else {
                ++output.counts.nodes;
            }
        } else if (field == 2) {
            if (mode == PassMode::CollectNodes) {
                parse_dense_nodes(message, context, mode, lookup, output);
            } else {
                output.counts.nodes += count_dense_nodes(message, context.dense_feature);
            }
        } else if (field == 3) {
            if (mode == PassMode::CollectNodes) {
                ++output.counts.ways;
            } else {
                accept_way(parse_way(message, context), mode, lookup, output);
            }
        } else if (field == 4) {
            if (mode == PassMode::SelectRoots) {
                accept_relation(parse_relation(message, context), mode, output);
            } else {
                ++output.counts.relations;
            }
        }
        else if (field == 5) fail("PBF changesets are unsupported in an OSM boundary source");
        else fail("PBF PrimitiveGroup contains an unsupported object type");
    }
}

ParsedBlob parse_primitive_block(
    std::string_view bytes,
    bool dense_feature,
    PassMode mode,
    const std::shared_ptr<const IdLookup>& lookup
) {
    ProtoReader reader(bytes);
    bool have_table = false;
    bool have_granularity = false;
    bool have_date_granularity = false;
    bool have_latitude_offset = false;
    bool have_longitude_offset = false;
    std::string_view table_bytes;
    std::vector<std::string_view> groups;
    BlockContext context;
    context.dense_feature = dense_feature;
    while (!reader.empty()) {
        const auto [field, wire] = reader.key();
        if (field == 1) {
            require_wire(wire, 2, "PrimitiveBlock.stringtable");
            if (have_table) fail("malformed protobuf duplicate PrimitiveBlock string table");
            table_bytes = reader.bytes();
            have_table = true;
        } else if (field == 2) {
            require_wire(wire, 2, "PrimitiveBlock.primitivegroup");
            groups.push_back(reader.bytes());
        } else if (field == 17 || field == 18) {
            require_wire(wire, 0, "PrimitiveBlock granularity");
            const auto raw = reader.varint();
            if (raw == 0 || raw > static_cast<std::uint64_t>(std::numeric_limits<std::int32_t>::max())) {
                fail("PBF granularity is outside positive int32");
            }
            if (field == 17) {
                if (have_granularity) fail("malformed protobuf duplicate PrimitiveBlock granularity");
                context.granularity = static_cast<std::int64_t>(raw);
                have_granularity = true;
            } else {
                if (have_date_granularity) fail("malformed protobuf duplicate date granularity");
                context.date_granularity = static_cast<std::int64_t>(raw);
                have_date_granularity = true;
            }
        } else if (field == 19 || field == 20) {
            require_wire(wire, 0, "PrimitiveBlock coordinate offset");
            const auto value = signed_varint(reader.varint());
            if (field == 19) {
                if (have_latitude_offset) fail("malformed protobuf duplicate latitude offset");
                context.latitude_offset = value;
                have_latitude_offset = true;
            } else {
                if (have_longitude_offset) fail("malformed protobuf duplicate longitude offset");
                context.longitude_offset = value;
                have_longitude_offset = true;
            }
        } else {
            reader.skip(wire);
        }
    }
    if (!have_table) fail("PBF PrimitiveBlock is missing its string table");
    context.strings = parse_string_table(table_bytes);
    std::optional<IdReader> id_reader;
    if (lookup) id_reader.emplace(*lookup);
    ParsedBlob output;
    for (const auto group : groups) {
        parse_primitive_group(group, context, mode, id_reader ? &*id_reader : nullptr, output);
    }
    return output;
}

}  // namespace

namespace {

ScanResult scan_pbf(
    const fs::path& input_path,
    unsigned workers,
    PassMode mode,
    const std::shared_ptr<const IdLookup>& lookup,
    const std::function<void(ParsedBlob&&)>& consume
) {
    std::ifstream input(input_path, std::ios::binary);
    if (!input) fail("cannot open input PBF: " + path_utf8(input_path));
    Sha256 source_hash;
    bool eof = false;
    auto first = read_framed_blob(input, source_hash, eof);
    if (eof || first.type != "OSMHeader") fail("PBF must begin with exactly one OSMHeader blob");
    const auto header = parse_header_block(decode_blob(first.encoded_blob));

    struct Pending {
        std::uint64_t ordinal;
        std::future<ParsedBlob> future;
    };
    std::deque<Pending> pending;
    ObjectCounts counts;
    std::uint64_t ordinal = 0;
    const auto reduce_front = [&]() {
        auto item = std::move(pending.front());
        pending.pop_front();
        try {
            auto parsed = item.future.get();
            counts += parsed.counts;
            consume(std::move(parsed));
        } catch (const std::exception& error) {
            fail("PBF data blob " + std::to_string(item.ordinal) + ": " + error.what());
        }
    };

    while (true) {
        auto framed = read_framed_blob(input, source_hash, eof);
        if (eof) break;
        if (framed.type != "OSMData") {
            fail("PBF contains a non-OSMData blob after its header");
        }
        const auto current = ordinal++;
        pending.push_back(Pending{
            current,
            std::async(std::launch::async,
                [encoded = std::move(framed.encoded_blob), header, mode, lookup]() {
                    const auto raw = decode_blob(encoded);
                    return parse_primitive_block(raw, header.dense_nodes, mode, lookup);
                })
        });
        if (pending.size() >= workers) reduce_front();
    }
    while (!pending.empty()) reduce_front();
    if (!input.eof()) fail("failed while reading input PBF");
    const auto [bytes, digest] = source_hash.finish();
    return ScanResult{counts, bytes, digest};
}

}  // namespace

namespace {

void write_u64(std::ostream& output, std::uint64_t value) {
    std::array<char, 8> bytes{};
    for (unsigned index = 0; index < 8; ++index) {
        bytes[index] = static_cast<char>((value >> (index * 8)) & 0xff);
    }
    quota_write(output, bytes.data(), bytes.size());
}

std::optional<std::uint64_t> read_u64(std::istream& input) {
    std::array<unsigned char, 8> bytes{};
    input.read(reinterpret_cast<char*>(bytes.data()), bytes.size());
    const auto received = input.gcount();
    if (received == 0 && input.eof()) return std::nullopt;
    if (received != static_cast<std::streamsize>(bytes.size())) {
        fail("disk-backed extraction store is truncated");
    }
    std::uint64_t value = 0;
    for (unsigned index = 0; index < 8; ++index) {
        value |= static_cast<std::uint64_t>(bytes[index]) << (index * 8);
    }
    return value;
}

fs::path numbered_path(const fs::path& prefix, std::string_view phase, std::size_t number) {
    return fs::path(prefix.wstring() + L"-" + std::wstring(phase.begin(), phase.end()) +
                    L"-" + std::to_wstring(number) + L".bin");
}

void merge_id_group(
    const std::vector<fs::path>& inputs,
    const fs::path& output_path,
    bool reject_duplicates,
    std::string_view duplicate_label
) {
    struct Item {
        std::uint64_t value;
        std::size_t source;
    };
    const auto later = [](const Item& left, const Item& right) {
        return left.value > right.value ||
            (left.value == right.value && left.source > right.source);
    };
    std::vector<std::ifstream> streams;
    streams.reserve(inputs.size());
    std::priority_queue<Item, std::vector<Item>, decltype(later)> heap(later);
    for (std::size_t index = 0; index < inputs.size(); ++index) {
        streams.emplace_back(inputs[index], std::ios::binary);
        if (!streams.back()) fail("cannot open ID sort run");
        if (const auto value = read_u64(streams.back())) heap.push(Item{*value, index});
    }
    std::ofstream output(output_path, std::ios::binary | std::ios::trunc);
    if (!output) fail("cannot create merged ID store");
    std::optional<std::uint64_t> previous;
    while (!heap.empty()) {
        const auto item = heap.top();
        heap.pop();
        if (previous && *previous == item.value) {
            if (reject_duplicates) fail("duplicate current " + std::string(duplicate_label));
        } else {
            write_u64(output, item.value);
            previous = item.value;
        }
        if (const auto next = read_u64(streams[item.source])) {
            heap.push(Item{*next, item.source});
        }
    }
}

std::uint64_t sort_ids(
    const fs::path& raw_path,
    const fs::path& sorted_path,
    const fs::path& prefix,
    bool reject_duplicates,
    std::string_view duplicate_label
) {
    std::ifstream raw(raw_path, std::ios::binary);
    if (!raw) fail("cannot open raw ID extraction store");
    const std::size_t per_chunk = SORT_CHUNK_BYTES / sizeof(std::uint64_t);
    std::vector<fs::path> runs;
    std::size_t run_number = 0;
    while (true) {
        std::vector<std::uint64_t> values;
        values.reserve(per_chunk);
        while (values.size() < per_chunk) {
            const auto value = read_u64(raw);
            if (!value) break;
            values.push_back(*value);
        }
        if (values.empty()) break;
        std::sort(values.begin(), values.end());
        const auto duplicate = std::adjacent_find(values.begin(), values.end());
        if (reject_duplicates && duplicate != values.end()) {
            fail("duplicate current " + std::string(duplicate_label));
        }
        values.erase(std::unique(values.begin(), values.end()), values.end());
        const auto run_path = numbered_path(prefix, "run", run_number++);
        std::ofstream run(run_path, std::ios::binary | std::ios::trunc);
        if (!run) fail("cannot create ID sort run");
        for (const auto value : values) write_u64(run, value);
        runs.push_back(run_path);
    }
    raw.close();
    if (runs.empty()) {
        std::ofstream empty(sorted_path, std::ios::binary | std::ios::trunc);
        if (!empty) fail("cannot create empty sorted ID store");
        return 0;
    }
    std::size_t pass = 0;
    while (runs.size() > 1) {
        std::vector<fs::path> next;
        for (std::size_t start = 0; start < runs.size(); start += MERGE_FAN_IN) {
            const auto end = std::min(runs.size(), start + MERGE_FAN_IN);
            std::vector<fs::path> group(runs.begin() + static_cast<std::ptrdiff_t>(start),
                                        runs.begin() + static_cast<std::ptrdiff_t>(end));
            const auto merged = numbered_path(prefix, "merge" + std::to_string(pass), next.size());
            merge_id_group(group, merged, reject_duplicates, duplicate_label);
            for (const auto& path : group) remove_work_file(path);
            next.push_back(merged);
        }
        runs = std::move(next);
        ++pass;
    }
    fs::rename(runs.front(), sorted_path);
    const auto bytes = fs::file_size(sorted_path);
    if (bytes % 8 != 0) fail("sorted ID store has invalid byte length");
    return bytes / 8;
}

void require_same_ids(
    const fs::path& expected_path,
    const fs::path& actual_path,
    std::string_view missing_label
) {
    std::ifstream expected(expected_path, std::ios::binary);
    std::ifstream actual(actual_path, std::ios::binary);
    if (!expected || !actual) fail("cannot compare closure ID stores");
    while (true) {
        const auto left = read_u64(expected);
        const auto right = read_u64(actual);
        if (!left && !right) return;
        if (!left || !right || *left != *right) {
            fail("missing " + std::string(missing_label) + " geometry reference");
        }
    }
}

IdLookup::IdLookup(fs::path path) {
    const auto bytes = fs::file_size(path);
    if (bytes % 8 != 0) fail("ID lookup store has invalid byte length");
    if (bytes == 0) return;

    std::ifstream input(path, std::ios::binary);
    if (!input) fail("cannot open sorted ID lookup store");
    input.seekg(static_cast<std::streamoff>(bytes - 8), std::ios::beg);
    const auto maximum_id = read_u64(input);
    if (!maximum_id) fail("sorted ID lookup store ended unexpectedly");
    const auto word_count = *maximum_id / 64 + 1;
    if (word_count > bits_.max_size()) fail("ID lookup bitset is too large");
    bits_.assign(static_cast<std::size_t>(word_count), 0);

    input.clear();
    input.seekg(0, std::ios::beg);
    while (const auto id = read_u64(input)) {
        bits_[static_cast<std::size_t>(*id / 64)] |= std::uint64_t{1} << (*id % 64);
    }
}

bool IdLookup::contains(std::uint64_t id) const {
    const auto word = id / 64;
    return word < bits_.size() &&
        (bits_[static_cast<std::size_t>(word)] & (std::uint64_t{1} << (id % 64))) != 0;
}

IdReader::IdReader(const IdLookup& lookup)
    : lookup_(lookup) {}

bool IdReader::contains(std::uint64_t id) {
    return lookup_.contains(id);
}

}  // namespace
