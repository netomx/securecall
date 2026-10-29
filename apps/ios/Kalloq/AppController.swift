import Combine
import Foundation
import KalloqCore

struct Friend: Identifiable, Equatable {
    let id: String
    var alias: String?
    var ik: String?
    var fingerprint: String?
    var keyChanged: Bool
    var pinned: String?  // the identity key we trust for this contact

    var displayName: String { alias ?? String(id.prefix(8)) }
}

enum Notice: Equatable, Identifiable {
    case error(String)
    case info(String)

    var id: String { text }

    var isError: Bool {
        if case .error = self { return true }
        return false
    }

    var text: String {
        switch self {
        case .info(let text): return text
        case .error(let code): return Self.errors[code] ?? "Algo salió mal (\(code))"
        }
    }

    /// The codes of `docs/PROTOCOLO.md`, worded as in Android's `values-es/strings.xml`.
    private static let errors = [
        "invalid_username": "Usuario inválido: 6–32 caracteres, a–z, 0–9, _ y .",
        "weak_password": "La contraseña necesita al menos 10 caracteres",
        "username_taken": "Ese usuario ya existe",
        "invalid_credentials": "Usuario, contraseña o código incorrectos",
        "rate_limited": "Demasiados intentos, espera unos minutos",
        "busy": "El servidor está ocupado, intenta de nuevo",
        "not_found": "Usuario no encontrado",
        "blocked": "Bloqueaste a este usuario",
        "invalid_id": "Eso no es un ID de Kalloq válido",
        "self": "Ese es tu propio ID",
        "network": "No se puede conectar al servidor",
        "timeout": "No se puede conectar al servidor",
        "disconnected": "No se puede conectar al servidor",
        "identity_unreadable": "No se puede leer tu llave de identidad guardada",
        "key_changed": "La llave de este contacto cambió: confírmala antes de llamar",
        "e2e_failed": "No se pudo cifrar la llamada con este contacto",
        "verify_mismatch": "La verificación en persona ya no coincide: verifiquen de nuevo",
        "no_microphone": "Kalloq necesita permiso para usar el micrófono",
        "offline": "Ese contacto no está disponible",
        "in_call": "Ya estás en una llamada",
    ]
}

/**
 * Everything the screens act on: the session, the contact list and the account operations of
 * `docs/PROTOCOLO.md`. It lives with the app, not with a screen, so the connection survives
 * navigation. Port of the account and contacts half of `AppController.kt`; calls come in step 3.
 */
