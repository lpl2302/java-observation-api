package com.o3.server;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.Executors;

public class Server {

    private static SSLContext myServerSSLContext(String keystorePath, String keystorePassword) throws Exception {
        // load keystore used for both key and trust material.
        char[] passphrase = keystorePassword.toCharArray();

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(keystorePath), passphrase);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, passphrase);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ks);

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ssl;
    }

    private static String graphQlUrl() {
        // allow endpoint override for local tests/ci.
        String prop = System.getProperty("GRAPHQL_URL");
        if (prop != null && !prop.isBlank()) return prop;

        String env = System.getenv("GRAPHQL_URL");
        if (env != null && !env.isBlank()) return env;

        return "http://localhost:4003/graphql";
    }

    public static void main(String[] args) {
        try {
            // server needs keystore path + password as positional args.
            if (args.length < 2) {
                System.out.println("Usage: java -jar server.jar <keystore-path> <keystore-password>");
                return;
            }

            String keystorePath = args[0];
            String keystorePassword = args[1];

            String dbPath = System.getenv("DATABASE_PATH");
            if (dbPath == null || dbPath.isBlank()) dbPath = "database.db";

            // open sqlite database and create tables if needed.
            MessageDatabase db = MessageDatabase.getInstance();
            db.open(dbPath);

            HttpsServer server = HttpsServer.create(new InetSocketAddress(8001), 0);

            // close db/server gracefully when process stops.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { server.stop(1); } catch (Exception ignored) {}
                try { db.close(); } catch (Exception ignored) {}
            }));

            SSLContext sslContext = myServerSSLContext(keystorePath, keystorePassword);
            server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    // use default ssl parameters from configured context.
                    SSLContext c = getSSLContext();
                    SSLParameters sslParams = c.getDefaultSSLParameters();
                    params.setSSLParameters(sslParams);
                }
            });

            // shared mapper/service instances.
            ObservationMapper mapper = new ObservationMapper();
            WeatherService weather = new WeatherService();
            // write persistence snapshot at startup.
            ServerOutputSnapshot.write(db, mapper);

            RegistrationHandler registrationHandler = new RegistrationHandler(db);
            DataRecordHandler dataRecordHandler = new DataRecordHandler(db, mapper, weather);

            GraphQLConversionService conversionService = new GraphQLConversionService(graphQlUrl());
            PartialDataRecordHandler partialHandler = new PartialDataRecordHandler(db, conversionService, mapper);

            CollectionsHandler collectionsHandler = new CollectionsHandler(db, mapper);

            // unauthenticated registration endpoint.
            server.createContext("/registration", registrationHandler);

            UserAuthenticator authenticator = new UserAuthenticator(db);

            // authenticated observation and collection endpoints.
            HttpContext dataContext = server.createContext("/datarecord", dataRecordHandler);
            dataContext.setAuthenticator(authenticator);

            HttpContext partialContext = server.createContext("/datarecord/partial", partialHandler);
            partialContext.setAuthenticator(authenticator);

            HttpContext collectionsContext = server.createContext("/collections", collectionsHandler);
            collectionsContext.setAuthenticator(authenticator);

            // allow concurrent requests.
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();

            System.out.println("HTTPS server running at https://localhost:8001");
            System.out.println("GraphQL endpoint: " + graphQlUrl());

        } catch (Exception e) {
            System.out.println("Server failed to start:");
            e.printStackTrace();
        }
    }
}
