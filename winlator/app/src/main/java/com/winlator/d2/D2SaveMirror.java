package com.winlator.d2;

import android.app.Activity;
import android.content.Intent;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;















final class D2SaveMirror {
    static final String EXTRA = "d2_saves_spiegel";
    static final String RESULT = "ergebnis.txt";

    private D2SaveMirror() {
    }


    static boolean handle(Activity activity, Intent intent) {
        String direction = intent != null ? intent.getStringExtra(EXTRA) : null;
        if (direction == null) return false;
        File mirror = activity.getExternalFilesDir("spiegel");
        new Thread(() -> {
            String result = run(direction, mirror);
            D2Trace.log("saves mirror " + direction + ": " + result);
            if (mirror != null) {
                try (FileWriter out = new FileWriter(new File(mirror, RESULT))) {
                    out.write(result + "\n");
                }
                catch (IOException ignored) {

                }
            }
            activity.runOnUiThread(activity::finish);
        }, "d2-saves-mirror").start();
        return true;
    }


    // Thor D2: "spiel-weg" – for a fresh installation (e.g. to film it) without losing saved games. The saves are
    // mirrored to Android/data/<package>/files/spiegel/saves first (every .d2s checked), then only the game folder
    // goes; the app then shows "no game set up". After the new installation "ein" copies the saves back.
    private static String removeGameKeepSaves(File mirror) throws IOException {
        File saves = D2Store.saves(), game = D2Store.game();
        D2Umzug.mirrorOut(saves, mirror);
        File copy = new File(mirror, D2Umzug.SAVES);
        String[] names = saves.list();
        int checked = 0;
        for (String n : names != null ? names : new String[0]) {
            if (!n.toLowerCase(java.util.Locale.ROOT).endsWith(".d2s")) continue;
            File a = new File(saves, n), b = new File(copy, n);
            if (!b.isFile() || b.length() != a.length()) return "fehler: " + n + " nicht gespiegelt, nichts geloescht";
            checked++;
        }
        D2Umzug.delete(game);
        if (game.exists()) return "fehler: Spielordner nicht ganz geloescht (Spielstaende liegen im Spiegel)";
        return "ok spiel-weg (" + checked + " Charaktere im Spiegel)";
    }

    private static String run(String direction, File mirror) {
        if (!"aus".equals(direction) && !"ein".equals(direction) && !"spiel-weg".equals(direction))
            return "fehler: aus, ein oder spiel-weg, nicht " + direction;
        if (mirror == null) return "fehler: kein App-Ordner auf dem gemeinsamen Speicher";

        new File(mirror, RESULT).delete();
        if (!D2SpieltestPort.tokenFresh("saves mirror")) return "fehler: kein frischer adb-Marker";
        if (D2TouchOverlay.isGameRunning() || !D2Saves.beginImport()) return "fehler: das Spiel laeuft oder startet gerade";
        try {
            if (!D2Store.ready()) return "nicht intern";
            if ("spiel-weg".equals(direction)) return removeGameKeepSaves(mirror);
            if ("aus".equals(direction)) D2Umzug.mirrorOut(D2Store.saves(), mirror);
            else D2Umzug.mirrorIn(mirror, D2Store.base());
            return "ok " + direction;
        }
        catch (IOException | RuntimeException e) {
            return "fehler: " + e.getMessage();
        }
        finally {
            D2Saves.endImport();
        }
    }

}
