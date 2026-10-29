import os

/// Diagnostics without personal data: no tokens, IDs or names, only what happened and why.
/// Readable from a Mac with Console.app (subsystem com.cim.securecall) or `devicectl ... --console`.
enum Log {
    private static let logger = Logger(subsystem: "com.cim.securecall", category: "app")

    static func info(_ message: String) {
        logger.info("\(message, privacy: .public)")
        #if DEBUG
        print("kalloq:", message)
        #endif
    }
}
