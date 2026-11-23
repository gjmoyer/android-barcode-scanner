import Foundation

/// MSI checksum schemes. `nativeCode` mirrors `MsiNativeDecoder.nativePolicyCode`
/// and `toRequiredPolicy` in the Android JNI bridge.
enum MsiChecksumPolicy: String, CaseIterable, Identifiable {
    case mod10
    case mod11
    case mod10_10
    case mod10_11
    case none

    var id: String { rawValue }

    var title: String {
        switch self {
        case .mod10: return "Mod10"
        case .mod11: return "Mod11"
        case .mod10_10: return "Mod1010"
        case .mod10_11: return "Mod1110"
        case .none: return "None"
        }
    }

    var nativeCode: Int32 {
        switch self {
        case .mod10: return 0
        case .mod11: return 1
        case .mod10_10: return 2
        case .mod10_11: return 3
        case .none: return -1
        }
    }
}

/// Swift port of the Android SDK's `MsiChecksumValidator`, kept intentionally
/// small: the native decoder already filters candidates under the required
/// policy, and this tail re-validates and strips check digits for display.
enum MsiChecksumValidator {

    struct Validation {
        let payloadWithoutChecksum: String
        let valid: Bool
    }

    static func validate(_ full: String, policy: MsiChecksumPolicy) -> Validation {
        guard full.allSatisfy(\.isASCIIDigit) else {
            return Validation(payloadWithoutChecksum: full, valid: false)
        }
        let chars = Array(full)
        switch policy {
        case .none:
            return Validation(payloadWithoutChecksum: full, valid: true)
        case .mod10:
            guard chars.count >= 2 else { return invalid(full) }
            let payload = String(chars.dropLast())
            return Validation(
                payloadWithoutChecksum: payload,
                valid: chars.last!.digitValue == mod10Check(payload)
            )
        case .mod11:
            guard chars.count >= 2 else { return invalid(full) }
            let payload = String(chars.dropLast())
            let expected = mod11Check(payload)
            return Validation(
                payloadWithoutChecksum: payload,
                valid: expected >= 0 && chars.last!.digitValue == expected
            )
        case .mod10_10:
            guard chars.count >= 3 else { return invalid(full) }
            let payload = String(chars.dropLast(2))
            let first = chars[chars.count - 2].digitValue
            let second = chars[chars.count - 1].digitValue
            guard first == mod10Check(payload) else { return invalid(full) }
            let firstPayload = payload + String(first)
            return Validation(
                payloadWithoutChecksum: payload,
                valid: second == mod10Check(firstPayload)
            )
        case .mod10_11:
            guard chars.count >= 3 else { return invalid(full) }
            let payload = String(chars.dropLast(2))
            let first = mod11Check(payload)
            guard first >= 0, chars[chars.count - 2].digitValue == first else {
                return invalid(full)
            }
            let firstPayload = payload + String(first)
            return Validation(
                payloadWithoutChecksum: payload,
                valid: chars.last!.digitValue == mod10Check(firstPayload)
            )
        }
    }

    /// Mod10 (Luhn): double every second digit from the right of the payload.
    static func mod10Check(_ payload: String) -> Int {
        var sum = 0
        var doubleDigit = true
        for character in payload.reversed() {
            var digit = character.digitValue
            if doubleDigit {
                digit *= 2
                if digit > 9 { digit -= 9 }
            }
            sum += digit
            doubleDigit.toggle()
        }
        return (10 - (sum % 10)) % 10
    }

    /// IBM Mod11: weights 2...7 repeating from the right. -1 means the check
    /// digit would be 10 and cannot be represented as a decimal digit.
    static func mod11Check(_ payload: String) -> Int {
        var sum = 0
        var weight = 2
        for character in payload.reversed() {
            sum += character.digitValue * weight
            weight = weight == 7 ? 2 : weight + 1
        }
        let remainder = (11 - (sum % 11)) % 11
        return remainder == 10 ? -1 : remainder
    }

    private static func invalid(_ full: String) -> Validation {
        Validation(payloadWithoutChecksum: full, valid: false)
    }
}

private extension Character {
    var isASCIIDigit: Bool {
        guard let ascii = asciiValue else { return false }
        return ascii >= 48 && ascii <= 57
    }

    var digitValue: Int {
        Int(asciiValue! - 48)
    }
}
