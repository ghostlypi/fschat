package dev.fschat.daemon.remote;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

/** Builds an {@link SSLContext} that trusts a given PKCS12 truststore (dev self-signed certs). */
public final class ClientTls {

    private ClientTls() {
    }

    public static SSLContext trusting(Path truststore, char[] password) {
        try {
            KeyStore ts = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(truststore)) {
                ts.load(in, password);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("failed to load truststore " + truststore, e);
        }
    }
}
