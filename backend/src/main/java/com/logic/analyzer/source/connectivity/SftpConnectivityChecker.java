package com.logic.analyzer.source.connectivity;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceStatus;
import com.logic.analyzer.source.SourceType;
import com.logic.analyzer.source.dto.ConnectionTestResult;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

@Component
public class SftpConnectivityChecker implements SourceConnectivityChecker {

    private static final int DEFAULT_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    @Override
    public Set<SourceType> supports() {
        return Set.of(SourceType.SFTP);
    }

    @Override
    public ConnectionTestResult check(LogSource source) {
        Instant now = Instant.now();
        int port = source.getPort() != null ? source.getPort() : DEFAULT_PORT;

        try (SSHClient ssh = new SSHClient()) {
            // Known simplification for this initial phase: host key verification is
            // disabled so ad-hoc dev servers work without a pre-seeded known_hosts.
            // Future hardening: verify against a known_hosts file or pinned fingerprint.
            ssh.addHostKeyVerifier(new PromiscuousVerifier());
            ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
            ssh.connect(source.getHost(), port);
            ssh.authPassword(source.getUsername(), source.getPassword());

            try (SFTPClient sftp = ssh.newSFTPClient()) {
                sftp.stat(source.getPath());
            }
            return new ConnectionTestResult(SourceStatus.REACHABLE,
                    "Connected via SFTP and found remote path", now);
        } catch (Exception e) {
            return new ConnectionTestResult(SourceStatus.UNREACHABLE,
                    "SFTP connection failed: " + e.getMessage(), now);
        }
    }
}
