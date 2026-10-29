import Foundation

public enum ConnState: Sendable { case disconnected, connecting, connected }

/// Error code returned by the server: `{"ok": false, "error": code}`. See `docs/PROTOCOLO.md`.
/// `disconnected` and `timeout` are ours, for the cases where no reply ever arrives.
public struct ServerError: Error, Equatable, Sendable {
    public let code: String
    public init(_ code: String) { self.code = code }
}

/**
 * JSON-over-WebSocket client for sc-server: request/response by `id`, plus a stream of events.
 * Port of `net/ServerClient.kt`. It knows nothing about accounts or calls — that is
 * `AppController`'s job — so the same client serves every operation of the protocol.
 */
public actor ServerClient {
    /// Events the server pushes (everything with an `ev` other than `hello`).
    public nonisolated let events: AsyncStream<JSONValue>
    /// Connection state, for the line the contacts screen shows.
    public nonisolated let states: AsyncStream<ConnState>

    private static let connectTimeout: TimeInterval = 20
    private static let callTimeout: TimeInterval = 20
    private static let pingInterval: TimeInterval = 30

    private let url: URL
    private let session: URLSession
    private nonisolated let eventsIn: AsyncStream<JSONValue>.Continuation
    private nonisolated let statesIn: AsyncStream<ConnState>.Continuation

    private var socket: URLSessionWebSocketTask?
    private var nextId = 1
    private var pending: [Int: CheckedContinuation<JSONValue, Error>] = [:]
    private var waitingForHello: [CheckedContinuation<Void, Error>] = []
    private var waitingForClose: [CheckedContinuation<Void, Never>] = []

    public private(set) var state: ConnState = .disconnected {
        didSet { if state != oldValue { statesIn.yield(state) } }
    }

    public init(url: String) {
        self.url = URL(string: url)!
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = Self.connectTimeout
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)

        var eventsOut: AsyncStream<JSONValue>.Continuation!
        events = AsyncStream(bufferingPolicy: .bufferingNewest(64)) { eventsOut = $0 }
        eventsIn = eventsOut

        var statesOut: AsyncStream<ConnState>.Continuation!
        states = AsyncStream(bufferingPolicy: .bufferingNewest(16)) { statesOut = $0 }
        statesIn = statesOut
    }

    /// Opens the socket if needed and returns once the server's `hello` has arrived.
    public func connect() async throws {
        if state == .connected { return }
        if state == .disconnected {
            state = .connecting
            let task = session.webSocketTask(with: url)
            socket = task
            task.resume()
            Task { await self.receiveLoop(task) }
            Task { await self.pingLoop(task) }
        }
        let watchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.connectTimeout * 1_000_000_000))
            await self?.helloTimedOut()
        }
        defer { watchdog.cancel() }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            if state == .connected { c.resume() } else { waitingForHello.append(c) }
        }
    }

    public func disconnect() {
        guard let socket else { return }
        socket.cancel(with: .goingAway, reason: nil)
        closed(socket, ServerError("disconnected"))
    }

    /// One request and its reply. Throws `ServerError` with the server's code when `ok` is false.
    @discardableResult
    public func call(_ op: String, _ params: [String: JSONValue] = [:]) async throws -> JSONValue {
        try await connect()
        let id = nextId
        nextId += 1
        var fields = params
        fields["id"] = .number(Double(id))
        fields["op"] = .string(op)
        let text = String(decoding: JSONValue.object(fields).encoded(), as: UTF8.self)

        let watchdog = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.callTimeout * 1_000_000_000))
            await self?.fail(id, ServerError("timeout"))
        }
        defer { watchdog.cancel() }

        return try await withCheckedThrowingContinuation { (c: CheckedContinuation<JSONValue, Error>) in
            pending[id] = c
            guard let socket, state == .connected else {
                fail(id, ServerError("disconnected"))
                return
            }
            socket.send(.string(text)) { [weak self] error in
                guard let error else { return }
                Task { await self?.fail(id, error) }
            }
        }
    }

    /// Returns when the socket goes down, so the session loop can reconnect.
    public func waitUntilDisconnected() async {
        if state == .disconnected { return }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            waitingForClose.append(c)
        }
    }

    // ---------------------------------------------------------------- socket

    private func receiveLoop(_ task: URLSessionWebSocketTask) async {
        while true {
            do {
                let message = try await task.receive()
                guard socket === task else { return }
                switch message {
                case .string(let text): handle(text)
                case .data(let data): handle(String(decoding: data, as: UTF8.self))
                @unknown default: break
                }
            } catch {
                closed(task, error)
                return
            }
        }
    }

    /// The server only pings during an answered call, so keep the connection alive from this side.
    private func pingLoop(_ task: URLSessionWebSocketTask) async {
        while socket === task {
            try? await Task.sleep(nanoseconds: UInt64(Self.pingInterval * 1_000_000_000))
            guard socket === task else { return }
            task.sendPing { _ in }
        }
    }

    private func handle(_ text: String) {
        guard let message = JSONValue.parse(text) else { return }
        if let ev = message.string("ev") {
            if ev == "hello" {
                state = .connected
                let waiting = waitingForHello
                waitingForHello = []
                waiting.forEach { $0.resume() }
            } else {
                eventsIn.yield(message)
            }
        } else if let id = message["id"]?.intValue {
            deliver(id, message)
        }
    }

    private func deliver(_ id: Int, _ reply: JSONValue) {
        guard let waiting = pending.removeValue(forKey: id) else { return }
        if reply["ok"]?.boolValue == true {
            waiting.resume(returning: reply)
        } else {
            waiting.resume(throwing: ServerError(reply.string("error") ?? "internal"))
        }
    }

    private func fail(_ id: Int, _ error: Error) {
        pending.removeValue(forKey: id)?.resume(throwing: error)
    }

    private func helloTimedOut() {
        guard state != .connected, let socket else { return }
        socket.cancel(with: .goingAway, reason: nil)
        closed(socket, ServerError("timeout"))
    }

    private func closed(_ task: URLSessionWebSocketTask, _ error: Error) {
        guard socket === task else { return }
        socket = nil
        state = .disconnected

        let hello = waitingForHello
        waitingForHello = []
        hello.forEach { $0.resume(throwing: error) }

        let calls = pending
        pending = [:]
        calls.values.forEach { $0.resume(throwing: error) }

        let closers = waitingForClose
        waitingForClose = []
        closers.forEach { $0.resume() }
    }
}
