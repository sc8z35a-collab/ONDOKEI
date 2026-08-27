package jp.rstlab.batteryrelay.share;

import android.net.Network;

import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.PublicKey;

import jp.rstlab.batteryrelay.core.CryptoBox;

public final class DiscoveredPeer {
    public final String serviceName;
    public final InetAddress host;
    public final int port;
    public final String shareId;
    public final byte[] salt;
    public final PublicKey publicKey;
    public final Network network;

    public DiscoveredPeer(String serviceName, InetAddress host, int port, String shareId,
                          byte[] salt, PublicKey publicKey) {
        this(serviceName, host, port, shareId, salt, publicKey, null);
    }

    public DiscoveredPeer(String serviceName, InetAddress host, int port, String shareId,
                          byte[] salt, PublicKey publicKey, Network network) {
        if (serviceName == null || host == null || shareId == null || salt == null || publicKey == null) {
            throw new IllegalArgumentException("Peer fields must not be null");
        }
        if (port < 1 || port > 65_535 || !isUsableUnicast(host)) {
            throw new IllegalArgumentException("Invalid peer endpoint");
        }
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
        this.shareId = shareId;
        this.salt = salt.clone();
        this.publicKey = publicKey;
        this.network = network;
    }

    static DiscoveredPeer fromTxt(String serviceName, InetAddress host, int port,
                                  byte[] sid, byte[] saltText, byte[] publicText)
            throws GeneralSecurityException {
        return fromTxt(serviceName, host, port, sid, saltText, publicText, null);
    }

    static DiscoveredPeer fromTxt(String serviceName, InetAddress host, int port,
                                  byte[] sid, byte[] saltText, byte[] publicText, Network network)
            throws GeneralSecurityException {
        if (host == null || port < 1 || port > 65_535 || sid == null || saltText == null
                || publicText == null || !isUsableUnicast(host)) {
            throw new IllegalArgumentException("Incomplete service record");
        }
        // TXT records are small by design. Reject unreasonable fields before Base64 allocation.
        if (sid.length > 64 || saltText.length > 128 || publicText.length > 512) {
            throw new IllegalArgumentException("Oversized service record");
        }
        String cleanName = serviceName == null ? "" : serviceName
                .replaceAll("[\\r\\n\\t]", " ").trim();
        if (cleanName.isEmpty() || cleanName.codePointCount(0, cleanName.length()) > 64) {
            throw new IllegalArgumentException("Invalid service name");
        }
        String shareId = new String(sid, java.nio.charset.StandardCharsets.UTF_8);
        byte[] salt = null;
        byte[] publicBytes = null;
        try {
            salt = CryptoBox.unb64(new String(saltText, java.nio.charset.StandardCharsets.UTF_8));
            publicBytes = CryptoBox.unb64(new String(publicText,
                    java.nio.charset.StandardCharsets.UTF_8));
            if (!shareId.matches("[A-Za-z0-9_-]{16}") || salt.length != 16
                    || publicBytes.length == 0 || publicBytes.length > 256) {
                throw new IllegalArgumentException("Invalid service record");
            }
            return new DiscoveredPeer(cleanName, host, port, shareId, salt,
                    CryptoBox.decodePublicKey(publicBytes), network);
        } catch (IllegalArgumentException malformedBase64) {
            throw new IllegalArgumentException("Invalid service record", malformedBase64);
        } finally {
            if (salt != null) java.util.Arrays.fill(salt, (byte) 0);
            if (publicBytes != null) java.util.Arrays.fill(publicBytes, (byte) 0);
        }
    }

    private static boolean isUsableUnicast(InetAddress address) {
        return address != null
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isMulticastAddress();
    }

    public String stableKey() {
        // Bind identity to both the advertised share generation and public key, never IP/port.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(publicKey.getEncoded());
            try {
                return shareId + ":" + CryptoBox.b64(digest).substring(0, 16);
            } finally {
                java.util.Arrays.fill(digest, (byte) 0);
            }
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
