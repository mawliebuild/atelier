package gearth.services.packet_info.providers;

import gearth.misc.Cacher;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Version de l'Atelier (remplace celle de G-Earth) : la liste des messages du
 * jeu (id <-> nom) d'une version de Habbo est GARDEE pour toujours dans le
 * dossier « messages » de l'Atelier (a cote d'Atelier.jar), et fournie avec le
 * paquet pour les versions deja connues. On ne la telecharge qu'une fois, pour
 * une version jamais vue. Le service « harble » (disparu) n'est plus contacte.
 */
public abstract class RemotePacketInfoProvider extends PacketInfoProvider {

    public RemotePacketInfoProvider(String hotelVersion) {
        super(hotelVersion);
    }

    protected abstract String getRemoteUrl();

    protected abstract String getCacheName();

    /** Dossier « messages » de l'Atelier (dossier de lancement = dossier d'Atelier.jar). */
    static File dossier() {
        return new File(System.getProperty("user.dir"), "messages");
    }

    @Override
    protected File getFile() {
        String nom = getCacheName();
        File local = new File(dossier(), nom + ".json");
        if (local.isFile() && local.length() > 100) return local;
        // ancienne copie de G-Earth : on la reprend dans notre dossier
        File ancien = new File(Cacher.getCacheDir(), nom);
        if (ancien.isFile() && ancien.length() > 100) {
            garder(local, ancien);
            return local.isFile() ? local : ancien;
        }
        // harble.net n'existe plus : inutile d'attendre
        if (nom.startsWith("HARBLE_API-")) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(getRemoteUrl()).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Atelier/1.0");
            if (c.getResponseCode() != 200) return null;
            byte[] corps;
            try (InputStream in = c.getInputStream()) { corps = in.readAllBytes(); }
            if (corps.length < 100) return null;
            dossier().mkdirs();
            Files.write(local.toPath(), corps);
            return local;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void garder(File dest, File source) {
        try {
            dossier().mkdirs();
            Files.copy(source.toPath(), dest.toPath());
        } catch (Throwable ignored) { }
    }
}
