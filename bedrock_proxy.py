#!/usr/bin/env python3
"""
Minecraft Bedrock Proxy using RakNet
Single-file, multi-connection proxy that bridges client <-> server connections.
"""

import argparse
import logging
import sys
import threading
import time
from dataclasses import dataclass
from typing import Dict, Optional, Tuple

import raknet

# RakNet Message IDs
ID_UNCONNECTED_PING = 0x01
ID_UNCONNECTED_PING_OPEN_CONNECTIONS = 0x02
ID_UNCONNECTED_PONG = 0x1C
ID_OPEN_CONNECTION_REQUEST_1 = 0x05
ID_OPEN_CONNECTION_REPLY_1 = 0x06
ID_OPEN_CONNECTION_REQUEST_2 = 0x07
ID_OPEN_CONNECTION_REPLY_2 = 0x08
ID_CONNECTION_REQUEST = 0x09
ID_CONNECTION_REQUEST_ACCEPTED = 0x10
ID_CONNECTION_ATTEMPT_FAILED = 0x11
ID_NEW_INCOMING_CONNECTION = 0x13
ID_DISCONNECTION_NOTIFICATION = 0x15
ID_CONNECTION_LOST = 0x16
ID_CONNECTED_PING = 0x00
ID_CONNECTED_PONG = 0x03
ID_ADVERTISE_SYSTEM = 0x11

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(threadName)s: %(message)s',
    handlers=[logging.StreamHandler(sys.stdout)]
)
logger = logging.getLogger(__name__)


@dataclass
class ClientConnection:
    """Represents a client connected to the proxy."""
    address: raknet.SystemAddress
    server_peer: Optional[raknet.RakPeer] = None
    server_address: Optional[raknet.SystemAddress] = None
    connecting: bool = False
    connected: bool = False
    disconnecting: bool = False


