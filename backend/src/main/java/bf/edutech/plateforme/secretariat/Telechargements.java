package bf.edutech.plateforme.secretariat;

import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Réponses de téléchargement (pièces jointes, PDF produits). */
final class Telechargements {

    private Telechargements() {
    }

    static ResponseEntity<byte[]> fichier(Secretariat.Fichier f) {
        String nom = f.nom().contains(".") ? f.nom() : f.nom() + Secretariat.extension(f.type());
        return reponse(f.contenu(), MediaType.parseMediaType(f.type()), nom);
    }

    static ResponseEntity<byte[]> pdf(byte[] contenu, String nom) {
        return reponse(contenu, MediaType.APPLICATION_PDF, nom);
    }

    private static ResponseEntity<byte[]> reponse(byte[] contenu, MediaType type, String nom) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nom, StandardCharsets.UTF_8).build().toString())
                .body(contenu);
    }
}
