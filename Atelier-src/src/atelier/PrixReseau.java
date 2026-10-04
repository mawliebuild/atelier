package atelier;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Lecture d'une page web pour les prix (habbofurni.xyz), compressee (gzip).
 *
 * Pourquoi pas seulement HttpURLConnection : Java essaie UNE adresse du site
 * (IPv4 d'abord). Mesure du 4 oct. 2026 : l'adresse IPv4 de habbofurni.xyz
 * refuse les connexions (« Connection refused ») alors que l'IPv6 repond ;
 * un navigateur ou curl passent a l'adresse suivante, Java non. Resultat :
 * aucune lecture du site ne reussissait plus.
 *
 * Donc : d'abord la voie normale ; si la CONNEXION echoue (refus, pas de
 * route, delai), on essaie chaque adresse du site une a une, en HTTPS avec
 * verification du certificat et du nom (SNI). L'adresse qui marche est
 * retenue pour les lectures suivantes.
 */
final class PrixReseau {

    private PrixReseau() { }

    static final class Reponse {
        final int code;
        final String corps;
        final long octets;      // recus sur le reseau (compresses)
        Reponse(int code, String corps, long octets) { this.code = code; this.corps = corps; this.octets = octets; }
    }

    static final String UA = "Mozilla/5.0 (Macintosh) Atelier/1.0 (outil de build Habbo)";
    private static final int CONNEXION_MS = 8_000, LECTURE_MS = 20_000;
    /** Hote -> adresse qui repond, quand la voie normale echoue. */
    private static final Map<String, InetAddress> directes = new ConcurrentHashMap<>();

    /** GET ; IOException si le site ne repond pas du tout. */
    static Reponse lire(String adresse) throws IOException {
        URL u = new URL(adresse);
        InetAddress connue = directes.get(u.getHost());
        if (connue != null) {
            try { return direct(u, connue, 0); }
            catch (IOException e) { directes.remove(u.getHost()); }
        }
        try {
            return normal(u);
        } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
            if (e instanceof SocketTimeoutException && !String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("connect"))
                throw e;    // lecture trop lente : pas un probleme d'adresse
            return parAdresses(u, e);
        }
    }

    private static Reponse normal(URL u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(CONNEXION_MS);
        c.setReadTimeout(LECTURE_MS);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html");
        c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9");
        c.setRequestProperty("Accept-Encoding", "gzip");
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        byte[] brut = in == null ? new byte[0] : lireTout(in);
        return new Reponse(code, texte(brut, c.getContentEncoding()), brut.length);
    }

    private static Reponse parAdresses(URL u, IOException premiere) throws IOException {
        IOException derniere = premiere;
        for (InetAddress a : InetAddress.getAllByName(u.getHost())) {
            try {
                Reponse r = direct(u, a, 0);
                directes.put(u.getHost(), a);
                Journal.debug("réseau : " + u.getHost() + " joint par " + a.getHostAddress()
                        + " (voie normale : " + premiere + ").");
                return r;
            } catch (IOException e) { derniere = e; }
        }
        throw derniere;
    }

    /** HTTP/1.1 a la main sur une adresse choisie, HTTPS verifie. */
    private static Reponse direct(URL u, InetAddress a, int redirections) throws IOException {
        boolean https = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
        Socket brut = new Socket();
        try {
            brut.connect(new InetSocketAddress(a, port), CONNEXION_MS);
            brut.setSoTimeout(LECTURE_MS);
            Socket s = brut;
            if (https) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(brut, u.getHost(), port, true);
                SSLParameters p = ssl.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");      // certificat au nom du site
                p.setServerNames(List.of(new SNIHostName(u.getHost())));
                ssl.setSSLParameters(p);
                ssl.startHandshake();
                s = ssl;
            }
            String chemin = (u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath())
                    + (u.getQuery() == null ? "" : "?" + u.getQuery());
            String req = "GET " + chemin + " HTTP/1.1\r\nHost: " + u.getHost() + "\r\nUser-Agent: " + UA
                    + "\r\nAccept: text/html\r\nAccept-Language: fr-FR,fr;q=0.9\r\nAccept-Encoding: gzip"
                    + "\r\nConnection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Compteur in = new Compteur(new BufferedInputStream(s.getInputStream()));
            String statut = ligne(in);
            if (statut == null || !statut.startsWith("HTTP/")) throw new IOException("réponse illisible");
            String[] parts = statut.split(" ", 3);
            int code = Integer.parseInt(parts[1].trim());
            Map<String, String> entetes = new HashMap<>();
            for (String l; (l = ligne(in)) != null && !l.isEmpty(); ) {
                int i = l.indexOf(':');
                if (i > 0) entetes.put(l.substring(0, i).trim().toLowerCase(Locale.ROOT), l.substring(i + 1).trim());
            }
            if (code >= 300 && code < 400 && entetes.containsKey("location") && redirections < 3) {
                URL suite = new URL(u, entetes.get("location"));
                if (suite.getHost().equalsIgnoreCase(u.getHost())) return direct(suite, a, redirections + 1);
                return lire(suite.toString());
            }
            byte[] corps;
            if ("chunked".equalsIgnoreCase(entetes.get("transfer-encoding"))) corps = morceaux(in);
            else if (entetes.containsKey("content-length")) corps = in.readNBytes(Integer.parseInt(entetes.get("content-length")));
            else corps = lireTout(in);
            return new Reponse(code, texte(corps, entetes.get("content-encoding")), in.lus);
        } finally {
            try { brut.close(); } catch (IOException ignored) { }
        }
    }

    // ---------------------------------------------------------------- outils (testes)

    /** Une ligne d'en-tete (fin CRLF ou LF), ou null en fin de flux. */
    static String ligne(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16_384) throw new IOException("en-tête trop long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1);
    }

    /** Corps « Transfer-Encoding: chunked ». */
    static byte[] morceaux(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            String l = ligne(in);
            if (l == null) throw new EOFException("réponse coupée");
            int fin = l.indexOf(';');
            String hex = (fin >= 0 ? l.substring(0, fin) : l).trim();
            if (hex.isEmpty()) continue;
            int n = Integer.parseInt(hex, 16);
            if (n == 0) { while (true) { String t = ligne(in); if (t == null || t.isEmpty()) break; } break; }
            byte[] m = in.readNBytes(n);
            if (m.length < n) throw new EOFException("réponse coupée");
            b.write(m);
            ligne(in);       // CRLF apres le morceau
        }
        return b.toByteArray();
    }

    static String texte(byte[] brut, String encodage) throws IOException {
        if (encodage != null && encodage.toLowerCase(Locale.ROOT).contains("gzip") && brut.length > 0) {
            try (InputStream z = new GZIPInputStream(new ByteArrayInputStream(brut))) { brut = z.readAllBytes(); }
        }
        return new String(brut, StandardCharsets.UTF_8);
    }

    private static byte[] lireTout(InputStream in) throws IOException {
        try (InputStream i = in) { return i.readAllBytes(); }
    }

    /** Compte les octets recus. */
    private static final class Compteur extends FilterInputStream {
        long lus = 0;
        Compteur(InputStream in) { super(in); }
        @Override public int read() throws IOException { int c = super.read(); if (c >= 0) lus++; return c; }
        @Override public int read(byte[] b, int o, int n) throws IOException { int r = super.read(b, o, n); if (r > 0) lus += r; return r; }
    }
}