class BedrockProxy:
    """Main proxy class handling multiple client connections."""

    def __init__(
        self,
        listen_host: str = "0.0.0.0",
        listen_port: int = 19132,
        target_host: str = "127.0.0.1",
        target_port: int = 19133,
        max_connections: int = 100,
        motd: str = "Bedrock Proxy",
    ):
        self.listen_host = listen_host
        self.listen_port = listen_port
        self.target_host = target_host
        self.target_port = target_port
        self.max_connections = max_connections
        self.motd = motd

        self.listener: Optional[raknet.RakPeer] = None
        self.clients: Dict[Tuple[str, int], ClientConnection] = {}
        self.clients_lock = threading.Lock()
        self.running = False
        
        # Threads
        self.listener_thread: Optional[threading.Thread] = None
        self.bridge_threads: Dict[Tuple[str, int], threading.Thread] = {}

    def start(self):
        logger.info(f"Starting proxy on {self.listen_host}:{self.listen_port}")
        logger.info(f"Forwarding to {self.target_host}:{self.target_port}")

        self.listener = raknet.RakPeer()
        self.listener.startup(self.listen_host, self.listen_port, max_connections=self.max_connections)
        self.listener.max_incoming_connections = self.max_connections

        # Set offline ping response (MOTD for server list)
        self._set_offline_ping_response()

        self.running = True
        self.listener_thread = threading.Thread(target=self._listener_loop, daemon=True, name="Listener")
        self.listener_thread.start()

        logger.info("Proxy started successfully")

    def _set_offline_ping_response(self):
        """Set the MOTD that appears in server browser."""
        motd_bytes = self.motd.encode('utf-8')
        protocol = b"589"
        version = b"1.20.0"
        players = b"0"
        max_players = b"100"
        server_id = b"123456789012345678"
        gamemode = b"Survival"
        port = str(self.listen_port).encode()

        response = b"MCPE;" + motd_bytes + b";" + protocol + b";" + version + b";" + \
                   players + b";" + max_players + b";" + server_id + b";" + \
                   gamemode + b";" + port + b";" + port + b";;"
        self.listener.offline_ping_response = response

    def _listener_loop(self):
        """Main listener loop - receives packets and dispatches to clients."""
        while self.running:
            packet = self.listener.receive()
            if packet:
                self._handle_listener_packet(packet)
            time.sleep(0.001)

    def _handle_listener_packet(self, packet: raknet.Packet):
        """Handle packet received on listener."""
        addr = packet.system_address
        addr_key = (addr.host, addr.port)
        data = packet.data

        if not data:
            return

        packet_id = data[0]

        if packet_id == ID_NEW_INCOMING_CONNECTION:
            self._handle_new_client(addr)

        elif packet_id == ID_DISCONNECTION_NOTIFICATION or packet_id == ID_CONNECTION_LOST:
            self._handle_client_disconnect(addr_key)

        else:
            # Forward to client's server connection if exists
            with self.clients_lock:
                client = self.clients.get(addr_key)
                if client and client.connected and client.server_peer and client.server_address:
                    try:
                        client.server_peer.send(
                            data,
                            raknet.PacketPriority.HIGH_PRIORITY,
                            raknet.PacketReliability.RELIABLE_ORDERED,
                            0,
                            client.server_address
                        )
                    except Exception as e:
                        logger.error(f"Error forwarding to server: {e}")
                        self._schedule_disconnect(addr_key)

    def _handle_new_client(self, addr: raknet.SystemAddress):
        """Handle new incoming client connection."""
        addr_key = (addr.host, addr.port)
        logger.info(f"New client connection from {addr.host}:{addr.port}")

        with self.clients_lock:
            if addr_key in self.clients:
                return
            client = ClientConnection(address=addr, connecting=True)
            self.clients[addr_key] = client

        # Connect to target server in background
        threading.Thread(target=self._connect_to_target, args=(addr_key,), daemon=True).start()

    def _connect_to_target(self, addr_key: Tuple[str, int]):
        """Connect to target server for a client."""
        with self.clients_lock:
            client = self.clients.get(addr_key)
            if not client:
                return

        server_peer = raknet.RakPeer()
        server_peer.startup("0.0.0.0", 0, max_connections=1)
        server_peer.max_incoming_connections = 1

        server_peer.connect(self.target_host, self.target_port)

        # Wait for connection
        server_addr = None
        for _ in range(100):  # 5 second timeout
            if not self.running:
                break
            spkt = server_peer.receive()
            if spkt:
                if spkt.data[0] == ID_CONNECTION_REQUEST_ACCEPTED:
                    server_addr = spkt.system_address
                    logger.info(f"Connected to target server {server_addr.host}:{server_addr.port} for {addr_key[0]}:{addr_key[1]}")
                    break
                elif spkt.data[0] == ID_CONNECTION_ATTEMPT_FAILED:
                    logger.error(f"Failed to connect to target server for {addr_key[0]}:{addr_key[1]}")
                    break
            time.sleep(0.05)

        with self.clients_lock:
            client = self.clients.get(addr_key)
            if not client or not self.running:
                server_peer.shutdown(1.0)
                return

            if server_addr:
                client.server_peer = server_peer
                client.server_address = server_addr
                client.connecting = False
                client.connected = True
                
                # Start bridge thread for server->client direction
                bridge_thread = threading.Thread(
                    target=self._server_to_client_loop,
                    args=(addr_key,),
                    daemon=True,
                    name=f"Bridge-{addr_key[0]}:{addr_key[1]}"
                )
                self.bridge_threads[addr_key] = bridge_thread
                bridge_thread.start()
                logger.info(f"Bridge established for {addr_key[0]}:{addr_key[1]}")
            else:
                # Connection failed, notify client
                self.listener.send(
                    bytes([ID_CONNECTION_ATTEMPT_FAILED]),
                    raknet.PacketPriority.IMMEDIATE_PRIORITY,
                    raknet.PacketReliability.RELIABLE,
                    0,
                    client.address
                )
                self._remove_client(addr_key)
                server_peer.shutdown(1.0)

    def _server_to_client_loop(self, addr_key: Tuple[str, int]):
        """Bridge loop: receive from server, send to client."""
        while self.running:
            with self.clients_lock:
                client = self.clients.get(addr_key)
                if not client or not client.connected or client.disconnecting:
                    break
                server_peer = client.server_peer
                server_address = client.server_address
                client_address = client.address

            if not server_peer or not server_address:
                time.sleep(0.01)
                continue

            packet = server_peer.receive()
            if packet:
                if packet.system_address == server_address:
                    if packet.data[0] in (ID_DISCONNECTION_NOTIFICATION, ID_CONNECTION_LOST):
                        logger.info(f"Server disconnected for {addr_key[0]}:{addr_key[1]}")
                        self._schedule_disconnect(addr_key)
                        break
                    try:
                        self.listener.send(
                            packet.data,
                            raknet.PacketPriority.HIGH_PRIORITY,
                            raknet.PacketReliability.RELIABLE_ORDERED,
                            0,
                            client_address
                        )
                    except Exception as e:
                        logger.error(f"Error sending to client: {e}")
                        self._schedule_disconnect(addr_key)
                        break
            time.sleep(0.001)

    def _handle_client_disconnect(self, addr_key: Tuple[str, int]):
        """Handle client disconnect."""
        logger.info(f"Client disconnected: {addr_key[0]}:{addr_key[1]}")
        self._remove_client(addr_key)

    def _schedule_disconnect(self, addr_key: Tuple[str, int]):
        """Schedule a client for disconnection."""
        with self.clients_lock:
            client = self.clients.get(addr_key)
            if client and not client.disconnecting:
                client.disconnecting = True

    def _remove_client(self, addr_key: Tuple[str, int]):
        """Remove client and clean up."""
        with self.clients_lock:
            client = self.clients.pop(addr_key, None)
        
        if client:
            if client.server_peer:
                try:
                    client.server_peer.shutdown(1.0)
                except Exception:
                    pass
        
        # Bridge thread will exit on its own when it sees client removed

    def stop(self):
        logger.info("Stopping proxy...")
        self.running = False

        with self.clients_lock:
            for client in self.clients.values():
                if client.server_peer:
                    try:
                        client.server_peer.shutdown(1.0)
                    except Exception:
                        pass
            self.clients.clear()

        if self.listener:
            self.listener.shutdown(1.0)

        if self.listener_thread:
            self.listener_thread.join(timeout=2.0)

        for thread in self.bridge_threads.values():
            thread.join(timeout=1.0)

        logger.info("Proxy stopped")


def main():
    parser = argparse.ArgumentParser(description="Minecraft Bedrock Proxy")
    parser.add_argument("--listen-host", default="0.0.0.0", help="Listen host")
    parser.add_argument("--listen-port", type=int, default=19132, help="Listen port")
    parser.add_argument("--target-host", default="127.0.0.1", help="Target server host")
    parser.add_argument("--target-port", type=int, default=19133, help="Target server port")
    parser.add_argument("--max-connections", type=int, default=100, help="Max connections")
    parser.add_argument("--motd", default="Bedrock Proxy", help="Server MOTD")
    parser.add_argument("--debug", action="store_true", help="Enable debug logging")

    args = parser.parse_args()

    if args.debug:
        logger.setLevel(logging.DEBUG)

    proxy = BedrockProxy(
        listen_host=args.listen_host,
        listen_port=args.listen_port,
        target_host=args.target_host,
        target_port=args.target_port,
        max_connections=args.max_connections,
        motd=args.motd,
    )

    try:
        proxy.start()
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        logger.info("Interrupted")
    finally:
        proxy.stop()


if __name__ == "__main__":
    main()