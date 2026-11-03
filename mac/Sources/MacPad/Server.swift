import Foundation
import Network
import os

/// Accepts tablet connections: loopback for USB (via `adb reverse`), and Bonjour-advertised TCP for Wi-Fi.
final class Server {
    private let queue: DispatchQueue
    private let onConnection: (NWConnection, Session.Transport) -> Void
    /// A problem to show in the menu, or nil once every listener is up again.
    private let onProblem: (String?) -> Void
    private let log = Logger(subsystem: "com.azhardhiaulhaq.macpad", category: "server")
    private var listeners: [UInt16: NWListener] = [:]
    private var failed: Set<UInt16> = []

    init(queue: DispatchQueue,
         onConnection: @escaping (NWConnection, Session.Transport) -> Void,
         onProblem: @escaping (String?) -> Void) {
        self.queue = queue
        self.onConnection = onConnection
        self.onProblem = onProblem
    }

    func start() {
        open(.usb, port: Ports.usb)
        open(.wifi, port: Ports.wifi)
    }

    /// Opens one listener; a failed one (port briefly taken, network change after sleep) is
    /// reopened every few seconds rather than leaving that transport dead until relaunch.
    private func open(_ transport: Session.Transport, port: UInt16) {
        let listener: NWListener
        do {
            if transport == .usb {
                listener = try NWListener(using: Self.parameters(loopbackPort: port))
            } else {
                listener = try NWListener(using: Self.parameters(loopbackPort: nil), on: NWEndpoint.Port(rawValue: port)!)
                let txt = NWTXTRecord(["id": PairingStore.shared.serverId.hex, "v": "1"])
                listener.service = NWListener.Service(name: nil, type: "_macpad._tcp", domain: nil, txtRecord: txt)
            }
        } catch {
            fail(transport, port: port, error: error)
            return
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.onConnection(connection, transport)
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self, let listener, self.listeners[port] === listener else { return }
            switch state {
            case .ready:
                if self.failed.remove(port) != nil {
                    self.log.info("listener \(port) is back")
                    if self.failed.isEmpty { self.onProblem(nil) }
                }
            case .failed(let error):
                listener.cancel()
                self.listeners[port] = nil
                self.fail(transport, port: port, error: error)
            default:
                break
            }
        }
        listeners[port] = listener
        listener.start(queue: queue)
    }

    private func fail(_ transport: Session.Transport, port: UInt16, error: Error) {
        log.error("listener \(port) failed: \(String(describing: error), privacy: .public)")
        failed.insert(port)
        onProblem("Port \(port) is unavailable (\(error.localizedDescription)). MacPad keeps retrying; quit other copies of MacPad if this stays.")
        queue.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self, self.listeners[port] == nil else { return }
            self.open(transport, port: port)
        }
    }

    private static func parameters(loopbackPort: UInt16?) -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let params = NWParameters(tls: nil, tcp: tcp)
        params.allowLocalEndpointReuse = true
        if let loopbackPort {
            params.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: NWEndpoint.Port(rawValue: loopbackPort)!)
        }
        return params
    }
}
