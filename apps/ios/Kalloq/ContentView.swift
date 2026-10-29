import SwiftUI

/**
 * First screen of the iOS app. There is no account or call yet (see `docs/IOS_ARRANQUE.md`), so what
 * it shows is the one thing already finished: the end-to-end layer, checked against the same vectors
 * the Android app passes. It is a build-health screen, not a design for the final app.
 */
struct ContentView: View {
    @Environment(\.colorScheme) private var scheme
    private let rows = SelfCheck.run()

    private var allOK: Bool { rows.allSatisfy(\.ok) }

    var body: some View {
        NavigationStack {
            List {
                Section { header }

                Section {
                    ForEach(rows) { RowView(row: $0, brand: Color.kalloq(scheme)) }
                } header: {
                    Text("Vectores de referencia")
                } footer: {
                    Text(allOK
                         ? "Coinciden con los de Android y tools/kalloq_crypto.py: una llamada entre un iPhone y un Android podría negociar las mismas llaves."
                         : "Algún valor no coincide con Android. Revisa KalloqCore antes de seguir.")
                }

                Section("Siguiente") {
                    Label("Cliente del protocolo y pantallas de cuenta", systemImage: "person.crop.circle")
                    Label("Llamada con WebRTC y CallKit", systemImage: "phone.connection")
                    Label("Push VoIP por APNs (PushKit)", systemImage: "bell.badge")
                }
                .foregroundStyle(.secondary)
            }
            .navigationTitle("Kalloq")
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            Image(systemName: "lock.shield.fill")
                .font(.system(size: 34))
                .foregroundStyle(Color.kalloq(scheme))
            VStack(alignment: .leading, spacing: 3) {
                Text("Llamadas de voz cifradas de extremo a extremo")
                    .font(.headline)
                Text(allOK ? "Capa de cifrado lista" : "Capa de cifrado con fallas")
                    .font(.subheadline)
                    .foregroundStyle(allOK ? Color.kalloq(scheme) : .red)
            }
        }
        .padding(.vertical, 6)
    }
}

private struct RowView: View {
    let row: SelfCheck.Row
    let brand: Color

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: row.ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(row.ok ? brand : .red)
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
}

#Preview {
    ContentView()
}