@MainActor
final class AppController: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loggedOut
        case showRecovery(String)
        case loggedIn
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var conn: ConnState = .disconnected
    @Published private(set) var myId: String?
    @Published private(set) var username: String?
    @Published private(set) var friends: [Friend] = []
    @Published private(set) var incoming: [String] = []
    @Published private(set) var outgoing: [String] = []
    @Published private(set) var blocked: [String] = []
    @Published private(set) var busy = false
    @Published var notice: Notice?
    @Published var pendingInvite: String?
    /// The call in progress, mirrored from [calls] so the screens only watch one object.
    @Published private(set) var call: CallUi?

    /// `wss://call.l8.nz/ws`, or whatever `-serverURL` the build was launched with (for testing
    /// against a server on this Mac instead of production).
    let serverURL: String

    private let store = Store()
    private let server: ServerClient
    private(set) var calls: CallController!
    private var sessionTask: Task<Void, Never>?
    private var backoffSleep: Task<Void, Never>?
    private var sessionGeneration = 0
    private var authenticated = false

    var myInvite: String? { myId.map { Server.inviteBase + $0 } }

    init() {
        serverURL = UserDefaults.standard.string(forKey: "serverURL") ?? Server.url
        server = ServerClient(url: serverURL)
        calls = CallController(server: server, store: store, context: self)
        calls.$call.assign(to: &$call)

        Task { await watchStates() }
        Task { await watchEvents() }

        if store.token != nil {
            myId = store.userId
            username = store.username
            phase = .loggedIn
            keepSession()
        } else {
            phase = .loggedOut
        }
    }

    // ---------------------------------------------------------------- session

    /**
     * Keeps the socket connected and authenticated while logged in, reconnecting with backoff.
     * Only one of these ever runs: a second one would send `auth`, `set_key` and `contacts` twice.
     * Asking for a reconnect while it is running only cuts the backoff short.
     */
    private func keepSession() {
        guard sessionTask == nil else {
            backoffSleep?.cancel()
            return
        }
        sessionGeneration += 1
        let generation = sessionGeneration
        sessionTask = Task { [weak self] in
            var backoffMs: UInt64 = 500
            while !Task.isCancelled {
                guard let self, self.sessionGeneration == generation, let token = self.store.token else { break }
                do {
                    try await self.server.connect()
                    if !self.authenticated {
                        try await self.server.call("auth", ["token": .string(token)])
                        self.authenticated = true
                    }
                    try await self.afterAuth()
                    backoffMs = 500
                    await self.server.waitUntilDisconnected()
                } catch let error as ServerError where error.code == "invalid_token" {
                    self.signedOut(reason: "La sesión expiró, inicia sesión de nuevo")
                    return
                } catch {
                    // network error: wait and try again
                }
                let sleep = Task { _ = try? await Task.sleep(nanoseconds: backoffMs * 1_000_000) }
                self.backoffSleep = sleep
                await sleep.value
                self.backoffSleep = nil
                backoffMs = min(backoffMs * 2, 10_000)
            }
            // Only clear it if a newer session has not taken over in the meantime.
            if let self, self.sessionGeneration == generation { self.sessionTask = nil }
        }
    }

    private func endSession() {
        sessionGeneration += 1
        sessionTask?.cancel()
        sessionTask = nil
        backoffSleep?.cancel()
        backoffSleep = nil
    }

    /// The app came back to the front: reconnect now instead of waiting out the backoff.
    func reconnectNow() {
        if store.token != nil { keepSession() }
    }

    private func afterAuth() async throws {
        let seed: Data?
        if let stored = store.identitySeed {
            seed = stored
        } else if store.hasIdentitySeed {
            // Our stored identity exists but cannot be read: do not publish a new one (every contact
            // would see "key changed"); say so instead.
            seed = nil
        } else {
            let fresh = Identity.newSeed()
            store.identitySeed = fresh
            seed = fresh
        }
        if let seed {
            let ik = Base64URL.encode(try Identity.publicKey(seed: seed))
            try await server.call("set_key", ["ik": .string(ik)])
        } else {
            notice = .error("identity_unreadable")
        }
        try await refreshContacts()
    }

    private func watchStates() async {
        for await state in server.states {
            conn = state
            if state == .disconnected { authenticated = false }
        }
    }

    private func signedIn(_ reply: JSONValue, username: String) {
        let name = username.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let previous = store.username, previous != name { store.resetAccount() }
        store.token = reply.string("token")
        store.userId = reply.string("user_id")
        store.username = name
        authenticated = true
        myId = store.userId
        self.username = name
        endSession()
        keepSession()
    }

    private func signedOut(reason: String?) {
        endSession()
        calls.sessionLost()
        authenticated = false
        store.clearSession()
        Task { await server.disconnect() }
        phase = .loggedOut
        friends = []
        incoming = []
        outgoing = []
        blocked = []
        myId = nil
        username = nil
        if let reason { notice = .info(reason) }
    }

    // ---------------------------------------------------------------- events

    private func watchEvents() async {
        for await event in server.events {
            switch event.string("ev") {
            case "call_incoming": calls.onIncoming(event)
            case "call_answered": calls.onAnswered(event)
            case "call_ice": calls.onRemoteIce(event)
            case "call_ended": calls.onEnded(event)
            case "call_reconnecting": calls.onReconnecting(event)
            case "call_resumed": calls.onResumed(event)
            case "session_revoked":
                signedOut(reason: "Tu cuenta se abrió en otro dispositivo")
            case "friend_request":
                notice = .info("Nueva solicitud de contacto")
                await reload()
            case "friend_accepted":
                notice = .info("Aceptaron tu solicitud de contacto")
                await reload()
            case "key_changed":
                notice = .info("La llave de un contacto cambió")
                await reload()
            case "friend_removed":
                await reload()
            case "friend_id_changed":
                // Only for someone who is our contact now.
                if let old = event.string("old_id").flatMap(parseFriendId),
                   let new = event.string("new_id").flatMap(parseFriendId),
                   friends.contains(where: { $0.id == old }) {
                    store.renameContact(from: old, to: new)
                }
                await reload()
            default:
                break
            }
        }
    }

    private func reload() async { try? await refreshContacts() }

    private func refreshContacts() async throws {
        let reply = try await server.call("contacts")
        var list: [Friend] = []
        for entry in reply.objects("friends") {
            guard let id = entry.string("user_id").flatMap(parseFriendId) else { continue }
            let ik = entry.string("ik")
            var pinned = store.pins[id]
            if pinned == nil, let ik {
                store.setPin(id, ik)  // trust on first use
                pinned = ik
            }
            list.append(Friend(id: id,
                               alias: nil,
                               ik: ik,
                               fingerprint: ik.flatMap(Identity.fingerprint(base64url:)),
                               keyChanged: ik != nil && pinned != ik,
                               pinned: pinned))
        }
        friends = decorate(list)
        incoming = reply.strings("incoming")
        outgoing = reply.strings("outgoing")
        blocked = reply.strings("blocked")
    }

    /// Adds what only this phone knows: the names the user gave.
    private func decorate(_ list: [Friend]) -> [Friend] {
        list.map { friend in
            var friend = friend
            friend.alias = store.aliases[friend.id]
            return friend
        }
        .sorted { $0.displayName.lowercased() < $1.displayName.lowercased() }
    }

    func displayName(_ id: String) -> String {
        friends.first { $0.id == id }?.displayName ?? String(id.prefix(8))
    }

    // ---------------------------------------------------------------- account

    private func act(_ block: @escaping () async throws -> Void) {
        Task {
            busy = true
            do {
                try await block()
            } catch let error as ServerError {
                notice = .error(error.code)
            } catch {
                notice = .error("network")
            }
            busy = false
        }
    }

    func register(username: String, password: String) {
        act { [self] in
            let reply = try await server.call("register", [
                "username": .string(username.trimmingCharacters(in: .whitespacesAndNewlines)),
                "password": .string(password),
            ])
            store.resetAccount()
            signedIn(reply, username: username)
            phase = .showRecovery(reply.string("recovery_code") ?? "")
        }
    }

    func login(username: String, password: String) {
        act { [self] in
            let reply = try await server.call("login", [
                "username": .string(username.trimmingCharacters(in: .whitespacesAndNewlines)),
                "password": .string(password),
            ])
            signedIn(reply, username: username)
            phase = .loggedIn
        }
    }

    func recover(username: String, code: String, newPassword: String) {
        act { [self] in
            let reply = try await server.call("recover", [
                "username": .string(username.trimmingCharacters(in: .whitespacesAndNewlines)),
                "recovery_code": .string(code),
                "new_password": .string(newPassword),
            ])
            signedIn(reply, username: username)
            phase = .showRecovery(reply.string("recovery_code") ?? "")
        }
    }

    func recoverySaved() { phase = .loggedIn }

    func logout() {
        act { [self] in
            _ = try? await server.call("logout")
            signedOut(reason: nil)
        }
    }

    func deleteAccount(password: String) {
        act { [self] in
            try await server.call("delete_account", ["password": .string(password)])
            store.resetAccount()
            signedOut(reason: nil)
        }
    }

    func changePassword(current: String, new: String) {
        act { [self] in
            try await server.call("change_password", [
                "password": .string(current),
                "new_password": .string(new),
            ])
            notice = .info("Contraseña cambiada")
        }
    }

    func regenRecovery(password: String) {
        act { [self] in
            let reply = try await server.call("regen_recovery", ["password": .string(password)])
            phase = .showRecovery(reply.string("recovery_code") ?? "")
        }
    }

    func regenId() {
        act { [self] in
            let reply = try await server.call("regen_id")
            store.userId = reply.string("user_id")
            myId = store.userId
        }
    }

    // ---------------------------------------------------------------- contacts

    func addFriend(_ input: String) {
        guard let id = parseFriendId(input) else {
            notice = .error("invalid_id")
            return
        }
        guard id != myId else {
            notice = .error("self")
            return
        }
        act { [self] in
            let reply = try await server.call("friend_request", ["user_id": .string(id)])
            try await refreshContacts()
            notice = .info(reply.string("status") == "friends" ? "Ahora son contactos" : "Solicitud enviada")
        }
    }

    func respond(_ id: String, accept: Bool) {
        act { [self] in
            try await server.call("friend_respond", ["user_id": .string(id), "accept": .bool(accept)])
            try await refreshContacts()
        }
    }

    func removeFriend(_ id: String) {
        act { [self] in
            try await server.call("friend_remove", ["user_id": .string(id)])
            try await refreshContacts()
        }
    }

    func block(_ id: String) {
        act { [self] in
            try await server.call("block", ["user_id": .string(id)])
            try await refreshContacts()
        }
    }

    func unblock(_ id: String) {
        act { [self] in
            try await server.call("unblock", ["user_id": .string(id)])
            try await refreshContacts()
        }
    }

    func setAlias(_ id: String, _ alias: String) {
        store.setAlias(id, alias)
        friends = decorate(friends)
    }

    func startCall(_ id: String) { calls.start(friendId: id) }

    /// The user confirmed the contact's new key: pin it.
    func trustKey(_ id: String) {
        guard let ik = friends.first(where: { $0.id == id })?.ik else { return }
        store.setPin(id, ik)
        friends = decorate(friends.map { friend in
            guard friend.id == id else { return friend }
            var friend = friend
            friend.keyChanged = false
            friend.pinned = ik
            return friend
        })
    }
}

extension AppController: CallContext {
    func show(_ notice: Notice) { self.notice = notice }

    /**
     * Keys for a call with this contact: the identity key we pinned, plus the QR PSK once in-person
     * verification exists. Nil means we must not call: either their key changed and nobody confirmed
     * it, or our own identity cannot be read.
     */
    func callKeys(for friendId: String) -> CallKeys? {
        guard friends.first(where: { $0.id == friendId })?.keyChanged != true,
              let pinned = store.pins[friendId],
              let peerIk = Base64URL.decode(pinned),
              let seed = store.identitySeed else { return nil }
        return CallKeys(seed: seed, peerIk: peerIk, psk: nil)
    }
}
