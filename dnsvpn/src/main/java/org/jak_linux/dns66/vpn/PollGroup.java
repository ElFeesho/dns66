package org.jak_linux.dns66.vpn;

import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Set;

class PollGroup {
    static class PollGroupException extends Throwable {
        PollGroupException(Exception reason) {
            super(reason);
        }
    }

    interface ReadySocketCallback {
        void ready(DatagramSocket socket) throws IOException;
    }

    interface ResponseCallback {
        void response(StructPollfd fd) throws SocketException, PollGroupException;
    }

    private final StructPollfd[] polls;
    private StructPollfd deviceFd;
    private StructPollfd blockFd;
    private DatagramSocket[] others;

    PollGroup(FileDescriptor inFd, FileDescriptor blockfd, Set<DatagramSocket> datagramSockets, boolean canWrite) {
        this.deviceFd = createPollFdForFileDescriptorWithEvents(inFd, (short) (OsConstants.POLLIN | (canWrite?OsConstants.POLLOUT:0)));
        this.blockFd = createPollFdForFileDescriptorWithEvents(blockfd, (short) (OsConstants.POLLHUP | OsConstants.POLLERR));

        this.others = new DatagramSocket[datagramSockets.size()];
        datagramSockets.toArray(others);

        this.polls = new StructPollfd[2 + others.length];
        polls[0] = deviceFd;
        polls[1] = blockFd;

        for (int i = 0; i < others.length; i++) {
            polls[2 + i] = createPollFdForFileDescriptorWithEvents(ParcelFileDescriptor.fromDatagramSocket(others[i]).getFileDescriptor(), (short) OsConstants.POLLIN);
        }
    }

    @NonNull
    private static StructPollfd createPollFdForFileDescriptorWithEvents(FileDescriptor fileDescriptor, short events) {
        StructPollfd pollFd = new StructPollfd();
        pollFd.fd = fileDescriptor;
        pollFd.events = events;
        return pollFd;
    }

    boolean poll(ReadySocketCallback readySocketCallback, ResponseCallback responseCallback) throws ErrnoException, InterruptedException, IOException, PollGroupException {
        FileHelper.poll(polls, -1);

        if (blockFd.revents != 0) {
            return false;
        }

        for (int i = 0, j = 2; i < others.length; i++, j++) {
            if ((polls[j].revents & OsConstants.POLLIN) != 0) {
                readySocketCallback.ready(others[i]);
            }
        }

        responseCallback.response(deviceFd);

        return true;
    }
}
