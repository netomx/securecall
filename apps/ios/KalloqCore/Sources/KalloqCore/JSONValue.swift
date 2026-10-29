import Foundation

/**
 * A JSON value as the signalling protocol uses it. The messages are small and loosely typed
 * (`docs/PROTOCOLO.md`): one struct per operation would be more code than it is worth, so the
 * client passes whole values around, the way the Kotlin client passes `JsonObject`.
 */
public enum JSONValue: Sendable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(_ any: Any) {
        switch any {
        case let v as String:
            self = .string(v)
        case let v as NSNumber:
            // JSONSerialization hands back booleans as NSNumber too; only CFBoolean is a real one.
            self = CFGetTypeID(v) == CFBooleanGetTypeID() ? .bool(v.boolValue) : .number(v.doubleValue)
        case let v as [Any]:
            self = .array(v.map(JSONValue.init))
        case let v as [String: Any]:
            self = .object(v.mapValues(JSONValue.init))
        default:
            self = .null
        }
    }

    public static func parse(_ text: String) -> JSONValue? {
        guard let data = text.data(using: .utf8),
              let any = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return JSONValue(any)
    }

    public func encoded() -> Data {
        (try? JSONSerialization.data(withJSONObject: anyValue)) ?? Data("{}".utf8)
    }

    private var anyValue: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let v): return v
        case .number(let v): return v == v.rounded() ? Int(v) : v
        case .string(let v): return v
        case .array(let v): return v.map(\.anyValue)
        case .object(let v): return v.mapValues(\.anyValue)
        }
    }

    // Readers that give nil rather than throwing: anything the server sends may be missing.

    public subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public var stringValue: String? {
        if case .string(let v) = self { return v }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let v) = self { return v }
        return nil
    }

    public var intValue: Int? {
        if case .number(let v) = self { return Int(v) }
        return nil
    }

    public var doubleValue: Double? {
        if case .number(let v) = self { return v }
        return nil
    }

    public var arrayValue: [JSONValue]? {
        if case .array(let v) = self { return v }
        return nil
    }

    public func string(_ key: String) -> String? { self[key]?.stringValue }

    public func strings(_ key: String) -> [String] { (self[key]?.arrayValue ?? []).compactMap(\.stringValue) }

    public func objects(_ key: String) -> [JSONValue] { self[key]?.arrayValue ?? [] }
}
