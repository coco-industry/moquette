/*
 * Copyright (c) 2012-2018 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * The Apache License v2.0 is available at
 * http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.moquette.integration;

import io.moquette.broker.Server;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Properties;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import io.moquette.BrokerConstants;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Check that Moquette could also handle SSL with client authentication.
 *
 * This test verifies client's authentication on server, so the server certificate has to be imported into the
 * client's keystore and the client's certificate must be imported into server's keystore.
 *
 *  the first way is done by:
 *  <pre>
 *  keytool -genkeypair -alias testserver -keyalg RSA -validity 3650 -keysize 2048 -dname cn=localhost -keystore serverkeystore.jks -keypass passw0rdsrv -storepass passw0rdsrv
 *  </pre>
 *  and
 *  <pre>
 *  keytool -exportcert -alias testserver -keystore serverkeystore.jks -keypass passw0rdsrv -storepass passw0rdsrv | \
 *  keytool -importcert -trustcacerts -noprompt -alias testserver -keystore signedclientkeystore.jks -keypass passw0rd -storepass passw0rd
 *  </pre>
 *
 *  to create the key in the client side:
 *  <pre>
 *  keytool -genkeypair -alias signedtestclient -dname cn=client.moquette.io -validity 10000 -keyalg RSA -keysize 2048 -keystore signedclientkeystore.jks -keypass passw0rd -storepass passw0rd
 *  </pre>
 *
 *  to import the client's certificate into server:
 *  <pre>
 *  keytool -exportcert -alias signedtestclient -keystore signedclientkeystore.jks -keypass passw0rd -storepass passw0rd | \
 *  keytool -importcert -trustcacerts -noprompt -alias signedtestclient -keystore serverkeystore.jks -keypass passw0rdsrv -storepass passw0rdsrv
 *  </pre>
 *
 *  To verify that a client's certficate not imported into server, it's necessary to create a client's key:
 *  <pre>
 *  keytool -genkeypair -alias unsignedtestclient -dname cn=unverifiedclient.moquette.io -validity 10000 -keyalg RSA -keysize 2048 -keystore unsignedclientkeystore.jks -keypass passw0rd -storepass passw0rd
 *  </pre>
 *  and import into it the server's certificate:
 *  <pre>
 *  keytool -exportcert -alias testserver -keystore serverkeystore.jks -keypass passw0rdsrv -storepass passw0rdsrv | \
 *  keytool -importcert -trustcacerts -noprompt -alias testserver -keystore unsignedclientkeystore.jks -keypass passw0rd -storepass passw0rd
 *  </pre>
 *
 *
 * <p>
 * Create certificates needed for client authentication
 *
 * <pre>
 * # generate integration certificate chain (valid for 10000 days)
 * keytool -genkeypair -alias signedtestserver -dname cn=moquette.eclipse.org -keyalg RSA -keysize 2048 \
 * -keystore signedserverkeystore.jks -keypass passw0rdsrv -storepass passw0rdsrv -validity 10000
 * keytool -genkeypair -alias signedtestserver_sub -dname cn=moquette.eclipse.org -keyalg RSA -keysize 2048 \
 * -keystore signedserverkeystore.jks -keypass passw0rdsrv -storepass passw0rdsrv
 *
 * # sign integration subcertificate with integration certificate (valid for 10000 days)
 * keytool -certreq -alias signedtestserver_sub -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv | \
 * keytool -gencert -alias signedtestserver -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv -validity 10000 | \
 * keytool -importcert -alias signedtestserver_sub -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv
 *
 * # generate client keypair
 * keytool -genkeypair -alias signedtestclient -dname cn=moquette.eclipse.org -keyalg RSA -keysize 2048 \
 * -keystore signedclientkeystore.jks -keypass passw0rd -storepass passw0rd
 *
 * # create signed client certificate with integration subcertificate and import to client keystore (valid for 10000 days)
 * keytool -certreq -alias signedtestclient -keystore signedclientkeystore.jks -keypass passw0rd -storepass passw0rd | \
 * keytool -gencert -alias signedtestserver_sub -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv -validity 10000 | \
 * keytool -importcert -alias signedtestclient -keystore signedclientkeystore.jks -keypass passw0rd \
 * -storepass passw0rd -noprompt
 *
 * # import integration certificates into signed truststore
 * keytool -exportcert -alias signedtestserver -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv | \
 * keytool -importcert -trustcacerts -noprompt -alias signedtestserver -keystore signedclientkeystore.jks \
 * -keypass passw0rd -storepass passw0rd
 * keytool -exportcert -alias signedtestserver_sub -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv | \
 * keytool -importcert -trustcacerts -noprompt -alias signedtestserver_sub -keystore signedclientkeystore.jks \
 * -keypass passw0rd -storepass passw0rd
 *
 * # create unsigned client certificate (valid for 10000 days)
 * keytool -genkeypair -alias unsignedtestclient -dname cn=moquette.eclipse.org -validity 10000 -keyalg RSA \
 * -keysize 2048 -keystore unsignedclientkeystore.jks -keypass passw0rd -storepass passw0rd
 *
 * # import integration certificates into unsigned truststore
 * keytool -exportcert -alias signedtestserver -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv | \
 * keytool -importcert -trustcacerts -noprompt -alias signedtestserver -keystore unsignedclientkeystore.jks \
 * -keypass passw0rd -storepass passw0rd
 * keytool -exportcert -alias signedtestserver_sub -keystore signedserverkeystore.jks -keypass passw0rdsrv \
 * -storepass passw0rdsrv | \
 * keytool -importcert -trustcacerts -noprompt -alias signedtestserver_sub -keystore unsignedclientkeystore.jks \
 * -keypass passw0rd -storepass passw0rd
 * </pre>
 * </p>
 */
