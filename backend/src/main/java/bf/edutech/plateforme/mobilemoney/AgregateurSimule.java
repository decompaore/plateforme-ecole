package bf.edutech.plateforme.mobilemoney;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Agrégateur simulé, pour le développement et les tests (interdit en production par
 * {@code app.mobile-money.simulateur-autorise=false}). Les transactions restent en
 * mémoire ; {@link #confirmer} et {@link #refuser} jouent le rôle du parent.
 * Notification : {@code {"reference":"…"}}, signée par l'en-tête {@code X-Signature}
 * (HMAC-SHA256 hexadécimal du corps avec le secret de notification de l'école).
 */
@Component
public class AgregateurSimule implements Agregateur {

    static final String CODE = "SIMULATEUR";
    static final String ENTETE_SIGNATURE = "X-Signature";
    private static final Pattern REFERENCE = Pattern.compile("\"reference\"\\s*:\\s*\"([^\"]{1,40})\"");

    private static final class Ligne {
        final long montantDemande;
        volatile Etat etat = Etat.EN_ATTENTE;
        volatile long montantRecu;
        volatile LocalDate jour;
        final String referenceAgregateur = "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Ligne(long montantDemande) {
            this.montantDemande = montantDemande;
        }
    }

    private final Map<String, Map<String, Ligne>> parMarchand = new ConcurrentHashMap<>();
    private final Map<String, List<LigneReleve>> lignesAjoutees = new ConcurrentHashMap<>();
    private final Clock horloge;
    private volatile boolean enPanne;

    AgregateurSimule(Clock horloge) {
        this.horloge = horloge;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public EtatDistant initier(Identifiants identifiants, Demande demande) {
        if (enPanne) {
            throw new AgregateurIndisponibleException("Simulateur en panne");
        }
        Ligne ligne = new Ligne(demande.montant());
        transactions(identifiants.identifiantMarchand()).put(demande.reference(), ligne);
        return new EtatDistant(Etat.EN_ATTENTE, null, ligne.referenceAgregateur, null);
    }

    @Override
    public EtatDistant consulter(Identifiants identifiants, String reference) {
        if (enPanne) {
            throw new AgregateurIndisponibleException("Simulateur en panne");
        }
        Ligne ligne = transactions(identifiants.identifiantMarchand()).get(reference);
        if (ligne == null) {
            return new EtatDistant(Etat.ECHOUEE, null, null, "Transaction inconnue de l'agrégateur");
        }
        return new EtatDistant(ligne.etat, ligne.etat == Etat.CONFIRMEE ? ligne.montantRecu : null,
                ligne.referenceAgregateur, ligne.etat == Etat.ECHOUEE ? "Refusée par le client" : null);
    }

    @Override
    public List<LigneReleve> releve(Identifiants identifiants, LocalDate jour) {
        if (enPanne) {
            throw new AgregateurIndisponibleException("Simulateur en panne");
        }
        List<LigneReleve> lignes = new ArrayList<>();
        transactions(identifiants.identifiantMarchand()).forEach((reference, l) -> {
            if (l.etat == Etat.CONFIRMEE && jour.equals(l.jour)) {
                lignes.add(new LigneReleve(reference, l.referenceAgregateur, l.montantRecu));
            }
        });
        lignes.addAll(lignesAjoutees.getOrDefault(identifiants.identifiantMarchand() + "|" + jour, List.of()));
        return lignes;
    }

    @Override
    public boolean signatureValide(Identifiants identifiants, byte[] corps, HttpHeaders entetes) {
        String recue = entetes.getFirst(ENTETE_SIGNATURE);
        if (recue == null) {
            return false;
        }
        return MessageDigest.isEqual(signer(identifiants.secretWebhook(), corps).getBytes(StandardCharsets.US_ASCII),
                recue.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public String referenceNotifiee(byte[] corps) {
        Matcher m = REFERENCE.matcher(new String(corps, StandardCharsets.UTF_8));
        return m.find() ? m.group(1) : null;
    }

    // ---------------------------------------------------------------- pilotage (dév. et tests)

    /** Le parent confirme : le montant demandé est reçu. */
    public void confirmer(String identifiantMarchand, String reference) {
        Ligne l = ligne(identifiantMarchand, reference);
        confirmer(identifiantMarchand, reference, l.montantDemande);
    }

    /** Confirmation avec un montant reçu différent (cas à vérifier). */
    public void confirmer(String identifiantMarchand, String reference, long montantRecu) {
        Ligne l = ligne(identifiantMarchand, reference);
        l.montantRecu = montantRecu;
        l.jour = LocalDate.now(horloge);
        l.etat = Etat.CONFIRMEE;
    }

    public void refuser(String identifiantMarchand, String reference) {
        ligne(identifiantMarchand, reference).etat = Etat.ECHOUEE;
    }

    public void panne(boolean enPanne) {
        this.enPanne = enPanne;
    }

    /** Ajoute au relevé une transaction que la plateforme ne connaît pas. */
    public void ajouterAuReleve(String identifiantMarchand, LocalDate jour, LigneReleve ligne) {
        lignesAjoutees.computeIfAbsent(identifiantMarchand + "|" + jour, k -> new ArrayList<>()).add(ligne);
    }

    /** Signature d'une notification (HMAC-SHA256 hexadécimal). */
    public static String signer(String secret, byte[] corps) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(corps));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Ligne> transactions(String marchand) {
        return parMarchand.computeIfAbsent(marchand, k -> new ConcurrentHashMap<>());
    }

    private Ligne ligne(String marchand, String reference) {
        Ligne l = transactions(marchand).get(reference);
        if (l == null) {
            throw new IllegalArgumentException("Transaction simulée inconnue : " + reference);
        }
        return l;
    }
}
