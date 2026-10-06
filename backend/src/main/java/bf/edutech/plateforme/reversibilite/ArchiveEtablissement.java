package bf.edutech.plateforme.reversibilite;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Écrit l'archive complète d'un établissement : une table cloisonnée = un fichier CSV, les
 * fichiers binaires (bulletins PDF, photos) à part, les comptes des personnes citées, un
 * manifeste qui décrit chaque colonne et un fichier d'empreintes SHA-256.
 * <p>
 * Doit être appelé dans une transaction en lecture seule, ouverte pour l'établissement :
 * les tables sont trouvées dans le catalogue de PostgreSQL (toutes celles qui ont la
 * Row-Level Security), si bien qu'une table ajoutée par une migration future est exportée
 * sans modifier ce code. Les tables qui ont une colonne {@code tenant_id} sont en plus
 * filtrées explicitement ; si le rôle de connexion contourne la RLS, l'export est refusé.
 */
final class ArchiveEtablissement {

    static final int VERSION_FORMAT = 1;
    static final String SEPARATEUR = ";";
    private static final String FIN_LIGNE = "\r\n";
    private static final Pattern NOM_SUR = Pattern.compile("[a-z_][a-z0-9_]*");
    /**
     * Colonnes jamais exportées : secrets chiffrés (clés d'API Mobile Money…), inutilisables hors
     * de la plateforme, et adresses IP du journal d'audit, que l'établissement ne voit pas non plus.
     */
    private static final Pattern SECRET = Pattern.compile(".*_chiffree?$|adresse_ip");

    record Resultat(int tables, long lignes) {
    }

    record Etablissement(UUID id, String code, String nom, String statut, Instant creeLe) {
    }

    private record Colonne(String nom, String type, String typeCourt, boolean obligatoire) {
    }

    private record TableExportee(String nom, String fichier, long lignes, List<String> clePrimaire,
            List<Colonne> colonnes, List<String> omises, int fichiersJoints) {
    }

    private final JdbcTemplate jdbc;
    private final String versionApplication;
    private final Instant genereLe;
    private final Map<String, String> empreintes = new LinkedHashMap<>();

    ArchiveEtablissement(JdbcTemplate jdbc, String versionApplication, Instant genereLe) {
        this.jdbc = jdbc;
        this.versionApplication = versionApplication;
        this.genereLe = genereLe;
    }

    Resultat ecrire(UUID tenantId, OutputStream sortie) throws IOException {
        verifierCloisonnement();
        Etablissement etab = jdbc.queryForObject(
                "select id, code, nom, statut, cree_le from tenant where id = ?",
                (rs, n) -> new Etablissement(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class).toInstant()),
                tenantId);

        ZipOutputStream zip = new ZipOutputStream(sortie, StandardCharsets.UTF_8);
        zip.setComment("Export complet - " + etab.nom());
        ecrireTexte(zip, "LISEZMOI.txt", lisezmoi(etab));

        List<TableExportee> tables = new ArrayList<>();
        long lignes = 0;
        for (String table : tablesCloisonnees()) {
            TableExportee t = exporterTable(zip, table, tenantId);
            tables.add(t);
            lignes += t.lignes();
        }
        long comptes = exporterComptes(zip);
        ecrireTexte(zip, "manifeste.json", manifeste(etab, tables, comptes));

