import SwiftUI

/**
 * The reference vectors run inside the app. A green list means the end-to-end layer compiled into
 * this build agrees byte for byte with Android and with `tools/kalloq_crypto.py`, which is what
 * lets a call between the two phones negotiate the same keys. The full set lives in KalloqCore's
 * tests; this is here so it can be checked on a device too.
 */
struct CryptoCheckView: View {
    @Environment(\.colorScheme) private var scheme
    private let rows = SelfCheck.run()

    private var allOK: Bool { rows.allSatisfy(\.ok) }

    var body: some View {
        List {
            Section {
                ForEach(rows) { row in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Image(systemName: row.ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                            .foregroundStyle(row.ok ? Color.kalloq(scheme) : .red)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name)
                            Text(row.got)
                                .font(.system(.caption2, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            if !row.ok {
                                Text("esperado: \(row.expected)")
                                    .font(.system(.caption2, design: .monospaced))
                                    .foregroundStyle(.red)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                            }
                        }
                    }
                }
            } footer: {
                Text(allOK
                     ? "Coinciden con los de Android y tools/kalloq_crypto.py."
                     : "Algún valor no coincide con Android. Revisa KalloqCore antes de seguir.")
            }
        }
        .navigationTitle("Comprobación de cifrado")
        .navigationBarTitleDisplayMode(.inline)
    }
}
