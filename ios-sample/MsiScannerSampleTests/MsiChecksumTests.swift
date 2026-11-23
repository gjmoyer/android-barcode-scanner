import XCTest
@testable import MsiScannerSample

final class MsiChecksumTests: XCTestCase {

    func testMod10KnownVectors() {
        XCTAssertEqual(MsiChecksumValidator.mod10Check("12345"), 5)
        XCTAssertEqual(MsiChecksumValidator.mod10Check("1234567"), 4)
        XCTAssertEqual(MsiChecksumValidator.mod10Check("8052"), 3)

        let result = MsiChecksumValidator.validate("12345674", policy: .mod10)
        XCTAssertTrue(result.valid)
        XCTAssertEqual(result.payloadWithoutChecksum, "1234567")

        XCTAssertFalse(MsiChecksumValidator.validate("123450", policy: .mod10).valid)
    }

    func testMod11RejectsUnrepresentableCheckDigit() {
        XCTAssertEqual(MsiChecksumValidator.mod11Check("6"), -1)
        XCTAssertFalse(MsiChecksumValidator.validate("60", policy: .mod11).valid)

        let payload = "987654"
        let full = payload + String(MsiChecksumValidator.mod11Check(payload))
        let result = MsiChecksumValidator.validate(full, policy: .mod11)
        XCTAssertTrue(result.valid)
        XCTAssertEqual(result.payloadWithoutChecksum, payload)
    }

    func testDoubleChecksumPolicies() {
        let payload = "12345"
        let first = MsiChecksumValidator.mod10Check(payload)
        let second = MsiChecksumValidator.mod10Check(payload + String(first))
        let full = payload + String(first) + String(second)

        let mod1010 = MsiChecksumValidator.validate(full, policy: .mod10_10)
        XCTAssertTrue(mod1010.valid)
        XCTAssertEqual(mod1010.payloadWithoutChecksum, payload)

        let mod1110First = MsiChecksumValidator.mod11Check(payload)
        let mod1110Second = MsiChecksumValidator.mod10Check(payload + String(mod1110First))
        let mod1110Full = payload + String(mod1110First) + String(mod1110Second)
        let mod1110 = MsiChecksumValidator.validate(mod1110Full, policy: .mod10_11)
        XCTAssertTrue(mod1110.valid)
        XCTAssertEqual(mod1110.payloadWithoutChecksum, payload)
    }

    func testNoneAcceptsAndNonDigitsReject() {
        XCTAssertTrue(MsiChecksumValidator.validate("000", policy: .none).valid)
        XCTAssertFalse(MsiChecksumValidator.validate("12A45", policy: .mod10).valid)
    }
}
