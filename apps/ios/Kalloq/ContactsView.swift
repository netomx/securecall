import SwiftUI
import KalloqCore

/// The main screen: contact requests, contacts, and requests we sent.
struct ContactsView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.colorScheme) private var scheme

    @State private var adding = false
    @State private var settings = false

    var body: some View {
        NavigationStack {
            List {
                // Only when something is wrong: a permanent "connected" line is noise.
                if app.conn != .connected {
                    Section {
                        HStack(spacing: 8) {
                            if app.conn == .connecting {
                                ProgressView().controlSize(.small)
                            } else {
                                Image(systemName: "wifi.exclamationmark").foregroundStyle(.orange)
                            }
                            ConnectionLabel(state: app.conn)
                        }
                    }
                }

                if !app.incoming.isEmpty {
                    Section("Solicitudes de contacto") {
                        ForEach(app.incoming, id: \.self) { id in
                            HStack {
                                Text(String(id.prefix(8)))
                                    .font(.system(.body, design: .monospaced))
                                Spacer()
                                Button("Aceptar") { app.respond(id, accept: true) }
                                    .buttonStyle(.borderless)
                                    .tint(Color.kalloq(scheme))
                                Button("Rechazar") { app.respond(id, accept: false) }
                                    .buttonStyle(.borderless)
                                    .tint(.secondary)
                            }
                        }
                    }
                }

                Section("Contactos") {
                    if app.friends.isEmpty {
                        Text("Aún no tienes contactos. Comparte tu código QR o escanea el de otra persona.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(app.friends) { friend in
                        NavigationLink(value: friend.id) {
                            ContactRow(friend: friend)
                        }
                    }
                }

                if !app.outgoing.isEmpty {
                    Section("Solicitudes enviadas") {
                        ForEach(app.outgoing, id: \.self) { id in
                            HStack {
                                Text(String(id.prefix(8)))
                                    .font(.system(.body, design: .monospaced))
                                Spacer()
                                Button("Cancelar") { app.removeFriend(id) }
                                    .buttonStyle(.borderless)
                                    .tint(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Kalloq")
            .navigationDestination(for: String.self) { id in
                ContactDetailView(id: id)
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { settings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Ajustes")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { adding = true } label: { Image(systemName: "person.badge.plus") }
                        .accessibilityLabel("Agregar contacto")
                }
            }
            .sheet(isPresented: $adding) { AddContactView() }
            .sheet(isPresented: $settings) { SettingsView() }
        }
    }
}

private struct ContactRow: View {
    @Environment(\.colorScheme) private var scheme
    let friend: Friend

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(friend.displayName)
            if friend.keyChanged {
                Text("⚠ La llave cambió")
                    .font(.caption)
                    .foregroundStyle(.red)
            } else if friend.ik == nil {
                Text("Sin llave todavía")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

struct ConnectionLabel: View {
    let state: ConnState

    var body: some View {
        switch state {
        case .connected:
            Text("Conectado").font(.caption).foregroundStyle(.secondary)
        case .connecting:
            Text("Conectando…").font(.caption).foregroundStyle(.secondary)
        case .disconnected:
            Text("Sin conexión").font(.caption).foregroundStyle(.orange)
        }
    }
}
