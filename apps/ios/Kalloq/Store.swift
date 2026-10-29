import Foundation
import KalloqCore

/**
 * Device-local state, as `data/LocalStore.kt` keeps it on Android. The session token and the identity
 * seed go to the Keychain as `ThisDeviceOnly`, so they are not in any backup and cannot be read
 * before the first unlock. What only this phone knows about contacts — the names the user gave them
 * and the keys pinned on first sight — goes to a file with the same protection.
 */
final class Store {
    private static let service = "com.cim.securecall"
    private static let tokenKey = "token"
    private static let seedKey = "identity_seed"

    private let defaults = UserDefaults.standard

    var token: String? {
        get { Self.secret(Self.tokenKey).map { String(decoding: $0, as: UTF8.self) } }
        set { Self.setSecret(Self.tokenKey, newValue.map { Data($0.utf8) }) }
    }

    var identitySeed: Data? {
        get { Self.secret(Self.seedKey) }
        set { Self.setSecret(Self.seedKey, newValue) }
    }

    /// A seed is stored even if it cannot be read right now: never replace it silently, or every
    /// contact would see "key changed".
    var hasIdentitySeed: Bool { Self.hasSecret(Self.seedKey) }

    var userId: String? {
        get { defaults.string(forKey: "user_id") }
        set { defaults.set(newValue, forKey: "user_id") }
    }

    var username: String? {
        get { defaults.string(forKey: "username") }
        set { defaults.set(newValue, forKey: "username") }
    }

    /// Names the user gave to contacts. Never sent to the server.
    private(set) var aliases: [String: String] = [:]

    /// Identity keys pinned on first sight (TOFU), base64url. A different key from the server
    /// means "key changed".
    private(set) var pins: [String: String] = [:]

    init() { load() }

    func setAlias(_ id: String, _ alias: String?) {
        let trimmed = alias?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty { aliases[id] = trimmed } else { aliases[id] = nil }
        save()
    }

    func setPin(_ id: String, _ ik: String) {
        pins[id] = ik
        save()
    }

    /**
     * A contact got a new user id. Copies (never moves, never overwrites) what we know about them, so
     * the server cannot use a rename to drop a pinned key or to put another contact's key on someone
     * we already know.
     */
    func renameContact(from oldId: String, to newId: String) {
        guard oldId != newId, pins[newId] == nil, aliases[newId] == nil else { return }
        if let alias = aliases[oldId] { aliases[newId] = alias }
        if let pin = pins[oldId] { pins[newId] = pin }
        save()
    }

    /// Incoming call ids already handled, so an offer the server replays does not ring again.
    /// Only the last [seenCalls] matter: an older one can no longer pass the offer's timestamp.
    func callSeen(_ callId: String) -> Bool {
        defaults.stringArray(forKey: "seen_calls")?.contains(callId) ?? false
    }

    func markCallSeen(_ callId: String) {
        var seen = (defaults.stringArray(forKey: "seen_calls") ?? []).filter { $0 != callId }
        seen.append(callId)
        defaults.set(seen.suffix(Self.seenCalls).map { $0 }, forKey: "seen_calls")
    }

    private static let seenCalls = 200

    func clearSession() { token = nil }

    /// New account on this device: forget the previous identity and contacts.
    func resetAccount() {
        token = nil
        identitySeed = nil
        userId = nil
        username = nil
        aliases = [:]
        pins = [:]
        save()
    }

    // ---------------------------------------------------------------- contacts file

    private struct Contacts: Codable {
        var aliases: [String: String] = [:]
        var pins: [String: String] = [:]
    }

    private var contactsURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("kalloq", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("contacts.json")
    }

    private func load() {
        guard let data = try? Data(contentsOf: contactsURL),
              let contacts = try? JSONDecoder().decode(Contacts.self, from: data) else { return }
        aliases = contacts.aliases
        pins = contacts.pins
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(Contacts(aliases: aliases, pins: pins)) else { return }
        try? data.write(to: contactsURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    // ---------------------------------------------------------------- keychain

    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    private static func secret(_ key: String) -> Data? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private static func hasSecret(_ key: String) -> Bool {
        SecItemCopyMatching(query(key) as CFDictionary, nil) == errSecSuccess
    }

    private static func setSecret(_ key: String, _ value: Data?) {
        SecItemDelete(query(key) as CFDictionary)
        guard let value else { return }
        var item = query(key)
        item[kSecValueData as String] = value
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }
}
