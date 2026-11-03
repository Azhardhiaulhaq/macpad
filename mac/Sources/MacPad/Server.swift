import Foundation
import Network
import os

/// Accepts tablet connections: loopback for USB (via `adb reverse`), and Bonjour-advertised TCP for Wi-Fi.
final class Server {
    private let queue: DispatchQueue
    private let onConnection: (NWConnection, Session.Transport) -> Void
    private let onError: (String) -> Void
    private let log = Logger(subsystem: "com.azhardhiaulhaq.macpad", category: "server")
    private var listeners: [NWListener] = []

    init(queue: DispatchQueue,
         onConnection: @escaping (NWConnection, Session.Transport) -> Void,
         onError: @escaping (String) -> Void) {
        self.queue = queue
        self.onConnection = onConnection
        self.onError = onError
    }

    func start() {
        do {
            let usb = try NWListener(using: Self.parameters(loopbackPort: Ports.usb))
            add(usb, transport: .usb, port: Ports.usb)

            let wifi = try NWListener(using: Self.parameters(loopbackPort: nil), on: NWEndpoint.Port(rawValue: Ports.wifi)!)
            let txt = NWTXTRecord(["id": PairingStore.shared.serverId.hex, "v": "1"])
            wifi.service = NWListener.Service(name: nil, type: "_macpad._tcp", domain: nil, txtRecord: txt)
            add(wifi, transport: .wifi, port: Ports.wifi)
        } catch {
            onError("MacPad could not open its network ports: \(error.localizedDescription)")
        }
    }

    private func add(_ listener: NWListener, transport: Session.Transport, port: UInt16) {
        listener.newConnectionHandler = { [weak self] connection in
            self?.onConnection(connection, transport)
        }
        listener.stateUpdateHandler = { [weak self] state in
            if case .failed(let error) = state {
                self?.log.error("listener \(port) failed: \(String(describing: error), privacy: .public)")
                self?.onError("Port \(port) is unavailable (\(error.localizedDescription)). Quit other copies of MacPad and reopen it.")
            }
        }
        listener.start(queue: queue)
        listeners.append(listener)
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
