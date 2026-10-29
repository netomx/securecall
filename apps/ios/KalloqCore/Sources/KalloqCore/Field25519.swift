import Foundation

/**
 * Arithmetic modulo p = 2^255 - 19, only as much as turning an Ed25519 public key into its X25519
 * form needs: one addition, one subtraction and one inversion. CryptoKit does not expose that
 * conversion and libsodium would be a whole dependency for it, so the field lives here instead.
 *
 * Numbers are four 64-bit limbs, least significant first, always kept below p.
 */
struct Field25519 {
    private static let p: [UInt64] = [0xFFFF_FFFF_FFFF_FFED, .max, .max, 0x7FFF_FFFF_FFFF_FFFF]

    /// p - 2, the exponent that inverts a non-zero element (Fermat).
    private static let pMinus2: [UInt64] = [0xFFFF_FFFF_FFFF_FFEB, .max, .max, 0x7FFF_FFFF_FFFF_FFFF]

    static let one = Field25519(limbs: [1, 0, 0, 0])

    private let l: [UInt64]

    private init(limbs: [UInt64]) { l = limbs }

    /// 32 bytes little-endian. Fails unless they are below p, as a field element must be.
    init?(littleEndian bytes: [UInt8]) {
        guard bytes.count == 32 else { return nil }
        var limbs = [UInt64](repeating: 0, count: 4)
        for i in 0..<32 { limbs[i / 8] |= UInt64(bytes[i]) << (8 * (i % 8)) }
        guard Self.compare(limbs, Self.p) < 0 else { return nil }
        l = limbs
    }

    var littleEndian: [UInt8] { (0..<32).map { UInt8(truncatingIfNeeded: l[$0 / 8] >> (8 * ($0 % 8))) } }

    var isZero: Bool { l.allSatisfy { $0 == 0 } }

    static func + (a: Field25519, b: Field25519) -> Field25519 {
        // Both are below p < 2^255, so the sum fits in four limbs and is below 2p.
        var out = [UInt64](repeating: 0, count: 4)
        var carry: UInt64 = 0
        for i in 0..<4 {
            let (s1, o1) = a.l[i].addingReportingOverflow(b.l[i])
            let (s2, o2) = s1.addingReportingOverflow(carry)
            out[i] = s2
            carry = (o1 ? 1 : 0) + (o2 ? 1 : 0)
        }
        return Field25519(limbs: subtractP(out))
    }

    static func - (a: Field25519, b: Field25519) -> Field25519 {
        // (a + p) - b, which stays within four limbs because a + p < 2^256 and b < p.
        var out = [UInt64](repeating: 0, count: 4)
        var carry: UInt64 = 0
        for i in 0..<4 {
            let (s1, o1) = a.l[i].addingReportingOverflow(p[i])
            let (s2, o2) = s1.addingReportingOverflow(carry)
            out[i] = s2
            carry = (o1 ? 1 : 0) + (o2 ? 1 : 0)
        }
        var borrow: UInt64 = 0
        for i in 0..<4 {
            let (d1, u1) = out[i].subtractingReportingOverflow(b.l[i])
            let (d2, u2) = d1.subtractingReportingOverflow(borrow)
            out[i] = d2
            borrow = (u1 ? 1 : 0) + (u2 ? 1 : 0)
        }
        return Field25519(limbs: subtractP(out))
    }

    static func * (a: Field25519, b: Field25519) -> Field25519 {
        var t = [UInt64](repeating: 0, count: 8)
        for i in 0..<4 {
            var carry: UInt64 = 0
            for j in 0..<4 {
                let (hi, lo) = a.l[i].multipliedFullWidth(by: b.l[j])
                let (s1, o1) = t[i + j].addingReportingOverflow(lo)
                let (s2, o2) = s1.addingReportingOverflow(carry)
                t[i + j] = s2
                carry = hi &+ (o1 ? 1 : 0) &+ (o2 ? 1 : 0)
            }
            t[i + 4] = t[i + 4] &+ carry
        }
        // 2^256 = 38 mod p, so fold the top half down until nothing is left above 256 bits.
        var acc = Array(t[0..<4])
        var high = Array(t[4..<8])
        while true {
            let carry = addScaled(&acc, high, by: 38)
            if carry == 0 { break }
            high = [carry, 0, 0, 0]
        }
        return Field25519(limbs: subtractP(acc))
    }

    /// a^(p-2) mod p, the inverse of any non-zero element.
    func inverted() -> Field25519 {
        var r = Field25519.one
        for bit in stride(from: 254, through: 0, by: -1) {
            r = r * r
            if (Field25519.pMinus2[bit / 64] >> UInt64(bit % 64)) & 1 == 1 { r = r * self }
        }
        return r
    }

    /// acc += m * x, returning whatever carried above 256 bits.
    private static func addScaled(_ acc: inout [UInt64], _ x: [UInt64], by m: UInt64) -> UInt64 {
        var carry: UInt64 = 0
        for i in 0..<4 {
            let (hi, lo) = m.multipliedFullWidth(by: x[i])
            let (s1, o1) = acc[i].addingReportingOverflow(lo)
            let (s2, o2) = s1.addingReportingOverflow(carry)
            acc[i] = s2
            carry = hi &+ (o1 ? 1 : 0) &+ (o2 ? 1 : 0)
        }
        return carry
    }

    private static func subtractP(_ v: [UInt64]) -> [UInt64] {
        var v = v
        while compare(v, p) >= 0 {
            var borrow: UInt64 = 0
            for i in 0..<4 {
                let (d1, u1) = v[i].subtractingReportingOverflow(p[i])
                let (d2, u2) = d1.subtractingReportingOverflow(borrow)
                v[i] = d2
                borrow = (u1 ? 1 : 0) + (u2 ? 1 : 0)
            }
        }
        return v
    }

    private static func compare(_ a: [UInt64], _ b: [UInt64]) -> Int {
        for i in stride(from: 3, through: 0, by: -1) where a[i] != b[i] { return a[i] < b[i] ? -1 : 1 }
        return 0
    }
}
