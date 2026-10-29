import SwiftUI

/// The teal of the logo and of the Android app (`ui/theme/Theme.kt`, `docs/logo`). The full palette
/// moves into an asset catalogue when the real screens arrive.
extension Color {
    static let kalloqLight = Color(red: 0x0F / 255, green: 0x76 / 255, blue: 0x6E / 255)
    static let kalloqDark = Color(red: 0x5E / 255, green: 0xEA / 255, blue: 0xD4 / 255)

    static func kalloq(_ scheme: ColorScheme) -> Color { scheme == .dark ? kalloqDark : kalloqLight }
}
