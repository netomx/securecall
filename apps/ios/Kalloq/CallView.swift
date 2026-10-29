import SwiftUI

/// The call itself. The security code is the point of the screen: if both phones read the same six
/// digits out loud, nobody is in the middle (`docs/ARQUITECTURA.md` §4.4).
struct CallView: View {
    @EnvironmentObject private var app: AppController
    @Environment(\.colorScheme) private var scheme

    let call: CallUi

    private var name: String { app.displayName(call.peerId) }

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            VStack(spacing: 10) {
                Image(systemName: call.verified ? "checkmark.seal.fill" : "lock.fill")
                    .font(.system(size: 30))
                    .foregroundStyle(Color.kalloq(scheme))
                Text(name)
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                Text(status)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if let code = call.code, call.phase != .ended {
                VStack(spacing: 6) {
                    Text("Código de seguridad")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Text(code)
                        .font(.system(.title, design: .monospaced).weight(.semibold))
                    Text("Dilo en voz alta: si coincide en los dos teléfonos, nadie está en medio.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 36)
                }
                .padding(.top, 34)
            }

            Spacer()

            if call.onHold {
                Button("Reanudar") { app.calls.resume() }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.kalloq(scheme))
                    .padding(.bottom, 22)
            }

            controls
                .padding(.bottom, 44)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }

    private var status: String {
        if call.phase == .ended { return endedText }
        if call.onHold { return "En espera por otra llamada" }
        if call.peerOnHold { return "\(name) está en otra llamada" }
        if call.reconnecting { return "Reconectando…" }
        switch call.phase {
        case .calling: return "Llamando…"
        case .incoming: return "Llamada entrante"
        case .connecting: return "Conectando…"
        case .active: return call.verified ? "Cifrada y verificada" : "Cifrada de extremo a extremo"
        case .ended: return endedText
        }
    }

    private var endedText: String {
        switch call.endReason {
        case "hangup", nil: return "Llamada terminada"
        case "rejected": return "Llamada rechazada"
        case "timeout": return "Sin respuesta"
        case "busy": return "Está en otra llamada"
        case "offline": return "No está disponible"
        case "disconnected": return "Se perdió la conexión"
        case "e2e_failed": return "No se pudo cifrar la llamada"
        case "no_microphone": return "Sin permiso para el micrófono"
        case "failed": return "No se pudo conectar el audio"
        default: return "Llamada terminada"
        }
    }

    @ViewBuilder
    private var controls: some View {
        if call.phase == .ended {
            EmptyView()
        } else if call.phase == .incoming {
            HStack(spacing: 70) {
                CallButton(icon: "phone.down.fill", tint: .red, label: "Rechazar") {
                    app.calls.rejectCurrent()
                }
                CallButton(icon: "phone.fill", tint: .green, label: "Contestar") {
                    app.calls.answerFromUI()
                }
            }
        } else {
            VStack(spacing: 34) {
                HStack(spacing: 54) {
                    CallButton(icon: call.muted ? "mic.slash.fill" : "mic.fill",
                               tint: call.muted ? .secondary : Color.kalloq(scheme),
                               label: call.muted ? "Activar micrófono" : "Silenciar",
                               small: true) {
                        app.calls.toggleMute()
                    }
                    CallButton(icon: call.speaker ? "speaker.wave.3.fill" : "speaker.fill",
                               tint: call.speaker ? Color.kalloq(scheme) : .secondary,
                               label: "Altavoz",
                               small: true) {
                        app.calls.toggleSpeaker()
                    }
                }
                CallButton(icon: "phone.down.fill", tint: .red, label: "Colgar") {
                    app.calls.hangUp()
                }
            }
        }
    }
}

private struct CallButton: View {
    let icon: String
    let tint: Color
    let label: String
    var small = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: small ? 22 : 28))
                .foregroundStyle(small ? tint : .white)
                .frame(width: small ? 58 : 72, height: small ? 58 : 72)
                .background(small ? AnyShapeStyle(.quaternary) : AnyShapeStyle(tint), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
