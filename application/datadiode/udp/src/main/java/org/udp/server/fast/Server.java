package org.udp.server.fast;


import com.google.common.primitives.Ints;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Created by marcel on 06-12-15.
 */

public class Server {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(Server.class);

    static int serverPort = 9999;
    static int packetSize = 1300;

    static byte[] b = new byte[packetSize];
    static byte[] indexBytes = new byte[4];
    static int oldIndex = -1;

    Server() throws IOException {
        DatagramChannel channel = DatagramChannel.open();
        DatagramSocket socket = channel.socket();
        socket.setReceiveBufferSize(8192 * 128); // THIS!

        SocketAddress address = new InetSocketAddress(serverPort);
        socket.bind(address);

        byte[] message = new byte[packetSize];
        AtomicInteger atomicInteger = new AtomicInteger(0);

        ServerThread serverThread = new ServerThread(atomicInteger);
        serverThread.start();
        log.info("receiving: " + serverPort + " " + socket);

        try {


            while (true) {

                DatagramPacket packet = new DatagramPacket(message, message.length);
                socket.receive(packet);
                atomicInteger.incrementAndGet();

                // log.info("["+atomicInteger.get()+"] Server received "+ +packet.getLength());

                byte[] m = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());

                for (int i = 0; i < 4; i++) {
                    indexBytes[i] = m[i];
                }
                int index = Ints.fromByteArray(m);
/**
                if (oldIndex != -1 && index != 0 && index != (oldIndex + 1)) {
                    log.warn("packet loss: " + index + ", " + oldIndex);
                }
*/
                oldIndex = index;
                // log.info("Server received "+ +b.length+": " + new String(Base64.encodeBase64(b)));

            }
        } finally {
            log.info("received: " + atomicInteger.get());
        }
    }

    public static void main(String[] args) throws Exception {
        Server server = new Server();
    }


    class ServerThread extends Thread {

        private final org.slf4j.Logger log = LoggerFactory.getLogger(ServerThread.class);

        AtomicInteger atomicInteger;
        int prev = 0;
        int total = 0;

        public ServerThread(AtomicInteger atomicInteger) throws SocketException {
            this.atomicInteger = atomicInteger;
        }

        public void run() {
            while (true) {
                int now = atomicInteger.get();
                int diff = (now - prev);

                if(diff > 0) {
                    total = total + diff;
                    log.info("packets: " + atomicInteger.get() + " (" + diff + "), total(" + total + ")");
                    prev = now;
                } else {
                    total = 0;
                }

                try {
                    Thread.sleep(7500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }


}