public class ServerIntegrationSSLClientAuthTest {

    private static final Logger LOG = LoggerFactory.getLogger(ServerIntegrationSSLClientAuthTest.class);

    Server m_server;

    IMqttClient m_client;
    MessageCollector m_callback;
    static String backup;

    @TempDir
    Path tempFolder;

    @BeforeAll
    public static void beforeTests() {
        backup = System.getProperty("moquette.path");
    }

    @AfterAll
    public static void afterTests() {
        if (backup == null)
            System.clearProperty("moquette.path");
        else
            System.setProperty("moquette.path", backup);
    }

    protected void startServer(String dbPath) throws IOException {
        String file = getClass().getResource("/").getPath();
        System.setProperty("moquette.path", file);
        m_server = new Server();

        Properties sslProps = new Properties();
        sslProps.put(BrokerConstants.SSL_PORT_PROPERTY_NAME, "8883");
        sslProps.put(BrokerConstants.JKS_PATH_PROPERTY_NAME, "src/test/resources/serverkeystore.jks");
        sslProps.put(BrokerConstants.KEY_STORE_PASSWORD_PROPERTY_NAME, "passw0rdsrv");
        sslProps.put(BrokerConstants.KEY_MANAGER_PASSWORD_PROPERTY_NAME, "passw0rdsrv");
        sslProps.put(BrokerConstants.PERSISTENT_STORE_PROPERTY_NAME, dbPath);
        sslProps.put(BrokerConstants.NEED_CLIENT_AUTH, "true");
        sslProps.put(BrokerConstants.ENABLE_TELEMETRY_NAME, "false");
        m_server.startServer(sslProps);
    }

    @BeforeEach
    public void setUp() throws Exception {
        String dbPath = IntegrationUtils.tempH2Path(tempFolder);
        File dbFile = new File(dbPath);
        assertFalse(dbFile.exists(), String.format("The DB storagefile %s already exists", dbPath));

        startServer(dbPath);

        MqttClientPersistence subDataStore = new MqttDefaultFilePersistence(IntegrationUtils.newFolder(tempFolder, "client").getAbsolutePath());
        m_client = new MqttClient("ssl://localhost:8883", "TestClient", subDataStore);
        // m_client = new MqttClient("ssl://test.mosquitto.org:8883", "TestClient", s_dataStore);

        m_callback = new MessageCollector();
        m_client.setCallback(m_callback);
    }

    @AfterEach
    public void tearDown() throws Exception {
        IntegrationUtils.disconnectClient(m_client);

        if (m_server != null) {
            m_server.stopServer();
        }
    }

    @Test
    public void checkClientAuthentication() throws Exception {
        LOG.info("*** checkClientAuthentication ***");
        SSLSocketFactory ssf = configureSSLSocketFactory("signedclientkeystore.jks");

        MqttConnectOptions options = new MqttConnectOptions();
        options.setSocketFactory(ssf);
        m_client.connect(options);
        m_client.subscribe("/topic", 0);
        m_client.disconnect();
    }

    @Test
    public void checkClientAuthenticationFail() throws Exception {
        LOG.info("*** checkClientAuthenticationFail ***");
        SSLSocketFactory ssf = configureSSLSocketFactory("unsignedclientkeystore.jks");

        MqttConnectOptions options = new MqttConnectOptions();
        options.setSocketFactory(ssf);
        // actual a "Broken pipe" is thrown, this is not very specific.
        assertThrows(MqttException.class, () -> m_client.connect(options));
    }

    private SSLSocketFactory configureSSLSocketFactory(String keystore) throws KeyManagementException,
            NoSuchAlgorithmException, UnrecoverableKeyException, IOException, CertificateException, KeyStoreException {
        KeyStore ks = KeyStore.getInstance("JKS");
        InputStream jksInputStream = getClass().getClassLoader().getResourceAsStream(keystore);
        ks.load(jksInputStream, "passw0rd".toCharArray());

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "passw0rd".toCharArray());

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext sc = SSLContext.getInstance("TLS");
        TrustManager[] trustManagers = tmf.getTrustManagers();
        sc.init(kmf.getKeyManagers(), trustManagers, null);

        SSLSocketFactory ssf = sc.getSocketFactory();
        return ssf;
    }
}
