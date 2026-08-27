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
        if (host == null || port < 1 || port > 65_535 || sid == null || saltText == null || publicText == null) {
            throw new IllegalArgumentException("Incomplete service record");
        }
        String cleanName = serviceName == null ? "" : serviceName
                .replaceAll("[\\r\\n\\t]", " ").trim();
        if (cleanName.isEmpty() || cleanName.codePointCount(0, cleanName.length()) > 64) {
            throw new IllegalArgumentException("Invalid service name");
        }
        String shareId = new String(sid, java.nio.charset.StandardCharsets.UTF_8);
        byte[] salt = CryptoBox.unb64(new String(saltText, java.nio.charset.StandardCharsets.UTF_8));
        byte[] publicBytes = CryptoBox.unb64(new String(publicText, java.nio.charset.StandardCharsets.UTF_8));
        if (!shareId.matches("[A-Za-z0-9_-]{16}") || salt.length != 16
                || publicBytes.length > 256) {
            throw new IllegalArgumentException("Invalid service record");
        }
        return new DiscoveredPeer(cleanName, host, port, shareId, salt,
                CryptoBox.decodePublicKey(publicBytes), network);
    }

    public String stableKey() {
        // Bind identity to both the advertised share generation and public key, never IP/port.
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(publicKey.getEncoded());
            return shareId + ":" + CryptoBox.b64(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