        // Empreintes au format de « sha256sum -c »
        StringBuilder sommes = new StringBuilder();
        empreintes.forEach((chemin, h) -> sommes.append(h).append("  ").append(chemin).append('\n'));
        ecrireBrut(zip, "SHA256SUMS.txt", sommes.toString().getBytes(StandardCharsets.UTF_8));
        zip.finish();
        return new Resultat(tables.size(), lignes);
    }

    // ------------------------------------------------------------------ Catalogue

    private void verifierCloisonnement() {
        Boolean contourne = jdbc.queryForObject(
                "select rolsuper or rolbypassrls from pg_roles where rolname = current_user", Boolean.class);
        if (!Boolean.FALSE.equals(contourne)) {
            throw new IllegalStateException(
                    "Export refusé : le rôle de connexion contourne la Row-Level Security");
        }
        String tenant = jdbc.queryForObject("select current_setting('app.tenant_id', true)", String.class);
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalStateException("Export refusé : aucun établissement actif dans la transaction");
        }
    }

    private List<String> tablesCloisonnees() {
        return jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = current_schema() and c.relkind in ('r', 'p') and c.relrowsecurity
                  and c.relforcerowsecurity
                order by c.relname""", String.class).stream()
                .filter(t -> NOM_SUR.matcher(t).matches())
                .toList();
    }

    private List<Colonne> colonnes(String table) {
        return jdbc.query("""
                select a.attname, format_type(a.atttypid, a.atttypmod), t.typname, a.attnotnull
                from pg_attribute a join pg_type t on t.oid = a.atttypid
                where a.attrelid = ?::regclass and a.attnum > 0 and not a.attisdropped
                order by a.attnum""",
                (rs, n) -> new Colonne(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), table);
    }

    private List<String> clePrimaire(String table) {
        return jdbc.queryForList("""
                select a.attname from pg_index i
                join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any (i.indkey)
                where i.indrelid = ?::regclass and i.indisprimary
                order by array_position(i.indkey::int2[], a.attnum)""", String.class, table);
    }

    // ------------------------------------------------------------------ Tables

    private TableExportee exporterTable(ZipOutputStream zip, String table, UUID tenantId) throws IOException {
        List<Colonne> toutes = colonnes(table);
        List<Colonne> gardees = toutes.stream()
                .filter(c -> NOM_SUR.matcher(c.nom()).matches() && !SECRET.matcher(c.nom()).matches())
                .toList();
        List<String> omises = toutes.stream().filter(c -> !gardees.contains(c)).map(Colonne::nom).toList();
        List<String> cle = clePrimaire(table);
        boolean aTenant = toutes.stream().anyMatch(c -> c.nom().equals("tenant_id"));
        List<Colonne> binaires = gardees.stream().filter(c -> c.typeCourt().equals("bytea")).toList();
        // Les fichiers joints sont nommés d'après l'identifiant de la ligne
        boolean joignables = !binaires.isEmpty() && gardees.stream().anyMatch(c -> c.nom().equals("id"));
        String filtre = aTenant ? " where tenant_id = ?" : "";
        String tri = cle.isEmpty() ? "" : " order by " + cle.stream().map(c -> q(c)).collect(Collectors.joining(", "));

        // 1er passage : le CSV. Pour une colonne binaire, seuls les premiers octets sont lus
        // (type du fichier) ; le CSV reçoit le chemin du fichier dans l'archive.
        String sql = "select " + gardees.stream()
                .map(c -> c.typeCourt().equals("bytea") ? "substring(" + q(c.nom()) + " from 1 for 12)" : q(c.nom()))
                .collect(Collectors.joining(", "))
                + " from " + q(table) + filtre + tri;
        String fichier = "donnees/" + table + ".csv";
        long[] compteurs = new long[2]; // lignes, fichiers joints
        try (Entree entree = new Entree(zip, fichier)) {
            Writer w = entree.ecrivain();
            w.write('\uFEFF');
            w.write(gardees.stream().map(c -> csv(c.nom())).collect(Collectors.joining(SEPARATEUR)));
            w.write(FIN_LIGNE);
            int indexId = gardees.stream().map(Colonne::nom).toList().indexOf("id") + 1;
            jdbc.query(con -> {
                var ps = con.prepareStatement(sql);
                ps.setFetchSize(500);
                if (aTenant) {
                    ps.setObject(1, tenantId);
                }
                return ps;
            }, (ResultSet rs) -> {
                try {
                    compteurs[0]++;
                    StringBuilder ligne = new StringBuilder();
                    for (int i = 0; i < gardees.size(); i++) {
                        if (i > 0) {
                            ligne.append(SEPARATEUR);
                        }
                        Colonne c = gardees.get(i);
                        if (c.typeCourt().equals("bytea")) {
                            byte[] debut = rs.getBytes(i + 1);
                            if (debut != null && joignables) {
                                ligne.append(csv(cheminJoint(table, rs.getString(indexId), c, binaires.size(), debut)));
                                compteurs[1]++;
                            }
                        } else {
                            String v = valeur(rs, i + 1, c.typeCourt());
                            if (v != null) {
                                ligne.append(csv(v));
                            }
                        }
                    }
                    w.write(ligne.toString());
                    w.write(FIN_LIGNE);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        // 2e passage : chaque fichier joint, une ligne à la fois (jamais tous en mémoire)
        if (joignables) {
            for (Colonne c : binaires) {
                String sqlBinaire = "select id::text, " + q(c.nom()) + " from " + q(table) + filtre
                        + (filtre.isEmpty() ? " where " : " and ") + q(c.nom()) + " is not null" + tri;
                jdbc.query(con -> {
                    var ps = con.prepareStatement(sqlBinaire);
                    ps.setFetchSize(10);
                    if (aTenant) {
                        ps.setObject(1, tenantId);
                    }
                    return ps;
                }, (ResultSet rs) -> {
                    try {
                        byte[] octets = rs.getBytes(2);
                        ecrireBrut(zip, cheminJoint(table, rs.getString(1), c, binaires.size(), octets), octets);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        return new TableExportee(table, fichier, compteurs[0], cle, gardees, omises, (int) compteurs[1]);
    }

    private static String cheminJoint(String table, String id, Colonne c, int nombreBinaires, byte[] debut) {
        return "fichiers/" + table + "/" + id + (nombreBinaires > 1 ? "-" + c.nom() : "") + extension(debut);
    }

    private static String q(String identifiant) {
        if (!NOM_SUR.matcher(identifiant).matches()) {
            throw new IllegalArgumentException("Identifiant SQL inattendu : " + identifiant);
        }
        return "\"" + identifiant + "\"";
    }

    /**
     * Comptes des personnes citées dans les données de l'établissement (membres, enseignants,
     * parents, auteurs des saisies). Jamais de mot de passe ni d'information de connexion ;
     * le super administrateur de la plateforme n'y figure pas.
     */
    private long exporterComptes(ZipOutputStream zip) throws IOException {
        List<String[]> references = jdbc.query("""
                select cl.relname, a.attname
                from pg_constraint k
                join pg_class cl on cl.oid = k.conrelid
                join pg_attribute a on a.attrelid = k.conrelid and a.attnum = k.conkey[1]
                where k.contype = 'f' and k.confrelid = 'utilisateur'::regclass and cardinality(k.conkey) = 1
                  and cl.relrowsecurity and cl.relforcerowsecurity
                order by 1, 2""", (rs, n) -> new String[] { rs.getString(1), rs.getString(2) });
        String sousRequetes = references.stream()
                .filter(r -> NOM_SUR.matcher(r[0]).matches() && NOM_SUR.matcher(r[1]).matches())
                .map(r -> "select " + q(r[1]) + " from " + q(r[0]))
                .collect(Collectors.joining(" union "));
        String[] entetes = { "id", "telephone", "email", "nom", "prenoms", "actif", "cree_le" };
        long[] n = { 0 };
        try (Entree entree = new Entree(zip, "comptes/utilisateurs.csv")) {
            Writer w = entree.ecrivain();
            w.write('﻿');
            w.write(String.join(SEPARATEUR, entetes));
            w.write(FIN_LIGNE);
            if (!sousRequetes.isEmpty()) {
                jdbc.query("select id, telephone, email, nom, prenoms, actif, cree_le from utilisateur "
                        + "where not super_admin and id in (" + sousRequetes + ") order by nom, prenoms, id",
                        (ResultSet rs) -> {
                            try {
                                n[0]++;
                                StringBuilder ligne = new StringBuilder();
                                ligne.append(csv(rs.getString(1))).append(SEPARATEUR)
                                        .append(csv(rs.getString(2))).append(SEPARATEUR)
                                        .append(csv(rs.getString(3))).append(SEPARATEUR)
                                        .append(csv(rs.getString(4))).append(SEPARATEUR)
                                        .append(csv(rs.getString(5))).append(SEPARATEUR)
                                        .append(rs.getBoolean(6)).append(SEPARATEUR)
                                        .append(rs.getObject(7, OffsetDateTime.class).toInstant());
                                w.write(ligne.toString());
                                w.write(FIN_LIGNE);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
            entree.fermer();
        }
        return n[0];
    }

    // ------------------------------------------------------------------ Valeurs

    static String valeur(ResultSet rs, int i, String type) throws SQLException {
        switch (type) {
            case "bool": {
                boolean b = rs.getBoolean(i);
                return rs.wasNull() ? null : String.valueOf(b);
            }
            case "timestamptz": {
                OffsetDateTime t = rs.getObject(i, OffsetDateTime.class);
                return t == null ? null : t.toInstant().toString();
            }
            case "timestamp": {
                LocalDateTime t = rs.getObject(i, LocalDateTime.class);
                return t == null ? null : t.toString();
            }
            default:
                return rs.getString(i);
        }
    }

    /** Champ CSV : entre guillemets s'il contient le séparateur, un guillemet ou un retour à la ligne. */
    static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(SEPARATEUR) || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static String extension(byte[] o) {
        if (o.length >= 4 && o[0] == '%' && o[1] == 'P' && o[2] == 'D' && o[3] == 'F') {
            return ".pdf";
        }
        if (o.length >= 4 && (o[0] & 0xFF) == 0x89 && o[1] == 'P' && o[2] == 'N' && o[3] == 'G') {
            return ".png";
        }
        if (o.length >= 3 && (o[0] & 0xFF) == 0xFF && (o[1] & 0xFF) == 0xD8 && (o[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (o.length >= 12 && o[0] == 'R' && o[1] == 'I' && o[2] == 'F' && o[3] == 'F' && o[8] == 'W' && o[9] == 'E'
                && o[10] == 'B' && o[11] == 'P') {
            return ".webp";
        }
        return ".bin";
    }

    // ------------------------------------------------------------------ Textes

    private String lisezmoi(Etablissement e) {
        return """
                EXPORT COMPLET DES DONNÉES
                ==========================

                Établissement : %s (code %s)
                Généré le     : %s (UTC)
                Application   : version %s

                Cette archive contient toutes les données de l'établissement enregistrées
                sur la plateforme, dans des formats ouverts, pour pouvoir les conserver ou
                les reprendre dans un autre logiciel.

                Contenu
                -------
                donnees/           une table = un fichier CSV (voir manifeste.json pour
                                   la description de chaque colonne)
                fichiers/          documents enregistrés tels quels (bulletins PDF,
                                   photos) ; la colonne correspondante du CSV donne
                                   leur chemin dans l'archive
                comptes/           comptes des personnes citées dans les données
                                   (nom, téléphone, courriel) ; aucun mot de passe
                manifeste.json     description des tables, des colonnes, des clés
                SHA256SUMS.txt     empreinte de chaque fichier (vérification :
                                   sha256sum -c SHA256SUMS.txt)

                Format des fichiers CSV
                -----------------------
                - encodage UTF-8 avec BOM (s'ouvre directement dans Excel ou LibreOffice) ;
                - séparateur point-virgule (;), fin de ligne CRLF ;
                - un champ qui contient ; " ou un retour à la ligne est entre guillemets,
                  les guillemets internes sont doublés ;
                - champ vide = pas de valeur ;
                - dates AAAA-MM-JJ, heures HH:MM:SS, instants ISO 8601 en UTC
                  (2026-10-06T08:15:00Z) ; décimales avec un point ;
                - vrai/faux : true / false ;
                - les liens entre tables passent par les identifiants (colonne id et
                  colonnes *_id).

                Non exportés : les mots de passe (même chiffrés), les clés secrètes
                d'API (Mobile Money), inutilisables hors de la plateforme, et les
                adresses IP du journal d'audit.

                Ces données sont personnelles (élèves mineurs, parents, personnel) :
                conservez l'archive en lieu sûr et effacez-la quand elle ne sert plus.
                """.formatted(e.nom(), e.code(), genereLe, versionApplication);
    }

    private String manifeste(Etablissement e, List<TableExportee> tables, long comptes) {
        StringBuilder j = new StringBuilder();
        j.append("{\n");
        j.append("  \"format\": \"plateforme-ecoles/export-complet\",\n");
        j.append("  \"versionFormat\": ").append(VERSION_FORMAT).append(",\n");
        j.append("  \"versionApplication\": ").append(json(versionApplication)).append(",\n");
        j.append("  \"genereLe\": ").append(json(genereLe.toString())).append(",\n");
        j.append("  \"etablissement\": {\"id\": ").append(json(e.id().toString()))
                .append(", \"code\": ").append(json(e.code()))
                .append(", \"nom\": ").append(json(e.nom()))
                .append(", \"statut\": ").append(json(e.statut()))
                .append(", \"creeLe\": ").append(json(e.creeLe().toString())).append("},\n");
        j.append("  \"csv\": {\"encodage\": \"UTF-8\", \"bom\": true, \"separateur\": \";\", \"guillemet\": \"\\\"\", ")
                .append("\"finDeLigne\": \"\\r\\n\", \"valeurAbsente\": \"\", \"instants\": \"ISO 8601 UTC\"},\n");
        j.append("  \"comptes\": {\"fichier\": \"comptes/utilisateurs.csv\", \"lignes\": ").append(comptes).append("},\n");
        j.append("  \"tables\": [\n");
        for (int k = 0; k < tables.size(); k++) {
            TableExportee t = tables.get(k);
            j.append("    {\"nom\": ").append(json(t.nom()))
                    .append(", \"fichier\": ").append(json(t.fichier()))
                    .append(", \"lignes\": ").append(t.lignes())
                    .append(", \"sha256\": ").append(json(empreintes.get(t.fichier())))
                    .append(", \"clePrimaire\": [")
                    .append(t.clePrimaire().stream().map(ArchiveEtablissement::json).collect(Collectors.joining(", ")))
                    .append("]");
            if (t.fichiersJoints() > 0) {
                j.append(", \"fichiersJoints\": ").append(t.fichiersJoints());
            }
            if (!t.omises().isEmpty()) {
                j.append(", \"colonnesNonExportees\": [")
                        .append(t.omises().stream().map(ArchiveEtablissement::json).collect(Collectors.joining(", ")))
                        .append("]");
            }
            j.append(",\n      \"colonnes\": [");
            for (int c = 0; c < t.colonnes().size(); c++) {
                Colonne col = t.colonnes().get(c);
                j.append(c == 0 ? "\n" : ",\n");
                j.append("        {\"nom\": ").append(json(col.nom()))
                        .append(", \"type\": ").append(json(col.type()))
                        .append(", \"obligatoire\": ").append(col.obligatoire());
                if (col.typeCourt().equals("bytea")) {
                    j.append(", \"contenu\": \"chemin d'un fichier de l'archive\"");
                }
                j.append("}");
            }
            j.append("]}").append(k < tables.size() - 1 ? ",\n" : "\n");
        }
        j.append("  ]\n}\n");
        return j.toString();
    }

    static String json(String v) {
        if (v == null) {
            return "null";
        }
        StringBuilder s = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c < 0x20) {
                        s.append(String.format("\\u%04x", (int) c));
                    } else {
                        s.append(c);
                    }
                }
            }
        }
        return s.append('"').toString();
    }

    // ------------------------------------------------------------------ ZIP

    private void ecrireTexte(ZipOutputStream zip, String chemin, String texte) throws IOException {
        ecrireBrut(zip, chemin, texte.replace("\n", "\r\n").replace("\r\r\n", "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void ecrireBrut(ZipOutputStream zip, String chemin, byte[] octets) throws IOException {
        ZipEntry e = new ZipEntry(chemin);
        e.setTime(genereLe.toEpochMilli());
        zip.putNextEntry(e);
        zip.write(octets);
        zip.closeEntry();
        if (!chemin.equals("SHA256SUMS.txt")) {
            empreintes.put(chemin, HexFormat.of().formatHex(sha256().digest(octets)));
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Entrée ZIP écrite en flux (CSV) dont l'empreinte est calculée au fil de l'eau. */
    private final class Entree implements AutoCloseable {

        private final ZipOutputStream zip;
        private final String chemin;
        private final MessageDigest empreinte = sha256();
        private final Writer ecrivain;
        private boolean fermee;

        Entree(ZipOutputStream zip, String chemin) throws IOException {
            this.zip = zip;
            this.chemin = chemin;
            ZipEntry e = new ZipEntry(chemin);
            e.setTime(genereLe.toEpochMilli());
            zip.putNextEntry(e);
            OutputStream sansFermeture = new FilterOutputStream(zip) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    empreinte.update(b, off, len);
                    out.write(b, off, len);
                }

                @Override
                public void write(int b) throws IOException {
                    empreinte.update((byte) b);
                    out.write(b);
                }

                @Override
                public void close() throws IOException {
                    flush();
                }
            };
            this.ecrivain = new BufferedWriter(new OutputStreamWriter(sansFermeture, StandardCharsets.UTF_8), 64 * 1024);
        }

        Writer ecrivain() {
            return ecrivain;
        }

        void fermer() throws IOException {
            if (!fermee) {
                fermee = true;
                ecrivain.close();
                zip.closeEntry();
                empreintes.put(chemin, HexFormat.of().formatHex(empreinte.digest()));
            }
        }

        @Override
        public void close() throws IOException {
            fermer();
        }
    }
}
