package com.logic.analyzer.logstream.ingest;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.io.IOException;
import java.time.Instant;

public class SftpTailSource implements TailSource {

    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String remotePath;

    public SftpTailSource(String host, int port, String username, String password, String remotePath) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.remotePath = remotePath;
    }

    @Override
    public TailBytes readTail(int maxBytes) throws IOException {
        try (SSHClient ssh = new SSHClient()) {
            // Known simplification (shared with SftpConnectivityChecker): host key
            // verification is disabled so ad-hoc dev servers work without a
            // pre-seeded known_hosts. Future hardening: verify against a
            // known_hosts file or pinned fingerprint.
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
            ssh.connect(host, port);
            ssh.authPassword(username, password);

            try (SFTPClient sftp = ssh.newSFTPClient();
                 RemoteFile remoteFile = sftp.open(remotePath)) {
                long size = remoteFile.length();
                long offset = Math.max(0, size - maxBytes);
                int length = (int) (size - offset);
                byte[] buf = new byte[length];
                if (length > 0) {
                    remoteFile.read(offset, buf, 0, length);
                }
                return new TailBytes(buf, offset == 0);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SFTP tail read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Fingerprint probe() throws IOException {
        try (SSHClient ssh = new SSHClient()) {
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
            ssh.connect(host, port);
            ssh.authPassword(username, password);

            try (SFTPClient sftp = ssh.newSFTPClient()) {
                FileAttributes attrs = sftp.stat(remotePath);
                return new Fingerprint(attrs.getSize(), Instant.ofEpochSecond(attrs.getMtime()));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SFTP probe failed: " + e.getMessage(), e);
        }
    }
}
