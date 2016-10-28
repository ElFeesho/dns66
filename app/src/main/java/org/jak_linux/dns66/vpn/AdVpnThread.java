/* Copyright (C) 2016 Julian Andres Klode <jak@jak-linux.org>
 *
 * Derived from AdBuster:
 * Copyright (C) 2016 Daniel Brodie <dbrodie@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package org.jak_linux.dns66.vpn;


import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import org.jak_linux.dns66.Configuration;
import org.jak_linux.dns66.FileHelper;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;


class AdVpnThread implements Runnable {

    interface VpnFileDescriptorProvider {
        ParcelFileDescriptor retrieve();
    }

    interface ConfigProvider {
        Configuration retrieveConfig();
    }

    interface SocketProtector {
        void protect(DatagramSocket socket);
    }

    interface DnsServerListProvider {
        class NoDnsServersException extends Throwable {
        }

        Collection<InetAddress> retrieveDnsServers() throws NoDnsServersException;
    }

    interface Notify {
        void run(int value);
    }

    interface BlockedHostProvider {
        Set<String> retrieveBlockedHosts() throws InterruptedException;
    }

    private static class VpnNetworkException extends Exception {
        VpnNetworkException(String s) {
            super(s);
        }

        VpnNetworkException(String s, Throwable t) {
            super(s, t);
        }
    }

    private static class TimedValue<T> {
        private final T value;
        private final long time;

        TimedValue(T value) {
            this.value = value;
            this.time = System.currentTimeMillis();
        }

        long ageSeconds() {
            return (System.currentTimeMillis() - time) / 1000;
        }

        T get() {
            return value;
        }
    }

    private static final String TAG = "AdVpnThread";
    private static final int MIN_RETRY_TIME = 5;
    private static final int MAX_RETRY_TIME = 2 * 60;
    /* Maximum number of responses we want to wait for */
    private static final int DNS_MAXIMUM_WAITING = 1024;
    private static final long DNS_TIMEOUT_SEC = 10;
    // Apps using DownloadManager that are known to be broken on Nougat when a VPN is active, see
    // issue #31 for further details.

    private final Notify notify;
    private final SocketProtector socketProtector;
    private final VpnFileDescriptorProvider vpnFileDescriptorProvider;
    /* Data to be written to the device */
    private final Queue<byte[]> deviceWrites = new LinkedList<>();
    // HashMap that keeps an upper limit of packets
    private final LinkedHashMap<DatagramSocket, TimedValue<IpV4Packet>> dnsIn = new LinkedHashMap<DatagramSocket, TimedValue<IpV4Packet>>() {
        @Override
        protected boolean removeEldestEntry(Entry<DatagramSocket, TimedValue<IpV4Packet>> eldest) {
            boolean timeout = eldest.getValue().ageSeconds() > DNS_TIMEOUT_SEC;
            boolean overflow = size() > DNS_MAXIMUM_WAITING;
            if (timeout || overflow) {
                Log.d(TAG, "removeEldestEntry: Removing entry due to reason timeout=" + timeout + ", overflow=" + overflow);
                eldest.getKey().close();
                return true;
            }
            return false;
        }
    };
    private Thread thread = null;
    private FileDescriptor mBlockFd = null;
    private FileDescriptor mInterruptFd = null;
    private Set<String> blockedHosts = new HashSet<>();
    private BlockedHostProvider blockedHostProvider;

    AdVpnThread(Notify notify, SocketProtector socketProtector, VpnFileDescriptorProvider vpnFileDescriptorProvider, BlockedHostProvider blockedHostProvider) {
        this.notify = notify;
        this.socketProtector = socketProtector;
        this.vpnFileDescriptorProvider = vpnFileDescriptorProvider;
        this.blockedHostProvider = blockedHostProvider;
    }

    void startThread() {
        Log.i(TAG, "Starting Vpn Thread");
        thread = new Thread(this, "AdVpnThread");
        thread.start();
        Log.i(TAG, "Vpn Thread started");
    }

    void stopThread() {
        Log.i(TAG, "Stopping Vpn Thread");
        if (thread == null) {
            return;
        }

        thread.interrupt();

        mInterruptFd = FileHelper.closeOrWarn(mInterruptFd, TAG, "stopThread: Could not close interruptFd");
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Log.w(TAG, "stopThread: Interrupted while joining thread", e);
        }
        if (thread.isAlive()) {
            Log.w(TAG, "stopThread: Could not kill VPN thread, it is still alive");
        } else {
            thread = null;
            Log.i(TAG, "Vpn Thread stopped");
        }
    }

    @Override
    public synchronized void run() {
        Log.i(TAG, "Starting");

        // Load the block list
        try {
            blockedHosts = blockedHostProvider.retrieveBlockedHosts();
        } catch (InterruptedException e) {
            return;
        }

        notify.run(VpnStatus.VPN_STATUS_STARTING);

        int retryTimeout = MIN_RETRY_TIME;
        // Try connecting the vpn continuously
        while (true) {
            try {
                // If the function returns, that means it was interrupted
                runVpn();

                Log.i(TAG, "Told to stop");
                break;
            } catch (InterruptedException e) {
                break;
            } catch (VpnNetworkException e) {
                // We want to filter out VpnNetworkException from out crash analytics as these
                // are exceptions that we expect to happen from network errors
                Log.w(TAG, "Network exception in vpn thread, ignoring and reconnecting", e);
                // If an exception was thrown, show to the user and try again
                notify.run(VpnStatus.VPN_STATUS_RECONNECTING_NETWORK_ERROR);
            } catch (Exception e) {
                Log.e(TAG, "Network exception in vpn thread, reconnecting", e);
                //ExceptionHandler.saveException(e, Thread.currentThread(), null);
                notify.run(VpnStatus.VPN_STATUS_RECONNECTING_NETWORK_ERROR);
            }

            // ...wait and try again
            Log.i(TAG, "Retrying to connect in " + retryTimeout + "seconds...");
            try {
                Thread.sleep((long) retryTimeout * 1000);
            } catch (InterruptedException e) {
                break;
            }

            if (retryTimeout < MAX_RETRY_TIME) {
                retryTimeout *= 2;
            }
        }

        notify.run(VpnStatus.VPN_STATUS_STOPPING);
        Log.i(TAG, "Exiting");
    }

    private void runVpn() throws InterruptedException, ErrnoException, IOException, VpnNetworkException {
        // Allocate the buffer for a single packet.
        byte[] packet = new byte[32767];

        // A pipe we can interrupt the poll() call with by closing the interruptFd end
        FileDescriptor[] pipes = Os.pipe();
        mInterruptFd = pipes[0];
        mBlockFd = pipes[1];

        // Authenticate and configure the virtual network interface.
        try (ParcelFileDescriptor pfd = vpnFileDescriptorProvider.retrieve()) {
            // Read and write views of the tun device
            FileInputStream inputStream = new FileInputStream(pfd.getFileDescriptor());
            FileOutputStream outFd = new FileOutputStream(pfd.getFileDescriptor());

            // Now we are connected. Set the flag and show the message.
            notify.run(VpnStatus.VPN_STATUS_RUNNING);

            // We keep forwarding packets till something goes wrong.
            while (doOne(inputStream, outFd, packet))
                ;
        } finally {
            mBlockFd = FileHelper.closeOrWarn(mBlockFd, TAG, "runVpn: Could not close blockFd");
        }
    }

    private boolean doOne(FileInputStream inputStream, FileOutputStream outFd, byte[] packet) throws IOException, ErrnoException, InterruptedException, VpnNetworkException {
        StructPollfd deviceFd = new StructPollfd();
        deviceFd.fd = inputStream.getFD();
        deviceFd.events = (short) OsConstants.POLLIN;
        StructPollfd blockFd = new StructPollfd();
        blockFd.fd = mBlockFd;
        blockFd.events = (short) (OsConstants.POLLHUP | OsConstants.POLLERR);

        if (!deviceWrites.isEmpty()) {
            deviceFd.events |= (short) OsConstants.POLLOUT;
        }

        DatagramSocket[] others = new DatagramSocket[dnsIn.size()];
        dnsIn.keySet().toArray(others);

        StructPollfd[] polls = new StructPollfd[2 + others.length];
        polls[0] = deviceFd;
        polls[1] = blockFd;
        for (int i = 0; i < others.length; i++) {
            StructPollfd pollFd = polls[2 + i] = new StructPollfd();
            pollFd.fd = ParcelFileDescriptor.fromDatagramSocket(others[i]).getFileDescriptor();
            pollFd.events = (short) OsConstants.POLLIN;
        }

        Log.d(TAG, "doOne: Polling " + polls.length + " file descriptors");
        FileHelper.poll(polls, -1);
        if (blockFd.revents != 0) {
            Log.i(TAG, "Told to stop VPN");
            return false;
        }
        // Need to do this before reading from the device, otherwise a new insertion there could
        // invalidate one of the sockets we want to read from either due to size or time out
        // constraints
        for (int i = 0; i < others.length; i++) {
            if ((polls[i + 2].revents & OsConstants.POLLIN) != 0) {
                DatagramSocket socket = others[i];
                IpV4Packet parsedPacket = dnsIn.get(socket).get();
                Log.d(TAG, "Read from DNS socket" + socket);
                dnsIn.remove(socket);
                handleRawDnsResponse(parsedPacket, socket);
                socket.close();
            }
        }
        if ((deviceFd.revents & OsConstants.POLLOUT) != 0) {
            Log.d(TAG, "Write to device");
            writeToDevice(outFd);
        }
        if ((deviceFd.revents & OsConstants.POLLIN) != 0) {
            Log.d(TAG, "Read from device");
            readPacketFromDevice(inputStream, packet);
        }

        return true;
    }

    private void writeToDevice(FileOutputStream outFd) throws VpnNetworkException {
        try {
            outFd.write(deviceWrites.poll());
        } catch (IOException e) {
            // TODO: Make this more specific, only for: "File descriptor closed"
            throw new VpnNetworkException("Outgoing VPN output stream closed");
        }
    }

    private void readPacketFromDevice(FileInputStream inputStream, byte[] packet) throws VpnNetworkException, SocketException {
        // Read the outgoing packet from the input stream.
        int length;

        try {
            length = inputStream.read(packet);
        } catch (IOException e) {
            throw new VpnNetworkException("Cannot read from device", e);
        }


        if (length == 0) {
            // TODO: Possibly change to exception
            Log.w(TAG, "Got empty packet!");
            return;
        }

        handleDnsRequest(Arrays.copyOfRange(packet, 0, length));
    }

    private void handleDnsRequest(byte[] packet) throws VpnNetworkException {

        IpV4Packet parsedPacket;
        try {
            parsedPacket = IpV4Packet.newPacket(packet, 0, packet.length);
        } catch (Exception e) {
            Log.i(TAG, "handleDnsRequest: Discarding invalid IPv4 packet", e);
            return;
        }

        if (!(parsedPacket.getPayload() instanceof UdpPacket)) {
            Log.i(TAG, "handleDnsRequest: Discarding unknown packet type " + parsedPacket.getPayload());
            return;
        }

        UdpPacket parsedUdp = (UdpPacket) parsedPacket.getPayload();
        byte[] dnsRawData = (parsedUdp).getPayload().getRawData();
        Message dnsMsg;
        try {
            dnsMsg = new Message(dnsRawData);
        } catch (IOException e) {
            Log.i(TAG, "handleDnsRequest: Discarding non-DNS or invalid packet", e);
            return;
        }
        if (dnsMsg.getQuestion() == null) {
            Log.i(TAG, "handleDnsRequest: Discarding DNS packet with no query " + dnsMsg);
            return;
        }
        String dnsQueryName = dnsMsg.getQuestion().getName().toString(true);

        if (!blockedHosts.contains(dnsQueryName.toLowerCase(Locale.ENGLISH))) {
            Log.i(TAG, "handleDnsRequest: DNS Name " + dnsQueryName + " Allowed, sending to " + parsedPacket.getHeader().getDstAddr());
            DatagramPacket outPacket = new DatagramPacket(dnsRawData, 0, dnsRawData.length, parsedPacket.getHeader().getDstAddr(), parsedUdp.getHeader().getDstPort().valueAsInt());
            DatagramSocket dnsSocket = null;
            try {
                // Packets to be sent to the real DNS server will need to be protected from the VPN
                dnsSocket = new DatagramSocket();

                socketProtector.protect(dnsSocket);

                dnsSocket.send(outPacket);

                dnsIn.put(dnsSocket, new TimedValue<>(parsedPacket));
            } catch (IOException e) {
                FileHelper.closeOrWarn(dnsSocket, TAG, "handleDnsRequest: Cannot close socket in error");
                if (e.getCause() instanceof ErrnoException) {
                    ErrnoException errnoExc = (ErrnoException) e.getCause();
                    if ((errnoExc.errno == OsConstants.ENETUNREACH) || (errnoExc.errno == OsConstants.EPERM)) {
                        throw new VpnNetworkException("Cannot send message:", e);
                    }
                }
                Log.w(TAG, "handleDnsRequest: Could not send packet to upstream", e);
            }
        } else {
            Log.i(TAG, "handleDnsRequest: DNS Name " + dnsQueryName + " Blocked!");
            dnsMsg.getHeader().setFlag(Flags.QR);
            dnsMsg.getHeader().setRcode(Rcode.NXDOMAIN);
            handleDnsResponse(parsedPacket, dnsMsg.toWire());
        }
    }

    private void handleRawDnsResponse(IpV4Packet parsedPacket, DatagramSocket dnsSocket) throws IOException {
        byte[] datagramData = new byte[1024];
        DatagramPacket replyPacket = new DatagramPacket(datagramData, datagramData.length);
        dnsSocket.receive(replyPacket);
        handleDnsResponse(parsedPacket, datagramData);
    }

    private void handleDnsResponse(IpV4Packet parsedPacket, byte[] response) {
        UdpPacket udpOutPacket = (UdpPacket) parsedPacket.getPayload();
        IpV4Packet ipOutPacket = new IpV4Packet.Builder(parsedPacket)
                .srcAddr(parsedPacket.getHeader().getDstAddr())
                .dstAddr(parsedPacket.getHeader().getSrcAddr())
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(
                        new UdpPacket.Builder(udpOutPacket)
                                .srcPort(udpOutPacket.getHeader().getDstPort())
                                .dstPort(udpOutPacket.getHeader().getSrcPort())
                                .srcAddr(parsedPacket.getHeader().getDstAddr())
                                .dstAddr(parsedPacket.getHeader().getSrcAddr())
                                .correctChecksumAtBuild(true)
                                .correctLengthAtBuild(true)
                                .payloadBuilder(
                                        new UnknownPacket.Builder()
                                                .rawData(response)
                                )
                ).build();

        deviceWrites.add(ipOutPacket.getRawData());
    }
}
